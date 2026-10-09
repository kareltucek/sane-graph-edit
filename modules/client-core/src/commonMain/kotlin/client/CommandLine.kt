package client

/** What a [CommandLine] needs of the widget that shows it: one line of text the user edits. */
interface CommandLineWidget {
    /** Show the line with [text] in it, the caret at its end, and give it the keys. */
    fun show(text: String)

    /** Hide the line; the keys are the view's again. */
    fun hide()

    /** Show [text] beside the open line (what Tab could complete to). */
    fun showList(text: String)

    fun hideList()
}

/**
 * The `:` / `/` / `?` line of a view: what is typed into it and
 * what that does. The widget reports the user's editing ([typed])
 * and the keys that mean something here; everything else about a
 * text field is the widget's own.
 *
 * `:` runs a command on Enter. `/` and `?` search: matching nodes
 * are selected while the user types, Enter keeps them and shows
 * them, Escape puts the selection back.
 */
class CommandLine(
    private val view: EditorView,
    private val widget: CommandLineWidget,
    private val keys: () -> KeyMapper?,
) {
    var text: String = ""
        private set

    var isOpen: Boolean = false
        private set

    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var savedInput = ""

    /** `/` or `?`, not `:`. */
    private var searchMode = false

    fun open(prefix: String = ":") {
        searchMode = prefix == "/" || prefix == "?"
        if (searchMode) {
            // Escape puts this back
            view.searchState.selectionBeforeSearch = view.g.selectedNodes.toSet()
            view.searchState.forward = prefix == "/"
        }
        historyIndex = -1
        savedInput = ""
        isOpen = true
        put(prefix)
        view.repaint()
    }

    private fun put(text: String) {
        this.text = text
        widget.show(text)
    }

    /** The user edited the line; it now holds [text]. */
    fun typed(text: String) {
        this.text = text
        val prefix = text.take(1)
        if (searchMode && isOpen && (prefix == "/" || prefix == "?")) liveSearch(text.drop(1))
    }

    private fun matching(query: String) = view.g.nodes.filter { view.g.isVisible(it) }.filter { SearchState.matches(it.attributes.text, query) }

    /** Select what matches so far; nothing typed yet is the selection from before. */
    private fun liveSearch(query: String) {
        view.g.cleanSelect(if (query.isEmpty()) view.searchState.selectionBeforeSearch else matching(query).toSet())
        view.repaint()
    }

    // --- completion (Tab / Shift+Tab / Ctrl+D) ---

    /** Candidates being cycled by repeated Tabs, and where their word starts. */
    private var cycle: List<String> = emptyList()
    private var cycleIndex = 0
    private var cycleWordStart = 0

    /** The text after the last completion; Tab on unchanged text cycles. */
    private var lastCompleted: String? = null

    /** The list of [listCompletions] is showing; it goes with the line. */
    private var showingCompletions = false

    private fun completionFor(line: String): CommandCompletion.Result =
        CommandCompletion.complete(line, KeyMapper.BUILTIN_COMMANDS + CommandRegistry.list())

    /**
     * Tab, vim-like: a single match is completed (plus a space after a whole
     * word); several extend to their longest common prefix, then
     * repeated Tabs cycle through them ([backward]: Shift+Tab).
     * Nothing in a search.
     */
    fun complete(backward: Boolean) {
        if (!searchMode) completeCommand(backward)
    }

    private fun completeCommand(backward: Boolean) {
        val line = text.removePrefix(":")
        if (text == lastCompleted && cycle.isNotEmpty()) {
            cycleIndex = (cycleIndex + (if (backward) -1 else 1)).mod(cycle.size)
            replaceWord(line, cycleWordStart, cycle[cycleIndex])
        } else {
            val r = completionFor(line)
            val word = line.substring(r.wordStart)
            val common = CommandCompletion.commonPrefix(r.candidates)
            cycle = emptyList()
            when {
                r.candidates.size == 1 -> {
                    val c = r.candidates.single()
                    replaceWord(line, r.wordStart, if (c.endsWith("/") || c.endsWith("=")) c else "$c ")
                }
                r.candidates.size > 1 && common.length > word.length -> replaceWord(line, r.wordStart, common)
                r.candidates.size > 1 -> {
                    cycle = r.candidates
                    cycleWordStart = r.wordStart
                    cycleIndex = if (backward) cycle.size - 1 else 0
                    replaceWord(line, r.wordStart, cycle[cycleIndex])
                }
            }
        }
        lastCompleted = text
    }

    private fun replaceWord(line: String, wordStart: Int, replacement: String) = put(":" + line.substring(0, wordStart) + replacement)

    /** Ctrl+D: show what Tab could complete to. Nothing in a search. */
    fun listCompletions() {
        if (!searchMode) {
            val r = completionFor(text.removePrefix(":"))
            widget.showList(if (r.candidates.isEmpty()) "(no completions)" else r.candidates.joinToString("\n"))
            showingCompletions = true
        }
    }

    // --- history ---

    fun historyUp() {
        val reachable = history.isNotEmpty() && historyIndex != 0
        if (reachable) {
            if (historyIndex == -1) {
                savedInput = text
                historyIndex = history.size - 1
            } else {
                historyIndex--
            }
            put(history[historyIndex])
        }
    }

    fun historyDown() {
        if (historyIndex != -1) {
            if (historyIndex < history.size - 1) {
                historyIndex++
                put(history[historyIndex])
            } else {
                historyIndex = -1
                put(savedInput)
            }
        }
    }

    // --- closing ---

    private fun close() {
        if (showingCompletions) widget.hideList()
        showingCompletions = false
        cycle = emptyList()
        lastCompleted = null
        text = ""
        searchMode = false
        isOpen = false
        widget.hide()
        view.repaint()
    }

    /** Escape: nothing happens; a search puts the selection back. */
    fun cancel() {
        if (searchMode) view.g.cleanSelect(view.searchState.selectionBeforeSearch)
        close()
    }

    /** Enter. */
    fun execute() {
        val line = text.trim()
        if (line.isNotEmpty() && history.lastOrNull() != line) history.add(line)
        historyIndex = -1
        savedInput = ""
        when {
            line.startsWith("/") || line.startsWith("?") -> confirmSearch(line.drop(1), forward = line.startsWith("/"))
            line.startsWith(":") -> {
                close()
                val command = line.removePrefix(":").trim()
                val mapper = keys()
                if (command.isNotEmpty() && mapper != null) {
                    mapper.activeView = view
                    mapper.executeCommandLine(command)
                }
            }
            else -> close()
        }
    }

    /** Keep the matches for `n` / `N` and show them all (like `0` with them selected). */
    private fun confirmSearch(query: String, forward: Boolean) {
        val ss = view.searchState
        ss.query = query
        ss.forward = forward
        // `n` goes on to the first
        ss.cursor = -1
        ss.results = if (query.isEmpty()) emptyList() else matching(query).sortedWith(compareBy({ it.position.y }, { it.position.x }))
        close()
        if (ss.results.isNotEmpty()) {
            view.g.cleanSelect(ss.results.toSet())
            view.boundScreen()
        }
    }
}
