package ui

import DotGraphLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask
import javax.swing.SwingUtilities

/**
 * Periodic crash-safety snapshots for every dirty tab.
 *
 * On a fixed interval, walks every [GraphView] in the owning
 * [TabManager] and writes any dirty tab to
 * `$XDG_CACHE_HOME/sane-graph-edit/backups/` as a plain DOT file.
 *
 * The intended use case is **safeguarding against the user's own
 * saving mistakes**: overwrote the wrong file, saved after
 * deleting nodes you meant to keep, accidentally clobbered a
 * graph with an empty document. Every tick produces a *new*
 * numbered snapshot — backups accumulate and are **never deleted
 * automatically**. The user is expected to prune the directory
 * by hand when it gets large, and to manually copy a recovered
 * DOT file back into place if something goes wrong.
 *
 * Filename scheme: `<basename>.<hash>.<id>.dot`
 *
 *  - **`basename`** is the source file's name without the
 *    trailing `.dot` (or `untitled` for tabs that have never been
 *    saved). Makes backups easy to find with `ls` or tab-complete.
 *  - **`hash`** is a short SHA-1 prefix of the absolute source
 *    path — disambiguates between two files that share a
 *    basename (e.g. `foo/graph.dot` vs `bar/graph.dot`) without
 *    making the filename itself path-dependent. For untitled
 *    tabs it's a prefix of the per-tab autosave UUID.
 *  - **`id`** is a monotonically increasing integer per
 *    `(basename, hash)` prefix. The allocator scans the backup
 *    directory on first write to resume from whatever ids
 *    already exist, so ids keep climbing across editor
 *    restarts.
 *
 * Examples:
 * ```
 * foo.3a1b9cd0.1.dot
 * foo.3a1b9cd0.2.dot
 * untitled.8e7d2f4a.1.dot
 * ```
 *
 * Design notes:
 *
 *  - **EDT-bound.** The timer thread just posts an `invokeLater`;
 *    the actual flush runs on the Swing EDT alongside normal
 *    mutations, so reading `Graph.nodes`/`edges` is race-free.
 *  - **Best-effort.** Every IO path swallows exceptions — a
 *    backup failure (disk full, permission denied) must never
 *    kill the editor.
 *  - **No deletion, ever.** There is no `deleteBackup` method
 *    and no cleanup path. This is by design: the user asked for
 *    an accumulating safety net and will handle pruning.
 */
class AutosaveManager(
    private val tabManager: TabManager,
    private val backupDir: Path = XdgPaths.backupsDir,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var timer: Timer? = null
    private val idAllocator: BackupIdAllocator = BackupIdAllocator(backupDir)

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
     * Writes a single snapshot of [gv] to a freshly numbered
     * file in [backupDir]. Picks the next id lazily via
     * [idAllocator], which scans the directory on its first
     * lookup for a given prefix so ids keep climbing across
     * editor restarts.
     *
     * On a serialization or IO failure, the id is *not* advanced
     * — the next tick retries with the same id. That's
     * deliberate: we'd rather fill holes than skip numbers
     * silently.
     */
    private fun writeBackup(gv: GraphView) {
        val prefix = BackupPaths.prefixFor(gv.currentFile, gv.autosaveId)
        val id = idAllocator.peek(prefix)
        val filename = BackupPaths.filenameFor(prefix, id)
        val path = backupDir.resolve(filename)
        try {
            Files.createDirectories(backupDir)
            DotGraphLoader.saveToFile(gv.g, path.toString())
            idAllocator.advance(prefix)
        } catch (_: Throwable) {
            // best-effort — a failed backup does not fail the session
        }
    }

    companion object {
        /**
         * 5 minutes. Long enough that the backup directory
         * doesn't balloon during a normal editing session, short
         * enough that the worst-case lost work after a crash is
         * a few minutes of edits.
         */
        const val DEFAULT_INTERVAL_MS: Long = 5 * 60 * 1000L
    }
}

