package graph_tools

import Edge
import Graph
import Node
import graph_tools.Plotter.TextPlotter.drawEdge
import graph_tools.Plotter.TextPlotter.drawNode
import graph_tools.Plotter.TextPlotter.recomputeBounds
import graph_tools.Plotter.TextPlotter.recomputeEdge
import ui.Utils.orElse
import utils.Constants.defaultBgColor
import utils.Constants
import utils.Vector2
import java.awt.*
import java.awt.geom.AffineTransform

object Plotter {
    var t: AffineTransform = AffineTransform()
    var fontSize: Double = 12.0
    var renderArrowheads: Boolean = true
    var renderOvals: Boolean = true
    var thinStroke: Stroke = BasicStroke(1.0f)
    var thickStroke: Stroke = BasicStroke(2.0f)

    fun correctedFontSize(): Double = (fontSize * t.scaleY).toDouble()

    fun setTransforms(g2d: Graphics2D, optimizationLevel: Int) {
        g2d.transform = t
        g2d.setFont(g2d.font.deriveFont(fontSize.toFloat()))
        g2d.setStroke ( BasicStroke(2.0f))
        renderOvals = optimizationLevel < 2
    }

    fun drawGraph(g2d: Graphics2D, g: Graph) {
        g2d.paint = defaultBgColor
        g2d.stroke = thinStroke
        g.edges.forEach { drawEdge(g2d, it) }
        g2d.stroke = thinStroke
        g.nodes.forEach {
            drawNode(g2d, it, g.selectedNodes.contains(it))
        }
    }

    fun drawSelectionBox(g2d: Graphics2D, ul: Vector2, br: Vector2) {
        g2d.paint = defaultBgColor
        g2d.drawRect(ul.x.toInt(), ul.y.toInt(), (br.x - ul.x).toInt(), (br.y - ul.y).toInt())
    }

    fun recomputeNodes(g2d: Graphics2D, nodes: Iterable<Node>) {
        val (fm, zoom) = when {
            t.scaleX >= 1.0 -> g2d.fontMetrics to 1.0
            else -> {
                // If node is recomputed at too small scale, the dimensions are wrong when we zoom in later.
                val zoom = 1.0
                val myTransform = AffineTransform(t).also { it.setToScale(zoom, zoom) }
                g2d.transform = myTransform
                val fm = g2d.getFontMetrics(g2d.font)
                g2d.transform = t
                fm to zoom
            }
        }

        nodes.forEach { recomputeBounds(it, fm, (1.0 / zoom).toDouble()) }

    }

    fun recomputeEdges(edges: Iterable<Edge>) {
        edges.forEach { recomputeEdge(it) }
    }

    object TextPlotter {

        fun recomputeBounds(n: Node, fm: FontMetrics, scale: Double = 1.0) {
            val lines = n.attributes.text.split("\n")

            val maxWidth = lines.map { fm.stringWidth(it) }.maxOrNull() ?: 0
            val perLineHeight = fm.height
            val textBounds = Vector2(maxWidth, lines.size * perLineHeight)
            val shapeBounds = n.cache.shape.computeShapeBounds(textBounds)

            n.cache.textBounds = textBounds * scale
            n.cache.shapeBounds = shapeBounds * scale
            n.cache.lines = lines
        }

        fun recomputeEdge(e: Edge) {
            val dir = (e.dst.position - e.src.position).toUnit()
            val cp1 = e.src.cache.shape.connectionPoint(dir, e.src, 0.0)
            val cp2 = e.dst.cache.shape.connectionPoint(dir, e.dst,Constants.arrowheadRadius)
            val src1 = e.src.position + cp1
            val src2 = e.src.position - cp1
            val dst1 = e.dst.position - cp2
            val dst2 = e.dst.position + cp2

            val (src, dst) = listOf(
                src1 to dst1,
                src2 to dst1,
                src1 to dst2,
            ).minBy { (it.first - it.second).lengthSquared() }

            e.cache.srcPt = src
            e.cache.dstPt = dst
        }

        fun drawEdge(g2d: Graphics2D, e: Edge) {
            val src = e.cache.srcPt
            val dst = e.cache.dstPt

            g2d.paint = Constants.defaultFgColor

            g2d.drawLine(src.x.toInt(), src.y.toInt(), dst.x.toInt(), dst.y.toInt())
            if (renderOvals) {
                val r = (Constants.arrowheadRadius).toInt()
                g2d.fillOval(
                    dst.x.toInt() - r,
                    dst.y.toInt() - r,
                    r*2,
                    r*2,
                )
            } else if (renderArrowheads) {
                val r = (Constants.arrowheadRadius).toInt()
                g2d.fillRect(
                    dst.x.toInt() - r,
                    dst.y.toInt() - r,
                    r*2,
                    r*2,
                )
            }
        }

        fun drawNode(g2d: Graphics2D, n: Node, selected: Boolean) {
            val bounds = n.cache
            val textPos = n.position - bounds.textBounds / 2;

            g2d.paint = n.attributes.bg.orElse(Constants.defaultBgColor)

            if(selected) {
                g2d.stroke = thickStroke
            }

            n.cache.shape.paint(g2d, n)

            g2d.stroke = thinStroke

            g2d.paint = n.attributes.fg.orElse(Constants.defaultFgColor)

            bounds.lines.withIndex().forEach { s ->
                g2d.drawString(
                    s.value,
                    (textPos.x).toFloat(),
                    (textPos.y + Constants.textRenderYOffset + g2d.fontMetrics.height * s.index).toFloat()
                )
            }

            g2d.paint = Constants.defaultFgColor
        }
    }
}