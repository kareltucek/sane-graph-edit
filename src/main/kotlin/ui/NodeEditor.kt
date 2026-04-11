package ui

import utils.Constants
import graph_tools.EditTextCommand
import graph_tools.Node
import graph_tools.Plotter
import utils.Utils.orElse
import utils.Vector2
import utils.Utils.toScreenVector
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener


class NodeEditor(
    val parent: GraphView,
) : JTextArea() {
    var dl: DocumentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) {
            updateNodeEdit()
        }

        override fun removeUpdate(e: DocumentEvent?) {
            updateNodeEdit()
        }

        override fun changedUpdate(e: DocumentEvent?) {
            updateNodeEdit()
        }
    }

    var kl: KeyListener = object : KeyListener {
        override fun keyTyped(e: KeyEvent) { }

        override fun keyPressed(e: KeyEvent) {
            val used: Unit? = when (e.keyCode) {
                KeyEvent.VK_ESCAPE -> {
                    parent.endNodeEdit()
                }

                else -> null
            }
            used?.let { e.consume() }
        }

        override fun keyReleased(e: KeyEvent) {}
    }

    var editedNode: Node? = null

    /**
     * Original text of [editedNode] at the moment editing began. Captured
     * so that [endNodeEdit] can push an [EditTextCommand] holding the
     * full before/after pair — keeps label typing under a single undo
     * step rather than one per character.
     */
    private var editStartText: String? = null

    init {
        this.document.addDocumentListener(dl)
        this.addKeyListener(kl)
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g as Graphics2D
        //g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        super.paintComponent(g2d)
        g2d.dispose()
    }



    fun updatePosition(setCaretBy: Vector2? = null) {
        editedNode?.let { n ->
            val correctedFontSize = Plotter.screenspaceFontSize(n)

            n.cache.font?.let { fd ->
                this.font = fd.font
                // we have edited the text, so we should recompute the data at 1.0 zoom...
                Plotter.TextPlotter.recomputeBounds(n, this.getFontMetrics(this.font))
            }
            this.font = this.font.deriveFont(correctedFontSize.toFloat())

            val margin = ((n.cache.shapeBounds - n.cache.textBounds)/4)*Plotter.t.scaleX
            this.margin = Insets(margin.y.toInt(), margin.x.toInt(),margin.y.toInt(),margin.x.toInt())
            val center = (n.position).toScreenVector()
            val textBounds = n.cache.textBounds/2*Plotter.t.scaleX

            val textUl = center - textBounds

            val ul = center - textBounds - margin + Vector2.Unit
            val br = center + textBounds + margin + Vector2.Unit
            parent.placeMeAt(this, ul, br)

            if (n.attributes.text == Constants.defaultNodeText) {
                //set
                this.select(0, n.attributes.text.length)
            } else {
                setCaretBy?.let { caret ->
                    val fm = this.getFontMetrics(this.font)
                    val lineIdx = ((caret.y - textUl.y) / fm.height).toInt().coerceIn(0, n.cache.lines.size - 1)
                    val line = n.cache.lines[lineIdx]
                    //compute specific caret position
                    (0..line.length - 1).find {
                        val s = line.substring(0, it + 1)
                        val substringLen = fm.getStringBounds(s, this.graphics).width
                        textUl.x + substringLen > caret.x
                    }
                        ?.let { caretIdx ->
                            val globalIndex = n.cache.lines
                                .take(lineIdx)
                                .sumOf { it.length + 1 }
                                .let { it + caretIdx }
                            this.caret.dot = globalIndex
                        }
                }

            }

            parent.validate()
            parent.repaint()
        }
    }


    fun startNodeEdit(n: Node, clickScreenCoordinates: Vector2?) {
        editedNode = n
        editStartText = n.attributes.text
        this.text = editedNode!!.attributes.text
        this.background = n.attributes.bg.orElse(Constants.defaultBgColor)
        this.foreground = n.attributes.fg.orElse(Constants.defaultFgColor)
        updatePosition(clickScreenCoordinates)
    }

    fun updateNodeEdit() {
        if (editedNode != null) {
            editedNode!!.attributes.text = this.text
            parent.g.needsRecomputing(editedNode!!)
            updatePosition()
        }
    }

    fun endNodeEdit() {
        val n = editedNode ?: return
        // `updateNodeEdit` kept the node text in sync with the editor
        // while the user was typing, so the live text is already this.text.
        // Snap back to the captured "before" so the history command's
        // redo() can re-apply the final text cleanly and undo() can
        // restore the original — avoids a "ghost" mutation that's not
        // on the stack.
        val finalText = this.text
        val startText = editStartText
        if (startText != null && startText != finalText) {
            n.attributes.text = startText
            parent.g.commit(EditTextCommand(parent.g, n, startText, finalText))
        } else {
            // No-op edit (opened, closed unchanged). Clear out any
            // partial in-place updates made while moving the caret.
            n.attributes.text = startText ?: finalText
        }
        parent.g.needsRecomputing(n)
        editedNode = null
        editStartText = null
    }
}