/**
 * Pure filename-construction helpers, separated out so unit
 * tests can exercise the scheme without instantiating an
 * [AutosaveManager].
 */
object BackupPaths {
    /** Source-file basename without the `.dot` suffix, or `untitled`. */
    fun basenameFor(file: Path?): String =
        file?.fileName?.toString()?.removeSuffix(".dot") ?: "untitled"

    /**
     * Short disambiguator hash: the first 8 hex chars of
     * `SHA-1(abs path)` for file-backed tabs, or the first 8
     * chars of the autosave UUID for untitled tabs. Keeps the
     * filename compact while still separating e.g.
     * `~/foo/graph.dot` from `~/bar/graph.dot`.
     */
    fun hashFor(file: Path?, untitledId: String): String =
        file?.toAbsolutePath()?.toString()
            ?.let { sha1Hex(it).take(8) }
            ?: untitledId.take(8)

    /** `"<basename>.<hash>"` — the part of a backup filename that's stable across ids. */
    fun prefixFor(file: Path?, untitledId: String): String =
        "${basenameFor(file)}.${hashFor(file, untitledId)}"

    /** `"<prefix>.<id>.dot"`. */
    fun filenameFor(prefix: String, id: Int): String = "$prefix.$id.dot"

    /**
     * Parse a backup filename back into its `(prefix, id)` pair.
     * Returns null if the name does not match the scheme. The
     * allocator uses this to recover the highest existing id
     * for a prefix on first lookup.
     *
     * The prefix may itself contain dots (basename can, and
     * typically does — e.g. `my.graph`), so we split from the
     * *last* dot, not the first.
     */
    fun parseFilename(name: String): Pair<String, Int>? {
        if (!name.endsWith(".dot")) return null
        val stem = name.removeSuffix(".dot")
        val lastDot = stem.lastIndexOf('.')
        if (lastDot <= 0) return null
        val idStr = stem.substring(lastDot + 1)
        val id = idStr.toIntOrNull() ?: return null
        val prefix = stem.substring(0, lastDot)
        // Require the prefix to contain at least one dot itself
        // (the one between basename and hash). This is how we
        // reject unrelated files that happen to end in `.N.dot`.
        if (!prefix.contains('.')) return null
        return prefix to id
    }

    private fun sha1Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Per-prefix monotonic id allocator backed by an in-memory
 * counter that's seeded from the filesystem on first lookup.
 *
 * Split out of [AutosaveManager] so unit tests can drive it
 * against a temp directory without needing a tab manager or a
 * timer.
 */
class BackupIdAllocator(private val backupDir: Path) {
    private val nextIds: MutableMap<String, Int> = mutableMapOf()

    /**
     * Return the id that the next backup for [prefix] should
     * use, without advancing the counter. If the caller
     * successfully writes the file, it must call [advance] with
     * the same prefix; on failure, skipping the advance means
     * the next tick retries with the same id.
     */
    fun peek(prefix: String): Int =
        nextIds[prefix] ?: (scanMaxId(prefix) + 1)

    /** Move the counter for [prefix] one step forward. */
    fun advance(prefix: String) {
        val current = peek(prefix)
        nextIds[prefix] = current + 1
    }

    /**
     * Scan [backupDir] for files whose parsed prefix equals
     * [prefix] and return the highest id found — or zero if the
     * directory is missing or empty. Called once per prefix, on
     * the first [peek]; subsequent lookups hit the cached
     * counter.
     */
    private fun scanMaxId(prefix: String): Int {
        if (!Files.exists(backupDir)) return 0
        return try {
            Files.list(backupDir).use { stream ->
                stream
                    .map { it.fileName.toString() }
                    .map { BackupPaths.parseFilename(it) }
                    .filter { it != null && it.first == prefix }
                    .map { it!!.second }
                    .max(Comparator.naturalOrder())
                    .orElse(0)
            }
        } catch (_: Throwable) {
            0
        }
    }
}
