package host

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.SocketChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Decides which process owns the session of a socket: the one
 * that accepts connections there. Asking the socket and starting
 * to listen is done by one process at a time, the one that could
 * create `<socket>.starting`; every kind of process does it this
 * way (the JavaScript one is `SessionServer.start`).
 *
 * This kind also holds a lock on `<socket>.lock`, which the
 * operating system drops when the process ends, however it ends:
 * earlier versions decided by that lock alone and still do.
 */
object SessionLock {
    /** Somebody who is in the middle of [claim] and has not finished for this long has died in it. */
    private const val STARTING_STALE_MS = 5000L

    // referenced forever: a collected channel would release the lock
    private val held: MutableList<FileLock> = mutableListOf()

    /**
     * Become the owner of the session at [socket] by running
     * [listen], which must make this process accept connections
     * there, and return what it returns. Null, with [listen] not
     * run, if another process owns the session.
     */
    fun <T> claim(socket: Path, listen: () -> T): T? {
        Files.createDirectories(socket.parent)
        val starting = socket.resolveSibling("${socket.fileName}.starting")
        return if (!lock(socket) || !begin(starting)) {
            null
        } else {
            try {
                if (answers(socket)) null else listen()
            } finally {
                runCatching { Files.deleteIfExists(starting) }
            }
        }
    }

    private fun lock(socket: Path): Boolean {
        val channel = FileChannel.open(socket.resolveSibling("${socket.fileName}.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = channel.tryLock()
        if (lock == null) channel.close() else held.add(lock)
        return lock != null
    }

    /** True if this process created [starting], if need be after removing one left by a process that died. */
    private fun begin(starting: Path): Boolean {
        fun create(): Boolean =
            try {
                Files.createFile(starting)
                true
            } catch (_: FileAlreadyExistsException) {
                false
            }
        fun stale(): Boolean = runCatching { System.currentTimeMillis() - Files.getLastModifiedTime(starting).toMillis() > STARTING_STALE_MS }.getOrDefault(false)
        return create() || (stale() && runCatching { Files.deleteIfExists(starting) }.isSuccess && create())
    }

    /** True if somebody accepts a connection at [socket]. */
    private fun answers(socket: Path): Boolean =
        runCatching { SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) } }.getOrDefault(false)
}
