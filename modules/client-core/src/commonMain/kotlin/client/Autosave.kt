package client

import DotGraphLoader
import Graph

/**
 * Periodic crash-safety snapshots for every view with unsaved
 * changes.
 *
 * On a fixed interval every such view is written to
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
 * Filename scheme: `<basename>.<hash>.<id>.dot`, see [BackupPaths].
 *
 * Design notes:
 *
 *  - **On the client's thread.** The [scheduler] runs the flush
 *    where the views are edited, so reading a graph is race-free.
 *  - **Best-effort.** Every IO path swallows exceptions — a
 *    backup failure (disk full, permission denied) must never
 *    kill the editor.
 *  - **No deletion, ever.** This is by design: the user asked for
 *    an accumulating safety net and will handle pruning.
 */
class Autosave(
    /** The views to look after: an application's tabs. */
    private val views: () -> List<EditorView>,
    private val scheduler: Scheduler,
    private val backupDir: Path = Fs.cacheDir.resolve("backups"),
    private val intervalMs: Int = DEFAULT_INTERVAL_MS,
) {
    private var cancel: (() -> Unit)? = null
    private val idAllocator: BackupIdAllocator = BackupIdAllocator(backupDir)

    /**
     * Begin the periodic backups. Safe to call once. Creates
     * [backupDir] on demand; if that fails, autosave is silently
     * disabled rather than blocking startup.
     */
    fun start() {
        val ready = try {
            Fs.createDirectories(backupDir)
            true
        } catch (_: Exception) {
            false
        }
        if (ready) schedule()
    }

    private fun schedule() {
        cancel = scheduler.after(intervalMs) {
            flushAll()
            schedule()
        }
    }

    /** Stop the periodic backups. Call when the application ends. */
    fun stop() {
        cancel?.invoke()
        cancel = null
    }

    /** Write every view with unsaved changes right now. */
    fun flushAll() {
        val views = views()
        views.filter { it.content.isDirty }.forEach { writeBackup(it.g, it.content.currentFile, it.autosaveId) }
        // and those the views left, kept to go back to
        views.flatMap { it.content.unsavedKept() }.distinct().filter { k -> views.none { it.content.document === k } }
            .forEach { writeBackup(it.graph, it.info.file?.let { file -> Fs.path(file) }, it.info.document) }
    }

    /**
     * Writes a single snapshot of [graph] (of [file], or of the
     * untitled one [untitledId]) to a freshly numbered file in
     * [backupDir]. On a failure the id is *not* advanced — the next
     * tick retries with the same id: we'd rather fill holes than
     * skip numbers silently.
     */
    private fun writeBackup(graph: Graph, file: Path?, untitledId: String) {
        val prefix = BackupPaths.prefixFor(file, untitledId)
        val path = backupDir.resolve(BackupPaths.filenameFor(prefix, idAllocator.peek(prefix)))
        try {
            Fs.createDirectories(backupDir)
            DotGraphLoader.saveToFile(graph, path.toString())
            idAllocator.advance(prefix)
        } catch (_: Exception) {
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
        const val DEFAULT_INTERVAL_MS: Int = 5 * 60 * 1000
    }
}

/**
 * How backup files are named: `<basename>.<hash>.<id>.dot`.
 *
 *  - **`basename`** is the source file's name without the
 *    trailing `.dot` (or `untitled` for views that have never been
 *    saved). Makes backups easy to find with `ls` or tab-complete.
 *  - **`hash`** is a short SHA-1 prefix of the absolute source
 *    path — disambiguates between two files that share a
 *    basename (e.g. `foo/graph.dot` vs `bar/graph.dot`) without
 *    making the filename itself path-dependent. For untitled
 *    views it's a prefix of the view's own id.
 *  - **`id`** is a monotonically increasing integer per
 *    `(basename, hash)` prefix, see [BackupIdAllocator].
 *
 * Examples: `foo.3a1b9cd0.1.dot`, `foo.3a1b9cd0.2.dot`,
 * `untitled.8e7d2f4a.1.dot`.
 */
object BackupPaths {
    /** Source-file basename without the `.dot` suffix, or `untitled`. */
    fun basenameFor(file: Path?): String = file?.name?.removeSuffix(".dot") ?: "untitled"

    /**
     * Short disambiguator hash: the first 8 hex chars of
     * `SHA-1(abs path)` for file-backed views, or the first 8
     * chars of the view's id for untitled ones.
     */
    fun hashFor(file: Path?, untitledId: String): String =
        file?.toAbsolutePath()?.toString()?.let { sha1Hex(it).take(8) } ?: untitledId.take(8)

    /** `"<basename>.<hash>"` — the part of a backup filename that's stable across ids. */
    fun prefixFor(file: Path?, untitledId: String): String = "${basenameFor(file)}.${hashFor(file, untitledId)}"

    /** `"<prefix>.<id>.dot"`. */
    fun filenameFor(prefix: String, id: Int): String = "$prefix.$id.dot"

    /**
     * Parse a backup filename back into its `(prefix, id)` pair.
     * Returns null if the name does not match the scheme.
     *
     * The prefix may itself contain dots (basename can, and
     * typically does — e.g. `my.graph`), so we split from the
     * *last* dot, not the first. The prefix must contain at least
     * one dot itself (the one between basename and hash): that is
     * how unrelated files that happen to end in `.N.dot` are
     * rejected.
     */
    fun parseFilename(name: String): Pair<String, Int>? {
        val stem = name.removeSuffix(".dot")
        val lastDot = stem.lastIndexOf('.')
        val id = stem.substring(lastDot + 1).toIntOrNull()
        val prefix = stem.substring(0, lastDot.coerceAtLeast(0))
        return if (name.endsWith(".dot") && lastDot > 0 && id != null && prefix.contains('.')) prefix to id else null
    }
}

/**
 * Per-prefix monotonic id allocator backed by an in-memory
 * counter that's seeded from the filesystem on first lookup, so
 * ids keep climbing across editor restarts.
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
    // remembered at once: looked up again after the file was written, the directory would count that file too and a number would be skipped
    fun peek(prefix: String): Int = nextIds.getOrPut(prefix) { scanMaxId(prefix) + 1 }

    /** Move the counter for [prefix] one step forward. */
    fun advance(prefix: String) {
        nextIds[prefix] = peek(prefix) + 1
    }

    /** The highest id among the files of [prefix] in [backupDir]; zero if the directory is missing or has none. */
    private fun scanMaxId(prefix: String): Int =
        try {
            if (Fs.exists(backupDir)) Fs.list(backupDir).mapNotNull { BackupPaths.parseFilename(it.name) }.filter { it.first == prefix }.maxOfOrNull { it.second } ?: 0 else 0
        } catch (_: Exception) {
            0
        }
}
