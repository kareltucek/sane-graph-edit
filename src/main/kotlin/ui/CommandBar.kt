package ui

import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JTextField

/**
 * The `:` / `/` / `?` command bar at the bottom of the canvas.
 *
 * Opens when the user presses `:`, `/`, or `?` in canvas mode.
 * Text is typed into a [JTextField]; `<CR>` executes, `<Esc>`
 * dismisses. While open, keystrokes go to the text field and
 * are NOT fed to the [KeyMapper].
 *
 * The bar is a child of [GraphView] placed at the bottom edge
 * via the existing SpringLayout.
 */
class CommandBar(
    private val graphView: GraphView,
) : JTextField() {

    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var savedInput = ""

    init {
        isVisible = false
        addKeyListener(object : KeyListener {
            override fun keyTyped(e: KeyEvent) {}

            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        execute()
                        e.consume()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        close()
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
                }
            }

            override fun keyReleased(e: KeyEvent) {}
        })
    }

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

    /**
     * Open the bar with an optional prefix (`:`, `/`, `?`).
     * The prefix is shown in the text field so the user sees
     * what mode they're in.
     */
    fun open(prefix: String = ":") {
        text = prefix
        historyIndex = -1
        savedInput = ""
        isVisible = true
        requestFocusInWindow()
        // Position at bottom of GraphView
        val gv = graphView
        graphView.placeMeAt(
            this,
            utils.Vector2(0.0, (gv.height - 25).toDouble()),
            utils.Vector2(gv.width.toDouble(), gv.height.toDouble()),
        )
        graphView.validate()
        graphView.repaint()
    }

    fun close() {
        text = ""
        isVisible = false
        graphView.graphCanvas.requestFocusInWindow()
        graphView.repaint()
    }

    private fun execute() {
        val line = text.trim()
        if (line.isNotEmpty() && (history.isEmpty() || history.last() != line)) {
            history.add(line)
        }
        historyIndex = -1
        savedInput = ""
        close()

        if (line.isEmpty()) return

        val mapper = graphView.keyMapper ?: return
        mapper.activeView = graphView

        when {
            line.startsWith(":") -> {
                val cmd = line.removePrefix(":").trim()
                if (cmd.isNotEmpty()) {
                    mapper.executeCommandLine(cmd)
                }
            }
            line.startsWith("/") -> {
                // TODO: search forward
                val query = line.removePrefix("/")
                System.err.println("sane-graph-edit: search not yet implemented (query: $query)")
            }
            line.startsWith("?") -> {
                // TODO: search backward
                val query = line.removePrefix("?")
                System.err.println("sane-graph-edit: search not yet implemented (query: $query)")
            }
        }
    }
}
