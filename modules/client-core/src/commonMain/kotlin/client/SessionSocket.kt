package client

/** Where the editor session of a workspace (a directory) listens. */
object SessionSocket {
    /**
     * `$XDG_RUNTIME_DIR/sane-graph-edit/<hash of the directory>.sock`.
     * A hash, not the path: socket paths are limited to about a
     * hundred bytes.
     */
    fun pathFor(workspace: Path): Path =
        Fs.runtimeDir.resolve("sane-graph-edit").resolve(sha1Hex(workspace.toAbsolutePath().normalize().toString()).take(16) + ".sock")
}
