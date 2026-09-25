package graph_tools

import Graph
import utils.Vector2
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicPlacementTest {

    @AfterTest
    fun modesOff() {
        ViewModes.dynamic = false
    }

    /** A at the origin with a far-away child B (hidden) to the right, and C hidden, child of B. */
    private fun graph(): Triple<Graph, Map<String, Node>, Node> {
        val g = Graph()
        val ns = mapOf(
            "A" to Node("A", Vector2(0, 0)),
            "B" to Node("B", Vector2(5000, 0)),
            "C" to Node("C", Vector2(5000, 3000)),
            "D" to Node("D", Vector2(-200, 0)),
        )
        ns.values.forEach { g.add(it) }
        g.add(Edge(ns.getValue("A"), ns.getValue("B")))
        g.add(Edge(ns.getValue("B"), ns.getValue("C")))
        g.add(Edge(ns.getValue("D"), ns.getValue("A")))
        g.ensureCachesFresh(force = true)
        ns.getValue("B").cache.hideLevel = 1
        ns.getValue("C").cache.hideLevel = 1
        return Triple(g, ns, ns.getValue("B"))
    }

    private fun overlap(a: Node, b: Node): Boolean =
        abs(a.position.x - b.position.x) < (a.cache.shapeBounds.x + b.cache.shapeBounds.x) / 2 &&
            abs(a.position.y - b.position.y) < (a.cache.shapeBounds.y + b.cache.shapeBounds.y) / 2

    @Test
    fun `nothing moves while the mode is off, or on the first call after turning it on`() {
        val (g, ns, b) = graph()
        DynamicPlacement.place(g)
        ViewModes.dynamic = true
        b.cache.hideLevel = 0
        DynamicPlacement.place(g)  // first call only records the baseline
        assertEquals(Vector2(5000, 0), b.position)
    }

    @Test
    fun `a newly shown node lands near its visible neighbour, towards where it was`() {
        val (g, ns, b) = graph()
        ViewModes.dynamic = true
        DynamicPlacement.place(g)
        b.cache.hideLevel = 0
        DynamicPlacement.place(g)

        val a = ns.getValue("A")
        assertTrue(b.position.x > a.position.x, "B stays on A's right: ${b.position}")
        assertTrue((b.position - a.position).length() < 1000, "B is near A: ${b.position}")
        assertFalse(overlap(a, b))
        assertFalse(overlap(ns.getValue("D"), b))
    }

    @Test
    fun `chains are placed outward and the move is one undo step`() {
        val (g, ns, b) = graph()
        val c = ns.getValue("C")
        ViewModes.dynamic = true
        DynamicPlacement.place(g)
        b.cache.hideLevel = 0
        c.cache.hideLevel = 0
        DynamicPlacement.place(g)

        assertTrue((c.position - b.position).length() < 1000, "C placed next to B: ${c.position}")
        assertFalse(overlap(b, c))
        g.history.undo()
        assertEquals(Vector2(5000, 0), b.position)
        assertEquals(Vector2(5000, 3000), c.position)
    }

    @Test
    fun `no placement while there is something to redo`() {
        val (g, ns, b) = graph()
        ViewModes.dynamic = true
        g.commit(HideCommand(listOf(ns.getValue("D"))))
        g.history.undo()  // D visible again, but via undo
        DynamicPlacement.place(g)
        b.cache.hideLevel = 0
        DynamicPlacement.place(g)
        assertEquals(Vector2(5000, 0), b.position)
        assertTrue(g.history.canRedo)
    }
}
