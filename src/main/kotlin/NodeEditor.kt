import Utils.toScreenVector
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent
import javax.swing.JLayeredPane
import javax.swing.JTextArea
import javax.swing.SpringLayout
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener


class NodeEditor(
    val parent: ShapesEx,
    val lyt: SpringLayout,
    val pnl: JLayeredPane,
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

    fun setTextFieldVisible(visible: Boolean) {
        this.isVisible = visible
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g as Graphics2D
        //g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        super.paintComponent(g2d)
        g2d.dispose()
    }

    fun setTextFieldPosition(setCaretBy: MouseEvent? = null) {
        editedNode?.let { n ->
            val correctedFontSize = Plotter.correctedFontSize()
            this.setFont(this.font.deriveFont(correctedFontSize))

            val bounds = Plotter.TextPlotter.getBounds(n, this.getFontMetrics(this.font))

            val margin = (4*Plotter.t.scaleX).toInt()
            this.margin = Insets(margin, margin,margin,margin)
            val center = n.position.toScreenVector() // + Vector2(Constants.nodeEditorXMargin, 0.0f)
            val textBounds = bounds.textBounds + Vector2(2*margin, 2*margin);


            val ul = center - textBounds / 2
            val br = center + textBounds / 2
            //pnl.remove(this)
            //pnl.add(this)
            lyt.putConstraint(SpringLayout.WEST, this, ul.x.toInt() + Constants.frameMargin, SpringLayout.WEST, pnl);
            lyt.putConstraint(SpringLayout.NORTH, this, ul.y.toInt() + Constants.frameMargin, SpringLayout.NORTH, pnl);
            lyt.putConstraint(SpringLayout.EAST, this, br.x.toInt() + Constants.frameMargin, SpringLayout.WEST, pnl);
            lyt.putConstraint(SpringLayout.SOUTH, this, br.y.toInt() + Constants.frameMargin, SpringLayout.NORTH, pnl);

            if (n.text == Constants.defaultNodeText) {
                //set
                this.select(0, n.text.length)
            } else {
                setCaretBy?.let { evt ->
                    val fm = this.getFontMetrics(this.font)
                    val lineIdx = ((evt.y - ul.y) / fm.height).toInt()
                    val line = n.cachedBounds.lines[lineIdx.coerceIn(0, n.cachedBounds.lines.size - 1)]
                    //compute specific caret position
                    (0..line.length - 2).find {
                        val s = line.substring(0, it + 1)
                        val substringLen = fm.getStringBounds(s, this.graphics).width
                        ul.x + substringLen > evt.x
                    }
                        ?.let { caretIdx ->
                            val globalIndex = n.cachedBounds.lines
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

    fun startNodeEdit(n: Node, evt: MouseEvent?) {
        endNodeEdit()
        editedNode = n
        this.text = editedNode!!.text

        setTextFieldVisible(true)
        setTextFieldPosition(evt)
        this.requestFocus()
        parent.repaint()
    }

    fun updateNodeEdit() {
        if (editedNode != null) {
            editedNode!!.text = this.text
            Graph.g.needsRecomputing(editedNode!!)
            setTextFieldPosition()
            parent.repaint()
        }
    }

    fun endNodeEdit() {
        if (editedNode != null) {
            editedNode!!.text = parent.te.text
            parent.setTextFieldVisible(false)
            parent.repaint()
            editedNode = null
        }
    }
}