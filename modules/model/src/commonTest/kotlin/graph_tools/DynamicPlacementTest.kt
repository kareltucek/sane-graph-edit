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

    /** [g] in explore mode from [pins] on, as entering the mode sets it up. */
    private fun explore(g: Graph, pins: Set<Node>) {
        g.beforeExplore = g.nodes.associateWith { it.position }
        g.pinned = pins
        g.dynamicBaseline = pins
        DynamicPlacement.place(g)
    }

    @Test
    fun `explore mode - a node that comes to be seen is where the document has it from its parent, but near, with nothing to undo`() {
        val g = Graph()
        val root = Node("root", Vector2(0, 0))
        // far away in the document, each in a direction of its own
        val kids = listOf(Node("right", Vector2(3000, 0)), Node("up", Vector2(0, -4000)), Node("down left", Vector2(-2500, 2500)))
        (kids + root).forEach { g.add(it) }
        kids.forEach { g.add(Edge(root, it)) }
        g.ensureCachesFresh(force = true)
        val document = g.nodes.associateWith { it.position }

        explore(g, setOf(root))

        assertEquals(Vector2(0, 0), root.position)
        kids.forEach { kid ->
            assertTrue(kid.position.length() < 600, "${document.getValue(kid)} was brought in: ${kid.position}")
            assertTrue(kid.position.toUnit().dot(document.getValue(kid).toUnit()) > 0.95, "${document.getValue(kid)} kept its direction: ${kid.position}")
            assertFalse(overlap(kid, root))
        }
        // where nodes are in explore mode is not the document's: nothing to undo
        assertFalse(g.history.canUndo)
    }

    @Test
    fun `explore mode - what is unfolded pushes a faint node in its way off, the pinned ones stay`() {
        val g = Graph()
        val root = Node("root", Vector2(0, 0))
        val kid = Node("kid", Vector2(200, 0))
        // where the grandkid is going to be: right of the kid
        val other = Node("other kid", Vector2(330, 5))
        val grandkid = Node("grandkid", Vector2(9200, 0))
        listOf(root, kid, other, grandkid).forEach { g.add(it) }
        g.add(Edge(root, kid))
        g.add(Edge(root, other))
        g.add(Edge(kid, grandkid))
        g.ensureCachesFresh(force = true)
        kid.position = Vector2(200, 0)

        explore(g, setOf(root))
        val kidWas = kid.position
        val otherWas = other.position
        g.beforeExplore = g.beforeExplore!! + (grandkid to (g.beforeExplore!!.getValue(kid) + Vector2(130, 0)))
        g.pinned = setOf(root, kid)
        DynamicPlacement.place(g)

        val seen = g.visibleNodes().toList()
        assertEquals(g.nodes.toSet(), seen.toSet())
        seen.forEachIndexed { i, a -> seen.drop(i + 1).forEach { b -> assertFalse(overlap(a, b), "nodes at ${a.position} and ${b.position} overlap") } }
        assertEquals(Vector2(0, 0), root.position)
        assertEquals(kidWas, kid.position)
        assertTrue(grandkid.position.x > kid.position.x && (grandkid.position - kid.position).length() < 600, "the grandkid is right of its parent: ${grandkid.position}")
        assertTrue(other.position != otherWas, "the faint node gave way")
    }

    @Test
    fun `explore mode - the many children of a hub gather around it without overlapping`() {
        val g = Graph()
        val hub = Node("hub", Vector2(0, 0))
        val kids = (0 until 60).map { Node("kid $it", Vector2(5000 * kotlin.math.cos(it * 0.1047), 5000 * kotlin.math.sin(it * 0.1047))) }
        (kids + hub).forEach { g.add(it) }
        kids.forEach { g.add(Edge(hub, it)) }
        g.ensureCachesFresh(force = true)

        explore(g, setOf(hub))

        assertTrue(kids.all { it.position.length() < 1500 }, "all near the hub; the furthest is ${kids.maxOf { it.position.length() }} away")
        val overlapping = kids.indices.sumOf { i -> kids.drop(i + 1).count { overlap(kids[i], it) } }
        assertEquals(0, overlapping, "pairs of children that overlap")
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
