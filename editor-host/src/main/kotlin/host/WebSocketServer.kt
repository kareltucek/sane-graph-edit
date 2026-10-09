package host

import editor.EditorHub
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.experimental.xor

/**
 * Lets other processes join the session through a WebSocket on this
 * machine's loopback address: the same JSON as [SocketServer], one
 * message per text frame. For clients that cannot open a Unix
 * socket, such as a browser.
 *
 * Any web page can try to connect to a local port, so a client must
 * know [token]: it connects to [url]. Port 0 picks a free one.
 */
class WebSocketServer(
    private val hub: EditorHub,
    private val requestedPort: Int,
    val token: String = newToken(),
    private val onEditorThread: (() -> Unit) -> Unit,
) {
    private val server = ServerSocket()

    val port: Int get() = server.localPort
    val url: String get() = "ws://127.0.0.1:$port/?token=$token"

    fun start(): WebSocketServer {
        server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), requestedPort))
        thread(isDaemon = true, name = "editor-websocket-accept") {
            runCatching {
                while (true) {
                    val socket = server.accept()
                    thread(isDaemon = true, name = "editor-websocket-client") { serve(socket) }
                }
            }
        }
        return this
    }

    fun close() {
        runCatching { server.close() }
    }

    private fun serve(socket: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            if (handshake(input, output)) {
                socket.soTimeout = 0
                servePeer(hub, onEditorThread, read = { message -> receive(input, output, message) }, write = { send(output, TEXT, it.toByteArray(UTF_8)) })
            }
        } catch (_: IOException) {
            // gone before it was a client
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Answers the HTTP request that opens a WebSocket; false when it was refused. */
    private fun handshake(input: DataInputStream, output: DataOutputStream): Boolean {
        val request = line(input).split(' ')
        val headers = generateSequence { line(input).takeIf { it.isNotEmpty() } }
            .take(MAX_HEADERS)
            .mapNotNull { h -> h.indexOf(':').takeIf { it > 0 }?.let { h.substring(0, it).trim().lowercase() to h.substring(it + 1).trim() } }
            .toMap()
        val key = headers["sec-websocket-key"]
        val asksForWebSocket = request.size == 3 && request[0] == "GET" && headers["upgrade"].equals("websocket", ignoreCase = true) && key != null
        val given = request.getOrNull(1)?.substringAfter('?', "")?.split('&')?.firstOrNull { it.startsWith("token=") }?.substringAfter('=').orEmpty()
        val knowsToken = MessageDigest.isEqual(URLDecoder.decode(given, UTF_8).toByteArray(UTF_8), token.toByteArray(UTF_8))
        val answer = when {
            !asksForWebSocket -> "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            !knowsToken -> "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            else -> {
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + HANDSHAKE_GUID).toByteArray(UTF_8)))
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
            }
        }
        output.write(answer.toByteArray(UTF_8))
        output.flush()
        return asksForWebSocket && knowsToken
    }

    private fun line(input: DataInputStream): String {
        val bytes = ByteArrayOutputStream()
        var b = input.readUnsignedByte()
        while (b != '\n'.code) {
            if (bytes.size() >= MAX_HEADER_LINE) throw IOException("header line too long")
            bytes.write(b)
            b = input.readUnsignedByte()
        }
        return bytes.toString(UTF_8).trimEnd('\r')
    }

    /** Hands over the client's text messages until it closes the connection. */
    private fun receive(input: DataInputStream, output: DataOutputStream, message: (String) -> Unit) {
        val text = ByteArrayOutputStream()
        var open = true
        while (open) {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            val length = when (val short = b1 and 0x7f) {
                126 -> input.readUnsignedShort().toLong()
                127 -> input.readLong()
                else -> short.toLong()
            }
            // a client's frames are always masked
            if (b1 and 0x80 == 0 || length < 0 || length + text.size() > MAX_MESSAGE) throw IOException("bad frame")
            val mask = ByteArray(4).also(input::readFully)
            val payload = ByteArray(length.toInt()).also(input::readFully)
            for (i in payload.indices) payload[i] = payload[i] xor mask[i % 4]
            when (b0 and 0x0f) {
                CONTINUATION, TEXT -> {
                    text.write(payload)
                    if (b0 and 0x80 != 0) {
                        message(text.toString(UTF_8))
                        text.reset()
                    }
                }
                PING -> send(output, PONG, payload)
                PONG -> {}
                CLOSE -> {
                    send(output, CLOSE, payload.copyOf(minOf(payload.size, 2)))
                    open = false
                }
                else -> throw IOException("unsupported frame")
            }
        }
    }

    private fun send(output: DataOutputStream, opcode: Int, payload: ByteArray) {
        synchronized(output) {
            output.writeByte(0x80 or opcode)
            when {
                payload.size < 126 -> output.writeByte(payload.size)
                payload.size < 65536 -> {
                    output.writeByte(126)
                    output.writeShort(payload.size)
                }
                else -> {
                    output.writeByte(127)
                    output.writeLong(payload.size.toLong())
                }
            }
            output.write(payload)
            output.flush()
        }
    }

    companion object {
        private const val CONTINUATION = 0
        private const val TEXT = 1
        private const val CLOSE = 8
        private const val PING = 9
        private const val PONG = 10
        private const val HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val MAX_HEADERS = 100
        private const val MAX_HEADER_LINE = 8192
        private const val MAX_MESSAGE = 64L * 1024 * 1024

        fun newToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
