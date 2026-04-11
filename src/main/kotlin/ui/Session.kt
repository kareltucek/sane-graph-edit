package ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Persistent per-user session state: the list of files that were
 * open in tabs when the editor last exited (or when a session-save
 * was last flushed), plus which one was active.
 *
 * Not a history of every tab that was ever open — just the set at
 * the most recent snapshot. Untitled tabs are intentionally dropped
 * from the session because there's no stable identity to restore
 * them from; the autosave layer handles their in-flight work via
 * [AutosaveManager] and [XdgPaths.backupsDir].
 *
 * Instance-based rather than object-based so tests can point it at
 * a temp file without monkey-patching XDG env vars. Production
 * code uses [default].
 */
class Session(private val file: Path) {

    /**
     * Snapshot of the tab state we care about restoring:
     *
     *  - [files] is the ordered list of file-backed tabs left to
     *    right in the tab bar.
     *  - [activeFile] is whichever one was focused. Null means
     *    "no preference" — the loader will default to tab 0.
     */
    data class Snapshot(
        val files: List<Path>,
        val activeFile: Path?,
    )

    /**
     * Read the snapshot from disk. Returns an empty snapshot when
     * the file is missing or unreadable — session restore is
     * strictly best-effort; we never want a corrupted session file
     * to prevent the editor from starting.
     */
    fun load(): Snapshot {
        if (!Files.exists(file)) return EMPTY
        val props = Properties()
        try {
            Files.newInputStream(file).use(props::load)
        } catch (_: Throwable) {
            return EMPTY
        }
        val count = props.getProperty("openFiles.count")?.toIntOrNull() ?: 0
        val files = (0 until count).mapNotNull { i ->
            props.getProperty("openFiles.$i")
                ?.takeIf { it.isNotBlank() }
                ?.let(Paths::get)
        }
        val activeStr = props.getProperty("activeFile")
        val active = activeStr?.takeIf { it.isNotBlank() }?.let(Paths::get)
        return Snapshot(files, active)
    }

    /**
     * Write the snapshot to disk, best-effort. A failure here
     * should never bubble up — if we can't persist the session,
     * that's annoying but not crash-worthy.
     */
    fun save(snapshot: Snapshot) {
        try {
            Files.createDirectories(file.parent)
            val props = Properties()
            props.setProperty("openFiles.count", snapshot.files.size.toString())
            snapshot.files.forEachIndexed { i, p ->
                props.setProperty("openFiles.$i", p.toAbsolutePath().toString())
            }
            snapshot.activeFile?.let {
                props.setProperty("activeFile", it.toAbsolutePath().toString())
            }
            Files.newOutputStream(file).use {
                props.store(it, "sane-graph-edit session")
            }
        } catch (_: Throwable) {
            // best-effort
        }
    }

    companion object {
        val EMPTY: Snapshot = Snapshot(files = emptyList(), activeFile = null)

        /** Production singleton pointed at `$XDG_CONFIG_HOME/sane-graph-edit/session.properties`. */
        val default: Session by lazy {
            Session(XdgPaths.appConfigDir.resolve("session.properties"))
        }
    }
}
