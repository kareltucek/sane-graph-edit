import Constants.connectionDotRadius
import Constants.defaultColor
import Constants.selectedColor
import Plotter.TextPlotter.drawEdge
import Plotter.TextPlotter.drawNode
import Plotter.TextPlotter.getBounds
import Plotter.TextPlotter.recomputeEdge
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.geom.AffineTransform

object Plotter {
    var t: AffineTransform = AffineTransform()
    var fontSize: Float = 12.0f
    var renderArrowheads: Boolean = true
    var renderOvals: Boolean = true

    fun correctedFontSize(): Float = (fontSize*t.scaleY).toFloat()

    fun setTransforms(g2d: Graphics2D, optimizationLevel: Int) {
        g2d.transform = t
        g2d.setFont(g2d.font.deriveFont(fontSize))
    }

    fun drawGraph(g2d: Graphics2D, g: Graph) {
        g2d.paint = defaultColor
        g.nodes.forEach { if (!g.selectedNodes.contains(it)) { drawNode(g2d, it) } }
        g.edges.forEach { drawEdge(g2d, it) }

        g2d.paint = selectedColor
        g.selectedNodes.forEach { drawNode(g2d, it) }
    }

    fun drawSelectionBox(g2d: Graphics2D, ul: Vector2, br: Vector2) {
        g2d.paint = defaultColor
        g2d.drawRect(ul.x.toInt(), ul.y.toInt(), (br.x - ul.x).toInt(), (br.y - ul.y).toInt())
    }

    fun recomputeNodes(g2d: Graphics2D, nodes: Iterable<Node>) {
        nodes.forEach { it.cachedBounds = getBounds(it, g2d.fontMetrics) }
    }

    fun recomputeEdges(edges: Iterable<Edge>) {
        edges.forEach { recomputeEdge(it) }
    }

    object TextPlotter {
        data class NodeBounds (val textBounds: Vector2, val shapeBounds: Vector2, val lines: List<String>)

        fun getBounds( n: Node, fm: FontMetrics ): TextPlotter.NodeBounds {
            val lines = n.text.split("\n")

            val maxWidth = lines.map { fm.stringWidth(it) }.maxOrNull() ?: 0
            val perLineHeight = fm.height
            val textBounds = Vector2(maxWidth, lines.size * perLineHeight)
            val shapeBounds = textBounds* Constants.shapeSizeCf + Vector2(Constants.shapeSizeMargin, Constants.shapeSizeMargin)*2

            return NodeBounds(
                textBounds = textBounds,
                shapeBounds = shapeBounds,
                lines = lines,
            )
        }

        fun connectionPoint(dir: Vector2, n: Node): Vector2 {
            val bounds = n.cachedBounds.shapeBounds/2
            val dir2 = (dir * Vector2(bounds.y, bounds.x)).toUnit()
            val max = Math.max(bounds.x, bounds.y)
            val xCf = bounds.x/max
            val yCf = bounds.y/max
            return Vector2(dir2.x * max * xCf, dir2.y * max * yCf)
        }

        fun recomputeEdge(e: Edge) {
            val dir = (e.dst.position - e.src.position).toUnit()
            val src1 = e.src.position + this.connectionPoint(dir, e.src)
            val src2 = e.src.position - this.connectionPoint(dir, e.src)
            val dst1 = e.dst.position - this.connectionPoint(dir, e.dst)
            val dst2 = e.dst.position + this.connectionPoint(dir, e.dst)

            val (src, dst) = listOf(
                src1 to dst1,
                src2 to dst1,
                src1 to dst2,
            ).minBy { (it.first - it.second).distanceSquare() }

            e.cache.srcPt = src
            e.cache.dstPt = dst
        }

        fun drawEdge(g2d: Graphics2D, e: Edge) {
            val src = e.cache.srcPt
            val dst = e.cache.dstPt

            g2d.drawLine( src.x.toInt(), src.y.toInt(), dst.x.toInt(), dst.y.toInt() )
            if (Plotter.renderOvals) {
                g2d.drawOval( dst.x.toInt() - connectionDotRadius / 2, dst.y.toInt() - connectionDotRadius / 2, connectionDotRadius, connectionDotRadius )
            } else if (Plotter.renderArrowheads){
                g2d.drawRect( dst.x.toInt() - connectionDotRadius / 2, dst.y.toInt() - connectionDotRadius / 2, connectionDotRadius, connectionDotRadius )
            }
        }

        fun drawNode(g2d: Graphics2D, n: Node) {
            val bounds = n.cachedBounds
            val textPos = n.position - bounds.textBounds/2;
            val shapePos = n.position - bounds.shapeBounds/2;

            bounds.lines.withIndex().forEach { s ->
                g2d.drawString(s.value, textPos.x, textPos.y + Constants.textRenderYOffset + g2d.fontMetrics.height*s.index)
            }
            if (Plotter.renderOvals) {
                g2d.drawOval(shapePos.x.toInt(), shapePos.y.toInt(), bounds.shapeBounds.x.toInt(), bounds.shapeBounds.y.toInt())
            } else {
                g2d.drawRect( shapePos.x.toInt(), shapePos.y.toInt(), bounds.shapeBounds.x.toInt(), bounds.shapeBounds.y.toInt() )
            }
            n.cachedBounds = bounds
        }
    }
}