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

        assertTrue(a.isVisible)
        assertFalse(b.isVisible)
        assertFalse(c.isVisible)
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

        assertTrue(a.isVisible)
        assertTrue(b.isVisible)
        assertTrue(c.isVisible)
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
        assertTrue(a.isVisible)
        assertTrue(b.isVisible)
        assertTrue(c.isVisible)
        assertFalse(d.isVisible)  // still at level 1
        assertEquals(1, d.cache.hideLevel)

        // Second unhide: everything visible.
        g.commit(UnhideCommand(g.nodes.filter { it.cache.hideLevel > 0 }))
        assertTrue(d.isVisible)
    }

    @Test
    fun `hide is undoable`() {
        val (g, a, b) = threeNodeGraph()
        val c = g.nodes.first { it.attributes.text == "C" }

        g.commit(HideCommand(listOf(b, c)))
        assertFalse(b.isVisible)

        g.history.undo()
        assertTrue(b.isVisible)
        assertTrue(c.isVisible)

        g.history.redo()
        assertFalse(b.isVisible)
        assertFalse(c.isVisible)
    }

    @Test
    fun `unhide is undoable`() {
        val (g, a, b) = threeNodeGraph()
        g.commit(HideCommand(listOf(b)))
        assertFalse(b.isVisible)

        g.commit(UnhideCommand(listOf(b)))
        assertTrue(b.isVisible)

        g.history.undo()
        assertFalse(b.isVisible)  // undo of unhide = re-hide

        g.history.redo()
        assertTrue(b.isVisible)
    }

    @Test
    fun `computeGeneration skips hidden nodes`() {
        val (g, a, b) = threeNodeGraph()
        // Hide B. Forward generation from A should skip B (hidden).
        b.cache.hideLevel = 1

        val gen = GraphTools.computeGeneration(g, setOf(a), forward = true)
        assertFalse(gen.contains(b))
    }
}
