package ui

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Read-only scrollable text area docked to the bottom of
 * [GraphView] for vim-style command output (`:help`, `:map` with
 * no args, error messages, etc.).
 *
 * Styled to look like an extension of [CommandBar]: same
 * monospace font, same bottom anchor, shows when summoned,
 * hides on Escape or any key in its focus.
 */
class MessageArea(
    private val graphView: GraphView,
) : JScrollPane() {

    private val area: JTextArea = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        isEditable = false
        background = Color(0xF8F8F8)
        border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
    }

    init {
        setViewportView(area)
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY)
        isVisible = false
        area.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE ||
                    e.keyCode == KeyEvent.VK_ENTER ||
                    e.keyCode == KeyEvent.VK_Q) {
                    close()
                    e.consume()
                }
            }

            override fun keyTyped(e: KeyEvent) {
                // `:` closes the pane and drops into the command
                // bar — same feel as vim's more pager.
                if (e.keyChar == ':') {
                    close()
                    graphView.commandBar?.open(":")
                    e.consume()
                } else if (e.keyChar == '/' || e.keyChar == '?') {
                    close()
                    graphView.commandBar?.open(e.keyChar.toString())
                    e.consume()
                }
            }
        })
    }

    fun show(text: String) {
        area.text = text
        area.caretPosition = 0
        isVisible = true

        // Dock to the bottom third of the GraphView.
        val h = (graphView.height * 0.4).toInt().coerceAtLeast(150)
        val y = graphView.height - h
        graphView.placeMeAt(
            this,
            utils.Vector2(0.0, y.toDouble()),
            utils.Vector2(graphView.width.toDouble(), graphView.height.toDouble()),
        )
        graphView.validate()
        area.requestFocusInWindow()
        graphView.repaint()
    }

    fun close() {
        isVisible = false
        graphView.graphCanvas.requestFocusInWindow()
        graphView.repaint()
    }
}
