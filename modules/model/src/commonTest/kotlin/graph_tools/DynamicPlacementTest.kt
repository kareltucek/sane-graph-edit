@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

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
    fun `a shown parent goes left, shown siblings stack in their original order`() {
        val g = Graph()
        val a = Node("A", Vector2(0, 0))
        val p = Node("P", Vector2(900, 900))      // parent of A, originally far right
        val c1 = Node("C1", Vector2(-900, -50))   // children of A, originally far left
        val c2 = Node("C2", Vector2(-900, 50))
        listOf(a, p, c1, c2).forEach { g.add(it) }
        g.add(Edge(p, a)); g.add(Edge(a, c1)); g.add(Edge(a, c2))
        g.ensureCachesFresh(force = true)
        listOf(p, c1, c2).forEach { it.cache.hideLevel = 1 }
        ViewModes.dynamic = true
        DynamicPlacement.place(g)
        listOf(p, c1, c2).forEach { it.cache.hideLevel = 0 }
        DynamicPlacement.place(g)

        assertTrue(p.position.x < a.position.x, "parent left of A: ${p.position}")
        assertTrue(c1.position.x > a.position.x && c2.position.x > a.position.x, "children right of A")
        assertEquals(c1.position.x, c2.position.x, 1e-9)  // one column
        assertTrue(c1.position.y < c2.position.y, "C1 above C2, as originally")
        assertFalse(overlap(c1, c2))
    }

    @Test
    fun `a newly shown child lands right of its visible parent`() {
        val (g, ns, b) = graph()
        ViewModes.dynamic = true
        DynamicPlacement.place(g)
        b.cache.hideLevel = 0
        DynamicPlacement.place(g)

        val a = ns.getValue("A")
        assertTrue(b.position.x > a.position.x, "B goes right of A: ${b.position}")
        assertEquals(a.position.y, b.position.y, 1e-9)  // level with A: nothing in the way
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
    fun `explore mode - what is unfolded pushes the faint nodes in its way off, the pinned ones stay, with nothing to undo`() {
        val g = Graph()
        val root = Node("root", Vector2(0, 0))
        // the last kid is where the grandkids are going to be put
        val kids = (0..2).map { Node("kid $it", Vector2(150, -60 + 40 * it)) } + Node("kid 3", Vector2(285, -20))
        val grandkids = (0..2).map { Node("grandkid $it", Vector2(9000, 9000 + 100 * it)) }
        (kids + grandkids + root).forEach { g.add(it) }
        kids.forEach { g.add(Edge(root, it)) }
        grandkids.forEach { g.add(Edge(kids[1], it)) }
        g.ensureCachesFresh(force = true)
        val before = g.nodes.associateWith { it.position }

        g.pinned = setOf(root)
        DynamicPlacement.place(g)
        g.pinned = setOf(root, kids[1])
        DynamicPlacement.place(g)

        val seen = g.visibleNodes().toList()
        assertEquals(g.nodes.toSet(), seen.toSet())
        seen.forEachIndexed { i, a -> seen.drop(i + 1).forEach { b -> assertFalse(overlap(a, b), "nodes at ${a.position} and ${b.position} overlap") } }
        assertEquals(Vector2(0, 0), root.position)
        assertEquals(before.getValue(kids[1]), kids[1].position)
        assertTrue(grandkids.all { (it.position - kids[1].position).length() < 1000 }, "the grandkids are next to their parent")
        assertTrue(kids[3].position != before.getValue(kids[3]), "the faint node gave way")
        assertTrue(grandkids.any { abs(it.position.y - kids[1].position.y) < 10 }, "a grandkid is level with its parent")
        // where nodes are in explore mode is not the document's: nothing to undo
        assertFalse(g.history.canUndo)
    }

    @Test
    fun `explore mode - many children are stacked no higher than a column is, in columns side by side`() {
        val g = Graph()
        val hub = Node("hub", Vector2(0, 0))
        val kids = (0 until 120).map { Node("kid $it", Vector2(9000, 100 * it)) }
        (kids + hub).forEach { g.add(it) }
        kids.forEach { g.add(Edge(hub, it)) }
        g.ensureCachesFresh(force = true)

        g.pinned = setOf(hub)
        g.dynamicBaseline = setOf(hub)
        DynamicPlacement.place(g)

        val high = kids.maxOf { it.position.y } - kids.minOf { it.position.y }
        assertTrue(high <= DynamicPlacement.EXPLORE_HEIGHT, "the stack is $high high")
        assertTrue(kids.map { it.position.x }.distinct().size > 1, "several columns")
        assertTrue(kids.all { it.position.x > hub.position.x }, "all right of their parent")
        kids.forEachIndexed { i, a -> kids.drop(i + 1).forEach { b -> assertFalse(overlap(a, b), "nodes at ${a.position} and ${b.position} overlap") } }
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
