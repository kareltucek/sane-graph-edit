package ui

import utils.Constants
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


        if (!initialized && this.width != 0 && this.height != 0) {
            initialized = true
            println("width ${this.width} ${this.height}")
            // Only center on first paint when the transform is still
            // at identity — i.e. a fresh tab. If Session restored a
            // saved pan/zoom for this tab, `Plotter.t` is already
            // non-identity and we must not clobber it.
            if (Plotter.t.isIdentity) {
                Plotter.t.translate(this.width.toDouble() / 2, this.height.toDouble() / 2)
            }
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
            Plotter.setTransforms(g2d, optimizeLevel)

            withPerformanceCheck("Recomputation", 5.0, onIssue = { parent.g.printStats()}) {
                 parent.g.recompute(g2d)
            }

            Plotter.drawGraph(g2d,  parent.g)

            parent.mouseListener.controller.selectionBoxFrom?.let { box ->
                val (ul, br) = Vector2.computeCorners(parent.mouseListener.controller.lastPosition, box)
                Plotter.drawSelectionBox(g2d, ul, br)
            }
        }
    }

    public override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        doDrawing(g)
    }
}