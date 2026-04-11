package ui

import DotGraphLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask
import javax.swing.SwingUtilities

/**
 * Periodic crash-safety backup for every open tab.
 *
 * On a fixed interval, walks every [GraphView] in the owning
 * [TabManager] and writes any dirty tab to
 * `$XDG_CACHE_HOME/sane-graph-edit/backups/` as a plain DOT file.
 * Saving the tab or closing it deletes the corresponding backup,
 * so the backup directory reflects only the in-flight unsaved
 * work.
 *
 * Design notes:
 *
 *  - **EDT-bound.** The timer thread just posts an `invokeLater`;
 *    the actual flush runs on the Swing EDT alongside normal
 *    mutations, so reading `Graph.nodes`/`edges` is race-free.
 *  - **Best-effort.** Every IO path swallows exceptions — a
 *    backup failure (disk full, permission denied) must never
 *    kill the editor.
 *  - **Not an undo log.** Backups are overwrites, not history. If
 *    the user wants to recover a specific prior state, that's
 *    what undo and version control are for.
 *  - **Filename collisions** are possible if the user opens the
 *    same file in two tabs (same hash → same backup slot). The
 *    second tab's writes clobber the first's. This is an
 *    accepted limitation; closing either tab deletes the backup.
 */
class AutosaveManager(
    private val tabManager: TabManager,
    private val backupDir: Path = XdgPaths.backupsDir,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var timer: Timer? = null

    /**
     * Begin the periodic backup thread. Safe to call once. Creates
     * [backupDir] on demand; if that fails, autosave is silently
     * disabled rather than blocking startup.
     */
    fun start() {
        try {
            Files.createDirectories(backupDir)
        } catch (_: Throwable) {
            return
        }
        val t = Timer("sane-graph-edit-autosave", /* isDaemon = */ true)
        t.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                SwingUtilities.invokeLater { flushAll() }
            }
        }, intervalMs, intervalMs)
        timer = t
    }

    /** Stop the periodic thread. Call from the window-close handler. */
    fun stop() {
        timer?.cancel()
        timer = null
    }

    /** Flush every dirty tab right now. Safe to call from the EDT. */
    fun flushAll() {
        tabManager.forEachView { gv ->
            if (gv.isDirty) writeBackup(gv)
        }
    }

    /**
     * Delete every backup file that could plausibly belong to
     * [gv]: both the file-path-based and the untitled-UUID-based
     * variants. Called after a successful save (so the freshly
     * written source file supersedes any backup) and after a tab
     * is closed (so orphan backups don't accumulate).
     *
     * Deleting *both* variants is how save-as cleans up: the tab
     * had an untitled-UUID backup before the save, and a
     * file-path backup would exist after — but since we're saving
     * the source file now, neither backup is needed.
     */
    fun deleteBackup(gv: GraphView) {
        val candidates = buildList {
            gv.currentFile?.let { add(BackupPaths.fileBased(backupDir, it)) }
            add(BackupPaths.untitledBased(backupDir, gv.autosaveId))
        }
        candidates.forEach { p ->
            try {
                Files.deleteIfExists(p)
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }

    /** The backup path that [gv] would write to, if it wrote now. */
    fun pathFor(gv: GraphView): Path =
        BackupPaths.forView(backupDir, gv.currentFile, gv.autosaveId)

    private fun writeBackup(gv: GraphView) {
        try {
            val path = pathFor(gv)
            Files.createDirectories(path.parent)
            DotGraphLoader.saveToFile(gv.g, path.toString())
        } catch (_: Throwable) {
            // best-effort — a failed backup does not fail the session
        }
    }

    companion object {
        /** 30 seconds. Short enough to bound data loss, long enough not to thrash disk on large graphs. */
        const val DEFAULT_INTERVAL_MS: Long = 30_000
    }
}

/**
 * Pure path-derivation helpers for autosave, split out of
 * [AutosaveManager] so that unit tests can exercise the hashing
 * and filename scheme without spinning up a tab manager.
 */
object BackupPaths {
    /**
     * Deterministic backup slot for a view. Tabs with a file use
     * a truncated SHA-1 of the absolute source path (so reopening
     * the same file on the next launch reuses the same backup
     * slot); untitled tabs use a per-tab UUID passed in as
     * [untitledId].
     */
    fun forView(backupDir: Path, file: Path?, untitledId: String): Path =
        file?.let { fileBased(backupDir, it) }
            ?: untitledBased(backupDir, untitledId)

    fun fileBased(backupDir: Path, file: Path): Path {
        // 16 hex chars (64 bits) is plenty of collision resistance
        // for "at most a few thousand files per user"; trims the
        // filename to something human-scannable in `ls`.
        val hash = sha1Hex(file.toAbsolutePath().toString()).take(16)
        return backupDir.resolve("f-$hash.dot")
    }

    fun untitledBased(backupDir: Path, untitledId: String): Path =
        backupDir.resolve("u-$untitledId.dot")

    private fun sha1Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
