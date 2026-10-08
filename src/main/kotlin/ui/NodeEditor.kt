package ui

import utils.Constants
import client.sceneNode
import graph_tools.TextMeasurer
import utils.Utils.orElse
import utils.Vector2
import graph_tools.FontData
import utils.AwtUtils.toAwt
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

    /** Id of the node being edited. */
    var editedNode: String? = null

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



    /** Put the editor over the node, in its font and size; all it knows of the node is its [client.SceneNode]. */
    fun updatePosition(setCaretBy: Vector2? = null) {
        editedNode?.let { parent.model.sceneNode(it, TextMeasurer.default) }?.let { n ->
            val view = parent.viewTransform
            FontData.cache[n.fontSize to n.monospace]?.let { this.font = it.font }
            this.font = this.font.deriveFont((n.fontSize * view.scale).toFloat())

            val margin = ((n.shapeBounds - n.textBounds) / 4) * view.scale
            this.margin = Insets(margin.y.toInt(), margin.x.toInt(), margin.y.toInt(), margin.x.toInt())
            val center = view.toScreen(n.position)
            val textBounds = n.textBounds / 2 * view.scale

            val textUl = center - textBounds

            val ul = center - textBounds - margin + Vector2.Unit
            val br = center + textBounds + margin + Vector2.Unit
            parent.placeMeAt(this, ul, br)

            val text = n.lines.joinToString("\n")
            if (text == Constants.defaultNodeText) {
                this.select(0, text.length)
            } else {
                setCaretBy?.let { caret ->
                    val fm = this.getFontMetrics(this.font)
                    val lineIdx = ((caret.y - textUl.y) / fm.height).toInt().coerceIn(0, n.lines.size - 1)
                    val line = n.lines[lineIdx]
                    //compute specific caret position
                    (0..line.length - 1).find {
                        val s = line.substring(0, it + 1)
                        val substringLen = fm.getStringBounds(s, this.graphics).width
                        textUl.x + substringLen > caret.x
                    }
                        ?.let { caretIdx ->
                            val globalIndex = n.lines
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


    fun startNodeEdit(nodeId: String, clickScreenCoordinates: Vector2?) {
        parent.model.sceneNode(nodeId, TextMeasurer.default)?.let { n ->
            parent.model.textEditStarted(nodeId)
            // before editedNode: setting the text must not be reported as typing
            this.text = n.lines.joinToString("\n")
            editedNode = nodeId
            this.background = n.bg.orElse(Constants.defaultBgColor).toAwt()
            this.foreground = n.fg.orElse(Constants.defaultFgColor).toAwt()
            updatePosition(clickScreenCoordinates)
        }
    }

    // The widget only reports the text; the client puts it into the node and records the edit.

    fun updateNodeEdit() {
        if (editedNode != null) {
            parent.model.textTyped(this.text)
            updatePosition()
        }
    }

    fun endNodeEdit() {
        if (editedNode != null) {
            parent.model.textEditEnded(this.text)
            editedNode = null
        }
    }
}
