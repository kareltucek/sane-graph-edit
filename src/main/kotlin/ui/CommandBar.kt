package ui

import client.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The `:` / `/` / `?` command bar at the bottom of the canvas.
 *
 * Opens when the user presses `:`, `/`, or `?` in canvas mode.
 * Text is typed into a [JTextField]; `<CR>` executes, `<Esc>`
 * dismisses. While open, keystrokes go to the text field and
 * are NOT fed to the [KeyMapper].
 *
 * For `/` and `?` (search mode), the bar provides **live
 * selection**: as the user types, matching nodes are selected
 * in real time. Enter confirms (stores results, zooms to fit),
 * Escape restores the pre-search selection.
 */
class CommandBar(
    private val graphView: GraphView,
) : JTextField() {

    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var savedInput = ""

    /** True when the bar is in search mode (/ or ?), not command mode (:). */
    private var searchMode = false

    init {
        isVisible = false
        // Tab completes instead of moving the focus
        focusTraversalKeysEnabled = false
        addKeyListener(object : KeyListener {
            override fun keyTyped(e: KeyEvent) {}

            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        execute()
                        e.consume()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        cancel()
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        historyUp()
                        e.consume()
                    }
                    KeyEvent.VK_DOWN -> {
                        historyDown()
                        e.consume()
                    }
                    KeyEvent.VK_TAB -> if (!searchMode) {
                        completeTab(backward = e.isShiftDown)
                        e.consume()
                    }
                    KeyEvent.VK_D -> if (!searchMode && e.isControlDown) {
                        listCompletions()
                        e.consume()
                    }
                }
            }

            override fun keyReleased(e: KeyEvent) {}
        })

        // Live search: update selection on every keystroke.
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent) = onTextChanged()
        })
    }

    private fun onTextChanged() {
        if (!searchMode || !isVisible) return
        val prefix = text.firstOrNull()?.toString() ?: return
        if (prefix != "/" && prefix != "?") return
        val query = text.removePrefix(prefix)
        liveSearch(query)
    }

    /**
     * Live-select all visible nodes matching [query]. Called on
     * every keystroke while the search bar is open.
     */
    private fun liveSearch(query: String) {
        val g = graphView.g
        if (query.isEmpty()) {
            // Empty query → restore pre-search selection.
            g.cleanSelect(graphView.searchState.selectionBeforeSearch)
            graphView.repaint()
            return
        }
        val matches = g.nodes
            .filter { g.isVisible(it) }
            .filter { SearchState.matches(it.attributes.text, query) }
        g.cleanSelect(matches.toSet())
        graphView.repaint()
    }

    // --- completion (Tab / Shift+Tab / Ctrl+D) ---

    /** Candidates being cycled by repeated Tabs, and where their word starts. */
    private var cycle: List<String> = emptyList()
    private var cycleIndex = 0
    private var cycleWordStart = 0

    /** The text after the last completion; Tab on unchanged text cycles. */
    private var lastCompleted: String? = null

    private fun completionFor(line: String): CommandCompletion.Result =
        CommandCompletion.complete(line, KeyMapper.BUILTIN_COMMANDS + CommandRegistry.list())

    /**
     * vim-like: a single match is completed (plus a space after a whole
     * word); several extend to their longest common prefix, then
     * repeated Tabs cycle through them (Shift+Tab backwards).
     */
    private fun completeTab(backward: Boolean) {
        val line = text.removePrefix(":")
        if (text == lastCompleted && cycle.isNotEmpty()) {
            cycleIndex = Math.floorMod(cycleIndex + if (backward) -1 else 1, cycle.size)
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

    private fun replaceWord(line: String, wordStart: Int, replacement: String) {
        text = ":" + line.substring(0, wordStart) + replacement
        caretPosition = text.length
    }

    /** Ctrl+D: show what Tab could complete to. */
    private fun listCompletions() {
        val r = completionFor(text.removePrefix(":"))
        graphView.messageArea?.show(
            if (r.candidates.isEmpty()) "(no completions)" else r.candidates.joinToString("\n")
        )
        showingCompletions = true
    }

    /** The message area holds a Ctrl+D list; closed with the bar. */
    private var showingCompletions = false

    // --- history ---

    private fun historyUp() {
        if (history.isEmpty()) return
        if (historyIndex == -1) {
            savedInput = text
            historyIndex = history.size - 1
        } else if (historyIndex > 0) {
            historyIndex--
        } else return
        text = history[historyIndex]
        caretPosition = text.length
    }

    private fun historyDown() {
        if (historyIndex == -1) return
        if (historyIndex < history.size - 1) {
            historyIndex++
            text = history[historyIndex]
        } else {
            historyIndex = -1
            text = savedInput
        }
        caretPosition = text.length
    }

    // --- open / close ---

    /**
     * Open the bar with a prefix (`:`, `/`, `?`).
     */
    fun open(prefix: String = ":") {
        searchMode = prefix == "/" || prefix == "?"
        if (searchMode) {
            // Capture current selection so Escape can restore it.
            graphView.searchState.selectionBeforeSearch =
                graphView.g.selectedNodes.toSet()
            graphView.searchState.forward = (prefix == "/")
        }
        text = prefix
        historyIndex = -1
        savedInput = ""
        isVisible = true
        requestFocusInWindow()
        // Position at bottom of GraphView
        graphView.placeMeAt(
            this,
            utils.Vector2(0.0, (graphView.height - 25).toDouble()),
            utils.Vector2(graphView.width.toDouble(), graphView.height.toDouble()),
        )
        graphView.validate()
        graphView.repaint()
    }

    /** Close the bar (shared by execute and cancel). */
    private fun closeBar() {
        if (showingCompletions) graphView.messageArea?.close()
        showingCompletions = false
        cycle = emptyList()
        lastCompleted = null
        text = ""
        searchMode = false
        isVisible = false
        graphView.graphCanvas.requestFocusInWindow()
        graphView.repaint()
    }

    /** Escape: dismiss without confirming. Restore pre-search selection in search mode. */
    private fun cancel() {
        if (searchMode) {
            graphView.g.cleanSelect(graphView.searchState.selectionBeforeSearch)
        }
        closeBar()
    }

    /** Enter: confirm. */
    private fun execute() {
        val line = text.trim()
        if (line.isNotEmpty() && (history.isEmpty() || history.last() != line)) {
            history.add(line)
        }
        historyIndex = -1
        savedInput = ""

        if (line.isEmpty()) {
            closeBar()
            return
        }

        when {
            line.startsWith("/") || line.startsWith("?") -> {
                val query = line.drop(1)
                confirmSearch(query, forward = line.startsWith("/"))
            }
            line.startsWith(":") -> {
                closeBar()
                val cmd = line.removePrefix(":").trim()
                if (cmd.isNotEmpty()) {
                    val mapper = graphView.keyMapper ?: return
                    mapper.activeView = graphView
                    mapper.executeCommandLine(cmd)
                }
            }
            else -> closeBar()
        }
    }

    /**
     * Confirm a search: store results in [SearchState], zoom to
     * fit all matches.
     */
    private fun confirmSearch(query: String, forward: Boolean) {
        val ss = graphView.searchState
        ss.query = query
        ss.forward = forward
        ss.cursor = -1  // n will advance to 0

        if (query.isEmpty()) {
            ss.results = emptyList()
            closeBar()
            return
        }

        ss.results = graphView.g.nodes
            .filter { graphView.g.isVisible(it) }
            .filter { SearchState.matches(it.attributes.text, query) }
            .sortedWith(compareBy({ it.position.y }, { it.position.x }))

        closeBar()

        // Zoom to fit all matches (like pressing 0 with them selected).
        if (ss.results.isNotEmpty()) {
            graphView.g.cleanSelect(ss.results.toSet())
            graphView.boundScreen()
        }
    }
}
