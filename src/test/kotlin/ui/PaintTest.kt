package ui

import Graph
import graph_tools.Edge
import graph_tools.Node
import graph_tools.NodeShape
import utils.Rgb
import utils.Vector2
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Paints a view into an image and looks at the pixels: the whole path from graph to scene to Plotter. */
class PaintTest {
    private val size = 400

    private fun paint(g: Graph, select: List<Node> = emptyList()): BufferedImage {
        System.setProperty("java.awt.headless", "true")
        val view = GraphView(initialGraph = g)
        view.g.cleanSelect(select)
        view.graphCanvas.setSize(size, size)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()
        g2d.color = java.awt.Color.WHITE
        g2d.fillRect(0, 0, size, size)
        // the first paint puts the workspace origin in the middle of the canvas
        view.graphCanvas.doDrawing(g2d)
        g2d.dispose()
        return image
    }

    private fun BufferedImage.rgb(x: Int, y: Int): Rgb = Rgb(getRGB(x, y) and 0xFFFFFF)

    /** Pixels in the square around ([cx], [cy]) that are (nearly) black. */
    private fun BufferedImage.dark(cx: Int, cy: Int, half: Int): Int =
        (cx - half..cx + half).sumOf { x -> (cy - half..cy + half).count { y -> rgb(x, y).let { it.red + it.green + it.blue < 150 } } }

    /**
     * How much paint is in the square around ([cx], [cy]): 0 for white. Lines are antialiased and long
     * edges fade to grey, so "is there a black pixel" does not find them.
     */
    private fun BufferedImage.ink(cx: Int, cy: Int, half: Int): Int =
        (cx - half..cx + half).sumOf { x -> (cy - half..cy + half).sumOf { y -> rgb(x, y).let { 765 - it.red - it.green - it.blue } } }

    @Test
    fun `nodes are filled with their colour and carry text, edges join them`() {
        val a = Node("", Vector2(-100, 0)).also { it.attributes.bg = Rgb(200, 30, 30) }
        val b = Node("Hello", Vector2(100, 0)).also { it.attributes.bg = Rgb(30, 200, 30) }
        val g = Graph().also { it.add(a); it.add(b); it.add(Edge(a, b)) }
        val image = paint(g)
        val mid = size / 2

        // a has no text, so its centre is pure fill
        assertEquals(Rgb(200, 30, 30), image.rgb(mid - 100, mid))
        // b's text is drawn over its fill
        assertTrue(image.dark(mid + 100, mid, 12) > 10, "no text pixels in node b")
        // the edge runs along y = 0 between the nodes
        assertTrue(image.ink(mid, mid, 2) > 0, "no edge between the nodes")
        assertEquals(0, image.ink(mid, mid - 20, 2), "paint above the edge")
        // nothing far away from the graph
        assertEquals(Rgb(255, 255, 255), image.rgb(mid, mid + 150))
    }

    @Test
    fun `an oval leaves the corners of its bounds unpainted, a box fills them`() {
        val box = Node("wide enough text", Vector2(0, -80)).also { it.attributes.bg = Rgb(0, 0, 255) }
        val oval = Node("wide enough text", Vector2(0, 80)).also { it.attributes.bg = Rgb(0, 0, 255); it.setShape(NodeShape.Oval) }
        val g = Graph().also { it.add(box); it.add(oval) }
        val image = paint(g)
        val mid = size / 2

        val boxCorner = box.position - box.cache.shapeBounds / 2 + Vector2(3, 3)
        val ovalCorner = oval.position - oval.cache.shapeBounds / 2 + Vector2(3, 3)
        assertEquals(Rgb(0, 0, 255), image.rgb(mid + boxCorner.x.toInt(), mid + boxCorner.y.toInt()))
        assertEquals(Rgb(255, 255, 255), image.rgb(mid + ovalCorner.x.toInt(), mid + ovalCorner.y.toInt()))
    }

    @Test
    fun `a selected node has a thicker outline`() {
        fun outline(selected: Boolean): Int {
            val n = Node("", Vector2(0, 0)).also { it.attributes.bg = Rgb(255, 255, 0) }
            val image = paint(Graph().also { it.add(n) }, if (selected) listOf(n) else emptyList())
            val left = size / 2 - (n.cache.shapeBounds.x / 2).toInt()
            return image.ink(left, size / 2, 3)
        }
        assertTrue(outline(selected = true) > outline(selected = false))
    }
}
