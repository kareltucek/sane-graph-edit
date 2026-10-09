package client

/**
 * Persistent per-workspace session state: the list of files that were
 * open in tabs when the editor last exited (or when a session-save
 * was last flushed), plus which one was active.
 *
 * Not a history of every tab that was ever open — just the set at
 * the most recent snapshot. Untitled tabs are intentionally dropped
 * from the session because there's no stable identity to restore
 * them from; the autosave layer handles their in-flight work.
 *
 * Instance-based rather than object-based so tests can point it at
 * a temp file without monkey-patching XDG env vars. Production
 * code uses [Workspace.session] — one session per workspace.
 *
 * The file is a Java properties file ([PropertiesText]): every
 * client reads and writes the same one, whatever it runs on.
 */
class Session(private val file: Path) {

    /**
     * Snapshot of the tab state we care about restoring:
     *
     *  - [files] is the ordered list of file-backed tabs left to
     *    right in the tab bar.
     *  - [activeFile] is whichever one was focused. Null means
     *    "no preference" — the loader will default to tab 0.
     *  - [viewTransforms] maps each restored file's absolute path
     *    to its per-tab transform (pan + zoom), stored as the six
     *    numbers of [ViewTransform.matrix]. Paths that aren't in
     *    this map restore to identity and get the first-paint
     *    placing as before.
     */
    data class Snapshot(
        val files: List<Path>,
        val activeFile: Path?,
        val viewTransforms: Map<Path, List<Double>> = emptyMap(),
    )

    /**
     * Read the snapshot from disk. Returns an empty snapshot when
     * the file is missing or unreadable — session restore is
     * strictly best-effort; we never want a corrupted session file
     * to prevent the editor from starting.
     */
    fun load(): Snapshot =
        try {
            if (Fs.exists(file)) parse(PropertiesText.decode(Fs.readText(file))) else EMPTY
        } catch (_: Exception) {
            EMPTY
        }

    private fun parse(props: Map<String, String>): Snapshot {
        val count = props["openFiles.count"]?.toIntOrNull() ?: 0
        val files = (0 until count).mapNotNull { i -> props["openFiles.$i"]?.takeIf { it.isNotBlank() }?.let { Fs.path(it) } }
        val active = props["activeFile"]?.takeIf { it.isNotBlank() }?.let { Fs.path(it) }
        val transforms = props
            .filterKeys { it.startsWith(VIEW_TRANSFORM_PREFIX) && it.length > VIEW_TRANSFORM_PREFIX.length }
            .mapNotNull { (name, value) ->
                val numbers = value.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                if (numbers.size == 6) Fs.path(name.removePrefix(VIEW_TRANSFORM_PREFIX)) to numbers else null
            }
            .toMap()
        return Snapshot(files, active, transforms)
    }

    /**
     * Write the snapshot to disk, best-effort. A failure here
     * should never bubble up — if we can't persist the session,
     * that's annoying but not crash-worthy.
     */
    fun save(snapshot: Snapshot) {
        try {
            file.getParent()?.let { Fs.createDirectories(it) }
            val props = linkedMapOf<String, String>()
            // a file open in several tabs is restored in one, or the tabs would pile up from run to run
            val files = snapshot.files.distinctBy { it.toAbsolutePath().normalize().toString() }
            props["openFiles.count"] = files.size.toString()
            files.forEachIndexed { i, p -> props["openFiles.$i"] = p.toAbsolutePath().toString() }
            snapshot.activeFile?.let { props["activeFile"] = it.toAbsolutePath().toString() }
            snapshot.viewTransforms
                .filterValues { it.size == 6 }
                .forEach { (path, matrix) -> props[VIEW_TRANSFORM_PREFIX + path.toAbsolutePath().toString()] = matrix.joinToString(",") }
            Fs.writeText(file, PropertiesText.encode(props, "sane-graph-edit session"))
        } catch (_: Exception) {
            // best-effort
        }
    }

    companion object {
        private const val VIEW_TRANSFORM_PREFIX = "viewTransform."
        val EMPTY: Snapshot = Snapshot(files = emptyList(), activeFile = null)
    }
}

/**
 * The text of a Java properties file, as `java.util.Properties`
 * writes it and as much of what it reads as it writes: one
 * `key=value` per line, comments, backslash escapes. Not read:
 * a value continued on the next line.
 */
object PropertiesText {
    fun encode(properties: Map<String, String>, comment: String): String =
        "#$comment\n" + properties.entries.joinToString("") { (key, value) -> "${escape(key, everySpace = true)}=${escape(value, everySpace = false)}\n" }

    fun decode(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trimStart() }
            .filter { it.isNotEmpty() && it[0] != '#' && it[0] != '!' }
            .associate { line ->
                val end = keyEnd(line)
                // after the key: blanks, at most one separator, blanks
                val afterBlanks = line.drop(end).trimStart()
                val value = if (afterBlanks.startsWith("=") || afterBlanks.startsWith(":")) afterBlanks.drop(1).trimStart() else afterBlanks
                unescape(line.take(end)) to unescape(value)
            }

    /** Where the key of [line] ends: at the first `=`, `:` or blank that is not escaped. */
    private fun keyEnd(line: String): Int {
        var i = 0
        while (i < line.length && line[i] != '=' && line[i] != ':' && !line[i].isWhitespace()) i += if (line[i] == '\\') 2 else 1
        return i.coerceAtMost(line.length)
    }

    private fun escape(text: String, everySpace: Boolean): String = buildString {
        text.forEachIndexed { i, c ->
            when {
                c == ' ' -> append(if (everySpace || i == 0) "\\ " else " ")
                c == '\t' -> append("\\t")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\u000C' -> append("\\f")
                c == '\\' || c == '=' || c == ':' || c == '#' || c == '!' -> append('\\').append(c)
                c.code < 0x20 || c.code > 0x7e -> append("\\u").append(c.code.toString(16).padStart(4, '0').uppercase())
                else -> append(c)
            }
        }
    }

    private fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c != '\\' || next == null -> {
                    append(c)
                    i += 1
                }
                next == 'u' && i + 6 <= text.length -> {
                    append(text.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 6
                }
                else -> {
                    append(
                        when (next) {
                            't' -> '\t'
                            'n' -> '\n'
                            'r' -> '\r'
                            'f' -> '\u000C'
                            else -> next
                        },
                    )
                    i += 2
                }
            }
        }
    }
}
