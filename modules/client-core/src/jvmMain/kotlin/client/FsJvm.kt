package client

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

actual typealias Path = java.nio.file.Path

actual object Fs {
    actual fun path(text: String): Path = Paths.get(text)

    actual val home: String get() = System.getProperty("user.home")

    actual val configDir: Path get() = XdgPaths.appConfigDir

    actual val cacheDir: Path get() = XdgPaths.appCacheDir

    actual val runtimeDir: Path
        get() = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("java.io.tmpdir"), "runtime-${System.getProperty("user.name")}")

    actual fun exists(path: Path): Boolean = Files.exists(path)

    actual fun isDirectory(path: Path, followLinks: Boolean): Boolean =
        if (followLinks) Files.isDirectory(path) else Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

    actual fun list(dir: Path): List<Path> = Files.list(dir).use { it.toList() }

    actual fun readText(path: Path): String = Files.readString(path)

    actual fun readLines(path: Path, upTo: Int): List<String> {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return java.io.InputStreamReader(Files.newInputStream(path), decoder).buffered().useLines { it.take(upTo).toList() }
    }

    actual fun writeText(path: Path, text: String) {
        Files.writeString(path, text)
    }

    actual fun createDirectories(dir: Path) {
        Files.createDirectories(dir)
    }
}

actual fun sha1Hex(text: String): String =
    java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
