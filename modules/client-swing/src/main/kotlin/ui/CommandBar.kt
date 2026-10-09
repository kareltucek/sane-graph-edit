package ui

import client.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The `:` / `/` / `?` command bar at the bottom of the canvas: the
 * text field of the view's [CommandLine], which says what the text
 * typed here does.
 *
 * Opens when the user presses `:`, `/`, or `?` in canvas mode.
 * While open, keystrokes go to the text field and are NOT fed to
 * the [KeyMapper].
 */
class CommandBar(
    private val graphView: GraphView,
) : JTextField() {

    // the widget is defined below
    private val line: CommandLine by lazy { CommandLine(graphView.model, widget) { graphView.keyMapper } }

    init {
        isVisible = false
        // Tab completes instead of moving the focus
        focusTraversalKeysEnabled = false
        addKeyListener(object : KeyListener {
            override fun keyTyped(e: KeyEvent) {}

            override fun keyPressed(e: KeyEvent) {
                val action: (() -> Unit)? = when (e.keyCode) {
                    KeyEvent.VK_ENTER -> line::execute
                    KeyEvent.VK_ESCAPE -> line::cancel
                    KeyEvent.VK_UP -> line::historyUp
                    KeyEvent.VK_DOWN -> line::historyDown
                    KeyEvent.VK_TAB -> ({ line.complete(backward = e.isShiftDown) })
                    KeyEvent.VK_D -> if (e.isControlDown) line::listCompletions else null
                    else -> null
                }
                action?.let {
                    it()
                    e.consume()
                }
            }

            override fun keyReleased(e: KeyEvent) {}
        })

        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = line.typed(text)
            override fun removeUpdate(e: DocumentEvent) = line.typed(text)
            override fun changedUpdate(e: DocumentEvent) = line.typed(text)
        })
    }

    /** Open the bar with a prefix (`:`, `/`, `?`). */
    fun open(prefix: String = ":") = line.open(prefix)

    // not this component itself: it has a show and a hide of its own
    private val widget = object : CommandLineWidget {
        override fun show(text: String) {
            this@CommandBar.text = text
            caretPosition = text.length
            if (!isVisible) {
                isVisible = true
                requestFocusInWindow()
                // at the bottom of the view
                graphView.placeMeAt(
                    this@CommandBar,
                    utils.Vector2(0.0, (graphView.height - 25).toDouble()),
                    utils.Vector2(graphView.width.toDouble(), graphView.height.toDouble()),
                )
                graphView.validate()
            }
            graphView.repaint()
        }

        override fun hide() {
            text = ""
            isVisible = false
            graphView.graphCanvas.requestFocusInWindow()
            graphView.repaint()
        }

        override fun showList(text: String) {
            graphView.messageArea?.show(text)
        }

        override fun hideList() {
            graphView.messageArea?.close()
        }
    }
}
