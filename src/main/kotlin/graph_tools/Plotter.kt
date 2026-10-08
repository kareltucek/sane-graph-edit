package graph_tools

import Graph
import graph_tools.Plotter.TextPlotter.drawEdge
import graph_tools.Plotter.TextPlotter.drawNode
import utils.AwtUtils.toAwt
import utils.AwtUtils.withIdentityTransform
import utils.Utils.orElse
import utils.Constants.defaultBgColor
import utils.Constants
import utils.Vector2
import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

/**
 * Read-only renderer that walks a [Graph] and paints it onto a
 * [Graphics2D]. Owns the workspace→screen transform [t] and the font
 * measurement cache ([FontData.cache]).
 *
 * Note — global state: [t] is a singleton. That's fine for the single
 * currently-open graph, but once tabs land this needs to move onto
 * `GraphView` or be explicitly saved/restored on tab switch. See
 * `tasks/tabs.md`.
 *
 * Note — perf: the `optimizationLevel` argument to [setTransforms] comes
 * from `GraphCanvas.optimizeLevel`, which bumps itself up whenever a paint
 * frame exceeds a time budget. Higher levels drop antialiasing, then
 * arrowheads, then text at zoomed-out scales.
 */
object Plotter {
    /**
     * Workspace → screen affine transform. Mutated directly by the input
     * layer when the user pans (translate) or zooms (scale). Every
     * rendering path starts by copying this into the current `Graphics2D`.
     */
    var t: AffineTransform = AffineTransform()
    val defaultFontSize: Double get() = Constants.defaultFontSize
    var renderArrowheads: Boolean = true
    var renderOvals: Boolean = true
    var thinStroke: BasicStroke = BasicStroke(1.0f)
    var thickStroke: BasicStroke = BasicStroke(2.0f)
    /**
     * Canvas dimensions (width, height) in screen pixels. Set
     * once per paint frame by [ui.GraphCanvas] before [drawGraph]
     * runs. Used by [TextPlotter.drawEdge] to fade long edges
     * that cut across the canvas so they don't obscure the
     * graph underneath.
     */
    var screenDimensions: Vector2 = Vector2(800.0, 600.0)

    fun workspaceFontSize(n: Node): Double = NodeText.fontSize(n)
    fun screenspaceFontSize(n: Node): Double = NodeText.fontSize(n) * t.scaleX

    fun setTransforms(g2d: Graphics2D, optimizationLevel: Int) {
        // Concatenate, do *not* overwrite. The Graphics2D handed to a
        // child component's paintComponent already has a transform
        // installed by Swing: it pre-translates so that (0, 0) is the
        // child's top-left, accounting for everything above and left
        // of it (window borders, tab bar, layout offsets…). Replacing
        // it with `t` would throw that pre-translation away and cause
        // drawing to land at (Plotter.t.translate) measured from the
        // window content origin, not from the canvas. Before the tab
        // bar landed, GraphView lived at (0, 0) of the content pane
        // and the pre-translation was a no-op, so the bug was
        // invisible — now the tab bar adds ~26 pixels of vertical
        // pre-translation that we must keep.
        val composed = AffineTransform(g2d.transform)
        composed.concatenate(t)
        g2d.transform = composed
        g2d.setFont(g2d.font.deriveFont(defaultFontSize.toFloat()))
        g2d.setStroke(BasicStroke(2.0f))
        renderOvals = optimizationLevel < 3
    }

    fun drawGraph(g2d: Graphics2D, g: Graph) {
        g2d.paint = defaultBgColor.toAwt()
        g2d.stroke = thinStroke
        val visible = g.visibleNodes()
        g.edges
            .filter { it.src in visible && it.dst in visible }
            .forEach { drawEdge(g2d, it) }
        g2d.stroke = thinStroke
        g.nodes
            .filter { it in visible }
            .forEach { drawNode(g2d, it, g.selectedNodes.contains(it)) }
    }

    fun drawSelectionBox(g2d: Graphics2D, ul: Vector2, br: Vector2) {
        g2d.paint = Constants.defaultFgColor.toAwt()
        g2d.drawRect(ul.x.toInt(), ul.y.toInt(), (br.x - ul.x).toInt(), (br.y - ul.y).toInt())
    }

    private fun paintShape(g2d: Graphics2D, n: Node) {
        val size = n.cache.shapeBounds
        val pos = n.position - size / 2
        val (x, y, w, h) = listOf(pos.x.toInt(), pos.y.toInt(), size.x.toInt(), size.y.toInt())
        val bg = n.attributes.bg.orElse(Constants.defaultBgColor).toAwt()
        val fg = n.attributes.fg.orElse(Constants.defaultFgColor).toAwt()
        when (val shape = n.cache.shape) {
            is OvalShape -> {
                g2d.paint = bg
                g2d.fillOval(x, y, w, h)
                g2d.paint = fg
                g2d.drawOval(x, y, w, h)
            }
            else -> {
                val r = (shape as? RectangleShape)?.cornerRadius ?: 5
                g2d.paint = bg
                g2d.fillRoundRect(x, y, w, h, r, r)
                g2d.paint = fg
                g2d.drawRoundRect(x, y, w, h, r, r)
            }
        }
    }

    object TextPlotter {

