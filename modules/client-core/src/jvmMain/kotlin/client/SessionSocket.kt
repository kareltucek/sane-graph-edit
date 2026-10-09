package client

import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/** Where the editor session of a workspace (a directory) listens. */
object SessionSocket {
    /**
     * `$XDG_RUNTIME_DIR/sane-graph-edit/<hash of the directory>.sock`.
     * A hash, not the path: socket paths are limited to about a
     * hundred bytes.
     */
    fun pathFor(workspace: Path): Path {
        val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("java.io.tmpdir"), "runtime-${System.getProperty("user.name")}")
        val digest = MessageDigest.getInstance("SHA-1").digest(workspace.toAbsolutePath().normalize().toString().toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(16)
        return runtime.resolve("sane-graph-edit").resolve("$hash.sock")
    }
}
