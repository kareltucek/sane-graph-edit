package client

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The workspace: the working directory, and the state kept for it.
 * Starts as the directory the editor was launched in; `:cd` switches
 * it ([TabManager.switchWorkspace]). Relative paths typed into the
 * `:` bar are relative to [dir], and each workspace has its own
 * session under `$XDG_CONFIG_HOME/sane-graph-edit/workspaces/`.
 */
object Workspace {
    var dir: Path = Paths.get("").toAbsolutePath().normalize()

    /** [path] as an absolute path; a relative one is relative to [dir]. */
    fun resolve(path: Path): Path = dir.resolve(path).normalize()

    /** Where the state of the workspace [workspaceDir] is kept, below [configDir]. */
    fun stateDir(workspaceDir: Path = dir, configDir: Path = XdgPaths.appConfigDir): Path =
        configDir.resolve("workspaces").resolve(encode(workspaceDir))

    /** The session of the current workspace. */
    fun session(): Session = Session(stateDir().resolve("session.properties"))

    /** [dir] as a single file name: `/home/me` is `%2Fhome%2Fme`. */
    fun encode(dir: Path): String =
        dir.toAbsolutePath().normalize().toString().replace("%", "%25").replace("/", "%2F")
}
