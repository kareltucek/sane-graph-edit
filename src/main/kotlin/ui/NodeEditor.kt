package ui

import utils.Constants
import Node
import graph_tools.Plotter
import utils.Vector2
import ui.Utils.toScreenVector
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
            when (e.keyCode) {
                KeyEvent.VK_ESCAPE -> endNodeEdit()
            }
        }

        override fun keyReleased(e: KeyEvent) {}
    }

    var editedNode: Node? = null

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
            val correctedFontSize = Plotter.correctedFontSize()
            this.setFont(this.font.deriveFont(correctedFontSize.toFloat()))

            val bounds = Plotter.TextPlotter.getBounds(n, this.getFontMetrics(this.font))

            val margin = (4* Plotter.t.scaleX).toInt()
            this.margin = Insets(margin, margin,margin,margin)
            val center = n.position.toScreenVector() // + utils.Vector2(utils.Constants.nodeEditorXMargin, 0.0)
            val textBounds = bounds.textBounds + Vector2(2 * margin, 2 * margin);

            val ul = center - textBounds / 2
            val br = center + textBounds / 2
            parent.placeMeAt(this, ul, br)

            if (n.attributes.text == Constants.defaultNodeText) {
                //set
                this.select(0, n.attributes.text.length)
            } else {
                setCaretBy?.let { caret ->
                    val fm = this.getFontMetrics(this.font)
                    val lineIdx = ((caret.y - ul.y) / fm.height).toInt()
                    val line = n.cache.lines[lineIdx.coerceIn(0, n.cache.lines.size - 1)]
                    //compute specific caret position
                    (0..line.length - 2).find {
                        val s = line.substring(0, it + 1)
                        val substringLen = fm.getStringBounds(s, this.graphics).width
                        ul.x + substringLen > caret.x
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
        this.text = editedNode!!.attributes.text
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
        if (editedNode != null) {
            editedNode!!.attributes.text = this.text
            parent.repaint()
            editedNode = null
        }
    }
}