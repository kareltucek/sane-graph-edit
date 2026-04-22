package export

import Graph
import graph_tools.GraphTools
import graph_tools.Node
import graph_tools.OvalShape
import graph_tools.Plotter
import graph_tools.RectangleShape
import utils.Constants
import utils.Utils.orElse
import utils.Utils.toHexString
import utils.Vector2
import java.awt.Color

/**
 * Exports a [Graph] to a self-contained SVG string.
 *
 * The output is a direct walk over the graph's nodes and edges,
 * emitting SVG primitives that match what [Plotter] draws on
 * screen: `<rect>` / `<ellipse>` for node shapes, `<line>` +
 * `<circle>` for edges and arrowheads, `<text>` for labels.
 *
 * All coordinates are in the graph's workspace space — there is no
 * view transform, no pan/zoom. The SVG `viewBox` is set to the
 * graph's bounding box plus a small margin so the whole graph
 * fits any viewer at any size.
 *
 * Before writing, node/edge caches are refreshed via
 * [Graph.recompute] using a headless [Graphics2D] (a 1×1
 * BufferedImage). This means the writer works even when no window
 * is visible — useful for a future headless CLI export mode.
 */
object SvgWriter {

    /** Margin around the bounding box, in workspace units. */
    private const val MARGIN = 30.0

    /**
     * Render [graph] as an SVG document. If [selection] is
     * non-null and non-empty, only those nodes (and edges
     * between them) are exported; otherwise the full graph is
     * exported.
     */
    fun write(graph: Graph, selection: Set<Node>? = null): String {
        graph.ensureCachesFresh(force = true)

        val nodes = selection?.takeIf { it.isNotEmpty() } ?: graph.nodes
        val nodeSet = nodes.toSet()
        val edges = graph.edges.filter { it.src in nodeSet && it.dst in nodeSet }

        val box = GraphTools.computeBoundingBox(nodes)
            ?: return emptySvg()

        val ul = box.ul - Vector2(MARGIN, MARGIN)
        val br = box.br + Vector2(MARGIN, MARGIN)
        val size = br - ul

        // Explicit width/height in addition to viewBox. Some viewers
        // (notably Firefox) cap zoom relative to the "intrinsic size"
        // of the SVG; without width/height, intrinsic size = viewport
        // size and you hit a ~500% ceiling quickly. A large explicit
        // size makes the base render bigger so the zoom ceiling is
        // effectively unreachable. We scale so the longer axis is
        // 4000px — large enough that 500% of it is 20000px, more than
        // any screen can show.
        val scaleFactor = 4000.0 / maxOf(size.x, size.y)
        val w = size.x * scaleFactor
        val h = size.y * scaleFactor

        val sb = StringBuilder()
        sb.appendLine("""<svg xmlns="http://www.w3.org/2000/svg"""")
        sb.appendLine("""     width="${fmt(w)}" height="${fmt(h)}"""")
        sb.appendLine("""     viewBox="${fmt(ul.x)} ${fmt(ul.y)} ${fmt(size.x)} ${fmt(size.y)}"""")
        sb.appendLine("""     font-family="sans-serif">""")

        // Edges first (drawn below nodes, same as Plotter).
        sb.appendLine("""  <g class="edges">""")
        for (e in edges) {
            val src = e.cache.srcPt
            val dst = e.cache.dstPt
            val fg = Constants.defaultFgColor.toHexString()
            sb.appendLine("""    <line x1="${fmt(src.x)}" y1="${fmt(src.y)}" x2="${fmt(dst.x)}" y2="${fmt(dst.y)}" stroke="$fg" stroke-width="1"/>""")
            val r = Constants.arrowheadRadius
            sb.appendLine("""    <circle cx="${fmt(dst.x)}" cy="${fmt(dst.y)}" r="${fmt(r)}" fill="$fg"/>""")
        }
        sb.appendLine("""  </g>""")

        // Nodes.
        sb.appendLine("""  <g class="nodes">""")
        for (n in nodes) {
            val bg = n.attributes.bg.orElse(Constants.defaultBgColor).toHexString()
            val fg = n.attributes.fg.orElse(Constants.defaultFgColor).toHexString()
            val cache = n.cache
            val shapePos = n.position - cache.shapeBounds / 2

            sb.appendLine("""    <g class="node">""")

            // Shape.
            when (cache.shape) {
                is OvalShape -> {
                    val cx = n.position.x
                    val cy = n.position.y
                    val rx = cache.shapeBounds.x / 2
                    val ry = cache.shapeBounds.y / 2
                    sb.appendLine("""      <ellipse cx="${fmt(cx)}" cy="${fmt(cy)}" rx="${fmt(rx)}" ry="${fmt(ry)}" fill="$bg" stroke="$fg" stroke-width="1"/>""")
                }
                else -> {
                    // Default to rectangle (matches RectangleShape).
                    val cr = if (cache.shape is RectangleShape) (cache.shape as RectangleShape).cornerRadius else 5
                    sb.appendLine("""      <rect x="${fmt(shapePos.x)}" y="${fmt(shapePos.y)}" width="${fmt(cache.shapeBounds.x)}" height="${fmt(cache.shapeBounds.y)}" rx="$cr" ry="$cr" fill="$bg" stroke="$fg" stroke-width="1"/>""")
                }
            }

            // Text.
            val fontSize = Plotter.workspaceFontSize(n)
            val fd = n.cache.font
            if (fd != null && cache.lines.isNotEmpty()) {
                val textPos = n.position - cache.textBounds / 2
                for ((i, line) in cache.lines.withIndex()) {
                    if (line.isBlank()) continue
                    // SVG <text> y is the baseline. Approximate by
                    // adding ascent for the first line, then line
                    // height for each subsequent line — matching the
                    // Plotter's drawString placement.
                    val y = textPos.y + fd.ascent + fd.height * i
                    sb.appendLine("""      <text x="${fmt(textPos.x)}" y="${fmt(y)}" font-size="${fmt(fontSize)}" fill="$fg">${esc(line)}</text>""")
                }
            }

            sb.appendLine("""    </g>""")
        }
        sb.appendLine("""  </g>""")
        sb.appendLine("""</svg>""")

        return sb.toString()
    }

    private fun emptySvg(): String =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"/>"""

    /** Format a double to one decimal place — keeps SVG compact. */
    private fun fmt(d: Double): String = "%.1f".format(d)

    /** XML-escape text content for safe embedding in SVG. */
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
