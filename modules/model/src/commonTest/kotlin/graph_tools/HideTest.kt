@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package graph_tools

import Graph
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HideTest {

    private fun threeNodeGraph(): Triple<Graph, Node, Node> {
        val g = Graph()
        val a = Node("A", Vector2(0, 0))
        val b = Node("B", Vector2(100, 0))
        val c = Node("C", Vector2(200, 0))
        g.add(a); g.add(b); g.add(c)
        g.add(Edge(a, b)); g.add(Edge(b, c))
        return Triple(g, a, b)
    }

    @Test
    fun `hide increments hideLevel on affected nodes`() {
        val (g, a, b) = threeNodeGraph()
        val c = g.nodes.first { it.attributes.text == "C" }

        // "Select" a, hide everything else.
        val affected = g.nodes.filter { it !== a }
        g.commit(HideCommand(affected))

        assertTrue(g.isVisible(a))
        assertFalse(g.isVisible(b))
        assertFalse(g.isVisible(c))
        assertEquals(0, a.cache.hideLevel)
        assertEquals(1, b.cache.hideLevel)
        assertEquals(1, c.cache.hideLevel)
    }

    @Test
    fun `unhide decrements hideLevel`() {
        val (g, a, b) = threeNodeGraph()
        val c = g.nodes.first { it.attributes.text == "C" }

        val affected = g.nodes.filter { it !== a }
        g.commit(HideCommand(affected))

        val hidden = g.nodes.filter { it.cache.hideLevel > 0 }
        g.commit(UnhideCommand(hidden))

        assertTrue(g.isVisible(a))
        assertTrue(g.isVisible(b))
        assertTrue(g.isVisible(c))
    }

    @Test
    fun `two hides stack, two unhides unwind`() {
        val g = Graph()
        val a = Node("A", Vector2(0, 0))
        val b = Node("B", Vector2(100, 0))
        val c = Node("C", Vector2(200, 0))
        val d = Node("D", Vector2(300, 0))
        g.add(a); g.add(b); g.add(c); g.add(d)

        // First hide: keep {A, B, C}, hide {D}.
        // h increments ALL non-selected, including already-hidden.
        g.commit(HideCommand(g.nodes.filter { it !== a && it !== b && it !== c }))
        assertEquals(0, a.cache.hideLevel)
        assertEquals(0, b.cache.hideLevel)
        assertEquals(0, c.cache.hideLevel)
        assertEquals(1, d.cache.hideLevel)

        // Second hide: keep {A}, hide {B, C, D}.
        g.commit(HideCommand(g.nodes.filter { it !== a }))
        assertEquals(0, a.cache.hideLevel)
        assertEquals(1, b.cache.hideLevel)
        assertEquals(1, c.cache.hideLevel)
        assertEquals(2, d.cache.hideLevel)  // stacked!

        // First unhide: decrement all hidden.
        g.commit(UnhideCommand(g.nodes.filter { it.cache.hideLevel > 0 }))
        assertTrue(g.isVisible(a))
        assertTrue(g.isVisible(b))
        assertTrue(g.isVisible(c))
        assertFalse(g.isVisible(d))  // still at level 1
        assertEquals(1, d.cache.hideLevel)

        // Second unhide: everything visible.
        g.commit(UnhideCommand(g.nodes.filter { it.cache.hideLevel > 0 }))
        assertTrue(g.isVisible(d))
    }

    @Test
    fun `hide is undoable`() {
        val (g, a, b) = threeNodeGraph()
        val c = g.nodes.first { it.attributes.text == "C" }

        g.commit(HideCommand(listOf(b, c)))
        assertFalse(g.isVisible(b))

        g.history.undo()
        assertTrue(g.isVisible(b))
        assertTrue(g.isVisible(c))

        g.history.redo()
        assertFalse(g.isVisible(b))
        assertFalse(g.isVisible(c))
    }

    @Test
    fun `unhide is undoable`() {
        val (g, a, b) = threeNodeGraph()
        g.commit(HideCommand(listOf(b)))
        assertFalse(g.isVisible(b))

        g.commit(UnhideCommand(listOf(b)))
        assertTrue(g.isVisible(b))

        g.history.undo()
        assertFalse(g.isVisible(b))  // undo of unhide = re-hide

        g.history.redo()
        assertTrue(g.isVisible(b))
    }

    @Test
    fun `computeGeneration skips hidden nodes`() {
        val (g, a, b) = threeNodeGraph()
        // Hide B. Forward generation from A should skip B (hidden).
        b.cache.hideLevel = 1

        val gen = GraphTools.computeGeneration(g, setOf(a), forward = true)
        assertFalse(gen.contains(b))
    }

    /** A -> B -> C -> D, with the given nodes hidden. */
    private fun chain(vararg hidden: String): Pair<Graph, Map<String, Node>> {
        val g = Graph()
        val ns = listOf("A", "B", "C", "D").mapIndexed { i, s -> s to Node(s, Vector2(100 * i, 0)) }.toMap()
        ns.values.forEach { g.add(it) }
        ns.values.zipWithNext().forEach { (x, y) -> g.add(Edge(x, y)) }
        hidden.forEach { ns.getValue(it).cache.hideLevel = 1 }
        return g to ns
    }

    private fun withModes(
        unhide: Boolean = false,
        showParents: Boolean = false,
        showChildren: Boolean = false,
        body: () -> Unit,
    ) {
        ViewModes.unhide = unhide
        ViewModes.showParents = showParents
        ViewModes.showChildren = showChildren
        try {
            body()
        } finally {
            ViewModes.unhide = false
            ViewModes.showParents = false
            ViewModes.showChildren = false
        }
    }

    @Test
    fun `unhide mode lets traversal reach hidden nodes`() = withModes(unhide = true) {
        val (g, ns) = chain("B", "C")
        assertEquals(setOf(ns["B"]), GraphTools.computeGeneration(g, setOf(ns.getValue("A")), forward = true))
        assertEquals(ns.values.toSet(), GraphTools.computeClosure(g, setOf(ns.getValue("A")), forward = true))
    }

    @Test
    fun `reveal clears any hide level and undo restores it`() {
        val (g, ns) = chain("B")
        val b = ns.getValue("B")
        b.cache.hideLevel = 3
        g.commit(RevealCommand(mapOf(b to 3)))
        assertTrue(g.isVisible(b))
        g.history.undo()
        assertEquals(3, b.cache.hideLevel)
    }

    @Test
    fun `show-children shows children of shown nodes only`() = withModes(showChildren = true) {
        val (g, ns) = chain("B", "C", "D")
        assertTrue(g.isVisible(ns.getValue("B")))   // child of A
        assertFalse(g.isVisible(ns.getValue("C")))  // only below hidden B
        g.select(ns.getValue("B"))
        assertTrue(g.isVisible(ns.getValue("C")))   // child of selected B
        assertFalse(g.isVisible(ns.getValue("D")))
        assertEquals(setOf("A", "B", "C"), g.visibleNodes().map { it.attributes.text }.toSet())
    }

    @Test
    fun `a hidden node seen as a child is faint until it is selected`() = withModes(showChildren = true) {
        val (g, ns) = chain("B", "C", "D")
        assertFalse(g.isFaint(ns.getValue("A")))    // not hidden
        assertTrue(g.isFaint(ns.getValue("B")))     // hidden child of A
        assertFalse(g.isFaint(ns.getValue("C")))    // not seen at all
        g.select(ns.getValue("B"))
        assertFalse(g.isFaint(ns.getValue("B")))    // selected
        assertTrue(g.isFaint(ns.getValue("C")))     // child of selected B
    }

    @Test
    fun `show-parents shows parents, not children`() = withModes(showParents = true) {
        val (g, ns) = chain("A", "B", "D")          // only C is not hidden
        assertTrue(g.isVisible(ns.getValue("B")))   // parent of C
        assertFalse(g.isVisible(ns.getValue("D")))  // child of C
        assertFalse(g.isVisible(ns.getValue("A")))  // parent of hidden B
        assertEquals(setOf("B", "C"), g.visibleNodes().map { it.attributes.text }.toSet())
    }

    @Test
    fun `without show modes a selected hidden node stays invisible`() {
        val (g, ns) = chain("B")
        g.select(ns.getValue("B"))
        assertFalse(g.isVisible(ns.getValue("B")))
    }
}
