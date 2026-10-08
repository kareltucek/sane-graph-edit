package ui

import utils.Constants
import client.scene
import graph_tools.AwtTextMeasurer
import graph_tools.Plotter
import utils.Utils.PerformanceData.withPerformanceCheck
import utils.Vector2
import java.awt.*
import javax.swing.JPanel

class GraphCanvas(
    val parent: GraphView,
) : JPanel() {
    var initialized: Boolean = false
    var optimizeLevel: Int = 0

    init {
        this.isFocusable = true
        // Swing's default Tab / Shift-Tab focus traversal keys eat
        // the events before KeyListener sees them — so `:map <Tab>
        // …` never fires. We don't use focus traversal on the
        // canvas (single focus target), so turn it off.
        this.focusTraversalKeysEnabled = false
        // prevents artifact caused by overlay windows, such as oneko
        this.isDoubleBuffered = true
        this.requestFocus()
        //this.minimumSize = Dimension(500, 500)
        //this.size = Dimension(1000, 1000)
        //this.background = Color(255, 255, 255)
        validate()
        repaint()
    }

    fun doDrawing(graphics: Graphics) {
        val g2d = graphics as Graphics2D
        parent.syncSize()


        if (!initialized && this.width != 0 && this.height != 0) {
            initialized = true
            println("width ${this.width} ${this.height}")
            // Only center on first paint when the transform is still
            // at identity — i.e. a fresh tab. If Session restored a
            // saved pan/zoom for this tab, `Plotter.t` is already
            // non-identity and we must not clobber it.
            if (parent.viewTransform.isIdentity) {
                parent.viewTransform.translate(Vector2(this.width, this.height) / 2)
            }
            // a link into this new tab asked to focus a node; only now is the size known
            parent.content.pendingFocus?.let { parent.content.pendingFocus = null; parent.content.focusNode(it) }
            this.requestFocus()
        }

        val panning = this.parent.mouseListener.controller.state != null

        if (optimizeLevel < 1 || (optimizeLevel < 2 && !panning)) {
            val rh = RenderingHints(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            )
            rh[RenderingHints.KEY_RENDERING] = RenderingHints.VALUE_RENDER_SPEED
            g2d.setRenderingHints(rh)
        }

        withPerformanceCheck("GraphViewOnDraw", (50 + optimizeLevel*50.0), 0.01, {optimizeLevel++}) {

            g2d.drawRect(1, 1, this.width - 2, this.height - 2)

            // Push the current canvas dimensions so the edge-fade
            // logic in Plotter.drawEdge can compare edge length
            // against the window size for each paint frame.
            Plotter.screenDimensions = Vector2(this.width.toDouble(), this.height.toDouble())
            Plotter.setTransforms(g2d, parent.viewTransform, optimizeLevel)

            // the client says what to show; measuring its text is this renderer's part
            Plotter.drawScene(g2d, parent.model.scene(AwtTextMeasurer(g2d)))
            // the link letters shown there follow the selection, which changes in many places
            parent.refreshStatus()
        }
    }

    public override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        doDrawing(g)
    }
}