package graph_tools

import Graph
import graph_tools.Plotter.TextPlotter.drawEdge
import graph_tools.Plotter.TextPlotter.drawNode
import graph_tools.Plotter.TextPlotter.recomputeBounds
import graph_tools.Plotter.TextPlotter.recomputeEdge
import utils.Utils.orElse
import utils.Utils.withIdentityTransform
import utils.Constants.defaultBgColor
import utils.Constants
import utils.Vector2
import java.awt.*
import java.awt.geom.AffineTransform
import java.lang.Math.pow

object Plotter {
    var t: AffineTransform = AffineTransform()
    var defaultFontSize: Double = 12.0
    var renderArrowheads: Boolean = true
    var renderOvals: Boolean = true
    var thinStroke: BasicStroke = BasicStroke(1.0f)
    var thickStroke: BasicStroke = BasicStroke(2.0f)

    fun fontScale(n: Node): Double = pow(Constants.fontSizeZoomCoef, n.attributes.nodeScale.orElse(0.0))
    fun workspaceFontSize(n: Node): Double = (fontScale(n) * defaultFontSize)
    fun screenspaceFontSize(n: Node): Double = ((fontScale(n) * defaultFontSize) * t.scaleX)

    fun setTransforms(g2d: Graphics2D, optimizationLevel: Int) {
        g2d.transform = t
        g2d.setFont(g2d.font.deriveFont(defaultFontSize.toFloat()))
        g2d.setStroke(BasicStroke(2.0f))
        renderOvals = optimizationLevel < 3
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
        g2d.paint = Constants.defaultFgColor
        g2d.drawRect(ul.x.toInt(), ul.y.toInt(), (br.x - ul.x).toInt(), (br.y - ul.y).toInt())
    }


    fun recomputeNodes(g2d: Graphics2D, nodes: Iterable<Node>) = g2d.withIdentityTransform {
        nodes.forEach { n ->
            val fs = workspaceFontSize(n)
            val fd = FontData.get(g2d, fs)
            n.cache.font = fd
            val fm = g2d.getFontMetrics(fd.font)
            recomputeBounds(n, fm)
        }
    }

    fun recomputeEdges(edges: Iterable<Edge>) {
        edges.forEach { recomputeEdge(it) }
    }

    object TextPlotter {

        fun recomputeBounds(n: Node, fm: FontMetrics) {
            val lines = n.attributes.text.split("\n")

            val maxWidth = lines.map { fm.stringWidth(it) }.maxOrNull() ?: 0
            val perLineHeight = fm.height
            val textBounds = Vector2(maxWidth, lines.size * perLineHeight)
            val shapeBounds = n.cache.shape.computeShapeBounds(textBounds)

            n.cache.textBounds = textBounds
            n.cache.shapeBounds = shapeBounds
            n.cache.lines = lines
        }

        fun recomputeEdge(e: Edge) {
            val dir = (e.dst.position - e.src.position).toUnit()
            val cp1 = e.src.cache.shape.connectionPoint(dir, e.src, 0.0)
            val cp2 = e.dst.cache.shape.connectionPoint(dir, e.dst, Constants.arrowheadRadius)
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
                    r * 2,
                    r * 2,
                )
            } else if (renderArrowheads) {
                val r = (Constants.arrowheadRadius).toInt() - 1
                g2d.fillRect(
                    dst.x.toInt() - r,
                    dst.y.toInt() - r,
                    r * 2,
                    r * 2,
                )
            }
        }

        fun drawNode(g2d: Graphics2D, n: Node, selected: Boolean) {
            val bounds = n.cache
            val textPos = n.position - bounds.textBounds / 2;

            g2d.paint = n.attributes.bg.orElse(Constants.defaultBgColor)

            if (selected) {
                if (thickStroke.lineWidth * t.scaleX < 1.0) {
                    g2d.stroke = BasicStroke((thickStroke.lineWidth / t.scaleX * 1.1).toFloat())
                } else {
                    g2d.stroke = thickStroke
                }
            }

            n.cache.shape.paint(g2d, n)

            g2d.stroke = thinStroke

            g2d.paint = n.attributes.fg.orElse(Constants.defaultFgColor)

            val screenspaceFD = FontData.get(g2d, screenspaceFontSize(n))

            /**
             * For small fonts (w.r.t. how it is shown on screen), output height is messed up because of integral height.
             *
             * This follows the text component rendering. Fixing it would mess up text-editing vs read-only node correspondence.
             *
             * We would have to render all nodes as UI components, which would (I fear) strongly affect performance.
             */

            n.cache.font
                ?.takeIf { screenspaceFD.scale >= 5.0 } //reduce rendering time on zoomed-out graphs...
                ?.let { workspaceFD ->
                    g2d.font = workspaceFD.font

                    bounds.lines.withIndex().forEach { s ->
                        g2d.drawString(
                            s.value,
                            (textPos.x).toFloat(),
                            (textPos.y + screenspaceFD.ascent / Plotter.t.scaleX + screenspaceFD.height / Plotter.t.scaleX * s.index.toDouble()).toFloat()
                        )
                    }
                }

            g2d.paint = Constants.defaultFgColor
        }
    }
}

data class FontData(
    val scale: Double,
    val ascent: Double,
    val height: Double,
    val font: Font,
) {

    companion object {
        val cache: MutableMap<Double, FontData> = mutableMapOf()

        fun get(g2d: Graphics2D, fs: Double): FontData {
            val fd = FontData.cache[fs].orElse {
                g2d.withIdentityTransform {
                    val mf = g2d.font.deriveFont(fs.toFloat())
                    val mfm = g2d.getFontMetrics(mf)
                    if (mfm.height == 0) {
                        val dbg = 7
                    }
                    val fontData = FontData(
                        font = mf,
                        ascent = mfm.ascent.toDouble(),
                        height = mfm.height.toDouble(),
                        scale = fs
                    )
                    FontData.cache[fs] = fontData
                    fontData
                }
            }
            return fd
        }
    }
}