@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package export

import Graph
import graph_tools.Edge
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgWriterTest {

    private fun simpleGraph(): Graph {
        val g = Graph()
        val a = Node("Hello", Vector2(0, 0))
        val b = Node("World", Vector2(200, 0))
        g.add(a); g.add(b); g.add(Edge(a, b))
        return g
    }

    @Test
    fun `output is valid SVG with correct structure`() {
        val svg = SvgWriter.write(simpleGraph())
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.contains("viewBox="))
        assertTrue(svg.contains("</svg>"))
    }

    @Test
    fun `node count matches graph`() {
        val svg = SvgWriter.write(simpleGraph())
        // Each node produces one <g class="node"> group.
        val nodeGroups = Regex("""<g class="node">""").findAll(svg).count()
        assertEquals(2, nodeGroups)
    }

    @Test
    fun `edge count matches graph`() {
        val svg = SvgWriter.write(simpleGraph())
        // Each edge produces one <line> and one <circle> arrowhead.
        val lines = Regex("""<line """).findAll(svg).count()
        val circles = Regex("""<circle """).findAll(svg).count()
        assertEquals(1, lines)
        assertEquals(1, circles)
    }

    @Test
    fun `text content is XML-escaped`() {
        val g = Graph()
        g.add(Node("<b>bold</b> & \"quoted\"", Vector2(0, 0)))
        val svg = SvgWriter.write(g)
        assertTrue(svg.contains("&lt;b&gt;bold&lt;/b&gt; &amp; &quot;quoted&quot;"))
    }

    @Test
    fun `empty graph produces minimal SVG`() {
        val svg = SvgWriter.write(Graph())
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("/>") || svg.contains("</svg>"))
    }

    @Test
    fun `selection export includes only selected nodes`() {
        val g = Graph()
        val a = Node("A", Vector2(0, 0))
        val b = Node("B", Vector2(100, 0))
        val c = Node("C", Vector2(200, 0))
        g.add(a); g.add(b); g.add(c)
        g.add(Edge(a, b)); g.add(Edge(b, c))

        // Export only {a, b} — the b→c edge should be dropped.
        val svg = SvgWriter.write(g, setOf(a, b))
        val nodeGroups = Regex("""<g class="node">""").findAll(svg).count()
        val lines = Regex("""<line """).findAll(svg).count()
        assertEquals(2, nodeGroups)
        assertEquals(1, lines)  // a→b only, b→c excluded
    }

    @Test
    fun `multiline text produces multiple text elements`() {
        val g = Graph()
        g.add(Node("line1\nline2\nline3", Vector2(0, 0)))
        val svg = SvgWriter.write(g)
        val textElements = Regex("""<text """).findAll(svg).count()
        assertEquals(3, textElements)
    }

    @Test
    fun `default graph round-trips without error`() {
        // Exercises the full pipeline on the help graph that the
        // editor shows on startup — a good smoke test because it
        // has multiple shapes, edges, and long multiline labels.
        val svg = SvgWriter.write(Graph.defaultGraph())
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("</svg>"))
        // The hub, a node per category and the one that leads to the examples should be present.
        val nodeGroups = Regex("""<g class="node">""").findAll(svg).count()
        assertEquals(21, nodeGroups)
        // a star from the hub
        val edgeLines = Regex("""<line """).findAll(svg).count()
        assertEquals(20, edgeLines)
    }

    @Test
    fun `no two nodes of the help graph overlap`() {
        val g = Graph.defaultGraph()
        g.recompute(graph_tools.TextMeasurer.default)
        val nodes = g.nodes.toList()
        val overlapping = nodes.flatMap { a -> nodes.filter { b -> a.id < b.id && overlap(a, b) }.map { a.attributes.text.lines().first() to it.attributes.text.lines().first() } }
        assertEquals(emptyList(), overlapping)    }

    private fun overlap(a: graph_tools.Node, b: graph_tools.Node): Boolean {
        val reach = (a.cache.shapeBounds + b.cache.shapeBounds) / 2.0
        val apart = a.position - b.position
        return kotlin.math.abs(apart.x) < reach.x && kotlin.math.abs(apart.y) < reach.y
    }
}