        fun metrics(lines: List<String>, fm: FontMetrics): TextMetrics {
            val maxWidth = lines.map { fm.stringWidth(it) }.maxOrNull() ?: 0
            return TextMetrics(
                bounds = Vector2(maxWidth, lines.size * fm.height),
                ascent = fm.ascent.toDouble(),
                lineHeight = fm.height.toDouble(),
            )
        }

        fun recomputeBounds(n: Node, fm: FontMetrics) {
            val lines = n.attributes.text.split("\n")
            NodeText.apply(n, lines, metrics(lines, fm))
        }

        fun drawEdge(g2d: Graphics2D, e: Edge) {
            val src = e.cache.srcPt
            val dst = e.cache.dstPt

            g2d.paint = edgePaint(src, dst)

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

        /**
         * Colour for an edge between world-space points [src]
         * and [dst]. Edges shorter than a quarter of the shorter
         * canvas dimension (on screen) render default black;
         * edges at or above the longer dimension render at
         * [Constants.edgeFadedMaxBrightness] gray. Linear ramp
         * in between.
         *
         * Long edges often cut across the centre of the graph
         * and obscure everything underneath — fading them to a
         * mid-gray keeps them visible as structure while letting
         * the shorter local connections stay prominent.
         *
         * TODO: if another feature needs a proper perceptual
         * colour ramp (between two arbitrary RGB endpoints), add
         * a full lerp helper then. For now a single-channel
         * brightness is enough and cheap — we only ever fade
         * from black toward a uniform gray.
         */
        private fun edgePaint(src: Vector2, dst: Vector2): Color {
            val worldLen = (dst - src).length().toFloat()
            val screenLen = worldLen * t.scaleX.toFloat()
            val w = screenDimensions.x.toFloat()
            val h = screenDimensions.y.toFloat()
            val shorterDim = minOf(w, h)
            val longerDim = maxOf(w, h)
            if (longerDim <= 0f) return Constants.defaultFgColor.toAwt()
            val fadeStart = shorterDim * 0.125f
            val fadeEnd = longerDim * 0.5f
            if (screenLen <= fadeStart) return Constants.defaultFgColor.toAwt()
            val fade = ((screenLen - fadeStart) / (fadeEnd - fadeStart)).coerceIn(0f, 1f)
            val brightness = (fade * Constants.edgeFadedMaxBrightness).toInt()
            return Color(brightness, brightness, brightness)
        }

        fun drawNode(g2d: Graphics2D, n: Node, selected: Boolean) {
            val bounds = n.cache
            val textPos = n.position - bounds.textBounds / 2;

            if (selected) {
                if (thickStroke.lineWidth * t.scaleX < 1.0) {
                    g2d.stroke = BasicStroke((thickStroke.lineWidth / t.scaleX * 1.1).toFloat())
                } else {
                    g2d.stroke = thickStroke
                }
            }

            paintShape(g2d, n)

            g2d.stroke = thinStroke

            g2d.paint = n.attributes.fg.orElse(Constants.defaultFgColor).toAwt()

            val screenspaceFD = FontData.get(g2d, screenspaceFontSize(n), FontData.isMonospace(n))

            /**
             * For small fonts (w.r.t. how it is shown on screen), output height is messed up because of integral height.
             *
             * This follows the text component rendering. Fixing it would mess up text-editing vs read-only node correspondence.
             *
             * We would have to render all nodes as UI components, which would (I fear) strongly affect performance.
             */

            // the font the node was measured with; absent until the first measurement
            FontData.cache[workspaceFontSize(n) to FontData.isMonospace(n)]
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

            g2d.paint = Constants.defaultFgColor.toAwt()
        }
    }
}

/** Measures node text with the fonts of [g2d]; what [Graph.recompute] needs on a paint. */
class AwtTextMeasurer(private val g2d: Graphics2D) : TextMeasurer {
    override fun measure(lines: List<String>, fontSize: Double, monospace: Boolean): TextMetrics = g2d.withIdentityTransform {
        val fd = FontData.get(g2d, fontSize, monospace)
        Plotter.TextPlotter.metrics(lines, g2d.getFontMetrics(fd.font))
    }

    companion object {
        private val offscreen: Graphics2D by lazy { BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics() }

        /**
         * Make AWT fonts the [TextMeasurer.default], so that code
         * without a window (headless runs, export, tests) gets the
         * same node sizes as the canvas. Idempotent.
         */
        fun install() {
            if (TextMeasurer.default !is AwtTextMeasurer) TextMeasurer.default = AwtTextMeasurer(offscreen)
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
        val cache: MutableMap<Pair<Double, Boolean>, FontData> = mutableMapOf()

        private val monospaceBase = Font(Font.MONOSPACED, Font.PLAIN, 12)

        fun isMonospace(n: Node): Boolean = NodeText.isMonospace(n)

        fun get(g2d: Graphics2D, fs: Double, monospace: Boolean = false): FontData {
            val fd = FontData.cache[fs to monospace].orElse {
                g2d.withIdentityTransform {
                    val mf = (if (monospace) monospaceBase else g2d.font).deriveFont(fs.toFloat())
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
                    FontData.cache[fs to monospace] = fontData
                    fontData
                }
            }
            return fd
        }
    }
}