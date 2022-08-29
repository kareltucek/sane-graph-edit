import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.JPanel

class GraphComponent(
    val parent: ShapesEx,
    val te: NodeEditor
) : JPanel() {
    var lastCursorPosition: Vector2 = Vector2.Zero
    var initialized: Boolean = false
    val mouseListener = GraphMouseListener(this)
    val keyListener = GraphKeyListener(this)
    var avgDrawTime: Long = 0
    var optimizeLevel: Int = 0

    init {
        this.addMouseListener(mouseListener)
        this.addMouseMotionListener(mouseListener)
        this.addMouseWheelListener(mouseListener)
        this.addKeyListener(keyListener)
        te.addMouseWheelListener(mouseListener)
        this.isFocusable = true
        this.requestFocus()
        this.minimumSize = Dimension(500, 500)
        this.size = Dimension(1000, 1000)

    }

    fun requestGlobalRepaint() {
        parent.repaint()
    }

    fun startNodeEdit(n: Node, evt: MouseEvent?) {
        te.startNodeEdit(n, evt)
    }

    fun endNodeEdit() {
        te.endNodeEdit()
    }

    private fun doDrawing(g: Graphics) {
        val g2d = g as Graphics2D

        val watchStart = Instant.now()

        if (!initialized && this.width != 0 && this.height != 0) {
            initialized = true
            println("width ${this.width} ${this.height}")
            Plotter.t.translate(this.width.toDouble() / 2, this.height.toDouble() / 2)
            this.requestFocus()
        }

        if (optimizeLevel < 1) {
            val rh = RenderingHints(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            )
            rh[RenderingHints.KEY_RENDERING] = RenderingHints.VALUE_RENDER_SPEED
            g2d.setRenderingHints(rh)
        }

        g2d.drawRect(1, 1, this.width - 2, this.height - 2)

        Plotter.setTransforms(g2d, optimizeLevel)

        Graph.g.recompute(g2d)

        Plotter.drawGraph(g2d, Graph.g)

        mouseListener.selectionBoxFrom?.let { box ->
            val (ul, br) = Vector2.computeCorners(mouseListener.lastPosition, box)
            Plotter.drawSelectionBox(g2d, ul, br)
        }

        val watchEnd = Instant.now()

        avgDrawTime = (avgDrawTime*63 + (watchEnd.toEpochMilli() - watchStart.toEpochMilli()))/64
        if (avgDrawTime > Constants.optimizeAt) {
            optimizeLevel = optimizeLevel+1
            avgDrawTime = 0
        }
    }


    public override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        doDrawing(g)
    }
}