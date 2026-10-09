package graph_tools

import utils.Constants
import utils.Utils.orElse
import utils.Vector2
import kotlin.math.pow

/** Size of a block of text, in workspace units. */
data class TextMetrics(
    val bounds: Vector2,
    val ascent: Double,
    val lineHeight: Double,
    /**
     * Space to leave between the text and the edge of a box, for a
     * renderer whose text has exactly the measured size. Null: the
     * shape's own allowance, which grows with the text because a
     * renderer with whole-pixel fonts may draw it larger.
     */
    val margin: Double? = null,
)

/**
 * Measures node text. Only a renderer knows its fonts, so the model
 * asks through this interface; node sizes ([Node.NodeCache]) and
 * everything derived from them (edge endpoints, layout) follow from
 * the answers.
 */
interface TextMeasurer {
    fun measure(lines: List<String>, fontSize: Double, monospace: Boolean): TextMetrics

    companion object {
        /**
         * Used by [Graph.ensureCachesFresh] and by [Graph.recompute]
         * when no measurer is given. A renderer installs its own at
         * startup; without one, sizes are estimated from the
         * character count.
         */
        var default: TextMeasurer = ApproximateTextMeasurer
    }
}

/** Font-free estimate: every character is 0.6 em wide. */
object ApproximateTextMeasurer : TextMeasurer {
    override fun measure(lines: List<String>, fontSize: Double, monospace: Boolean): TextMetrics {
        val lineHeight = fontSize * 1.25
        val width = (lines.maxOfOrNull { it.length } ?: 0) * fontSize * 0.6
        return TextMetrics(Vector2(width, lines.size * lineHeight), ascent = fontSize, lineHeight = lineHeight)
    }
}

object NodeText {
    /** `fontname` values (Graphviz attribute) that select a monospaced font. */
    private val monospaceNames = setOf("monospace", "mono", "courier", "courier new", "monospaced")

    fun isMonospace(n: Node): Boolean = n.attributes.other["fontname"]?.lowercase() in monospaceNames

    fun fontScale(n: Node): Double = Constants.fontSizeZoomCoef.pow(n.attributes.nodeScale.orElse(0.0))

    /** Font size of [n] in workspace units. */
    fun fontSize(n: Node): Double = fontScale(n) * Constants.defaultFontSize

    /** Measure [n]'s text and store the resulting sizes in its cache. */
    fun recompute(n: Node, measurer: TextMeasurer) {
        val lines = n.attributes.text.split("\n")
        apply(n, lines, measurer.measure(lines, fontSize(n), isMonospace(n)))
    }

    fun apply(n: Node, lines: List<String>, metrics: TextMetrics) {
        n.cache.textBounds = metrics.bounds
        n.cache.shapeBounds = n.cache.shape.computeShapeBounds(metrics.bounds, metrics.margin)
        n.cache.lines = lines
        n.cache.ascent = metrics.ascent
        n.cache.lineHeight = metrics.lineHeight
    }
}

object EdgeGeometry {
    fun recompute(edges: Iterable<Edge>) {
        edges.forEach { recompute(it) }
    }

    /** Place the edge's endpoints on the boundaries of its nodes' shapes. */
    fun recompute(e: Edge) {
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
}
