package graph_tools

import Graph
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryTest {

    /**
     * Helper: serialise the observable state of a graph into something
     * stable we can assertEquals over. We don't use [parser_dot.Serializer]
     * directly because it pulls in rendering state; a hand-rolled tuple
     * is enough for these tests.
     */
    private fun snapshot(g: Graph): String {
        val nodeLines = g.nodes
            .map { "N(${it.attributes.text},${it.position.x},${it.position.y})" }
            .sorted()
            .joinToString("\n")
        val edgeLines = g.edges
            .map { "E(${it.src.attributes.text}->${it.dst.attributes.text})" }
            .sorted()
            .joinToString("\n")
        return "$nodeLines||$edgeLines"
    }

    @Test
    fun `add node, undo, redo round-trips`() {
        val g = Graph()
        val initial = snapshot(g)
        val n = Node("a", Vector2(0, 0))

        g.commit(AddNodeCommand(g, n))
        val afterAdd = snapshot(g)
        assertTrue(g.nodes.contains(n))

        g.history.undo()
        assertEquals(initial, snapshot(g))
        assertFalse(g.nodes.contains(n))

        g.history.redo()
        assertEquals(afterAdd, snapshot(g))
        assertTrue(g.nodes.contains(n))
    }

    @Test
    fun `add edge, undo, redo`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(100, 0))
        g.add(a)
        g.add(b)
        val before = snapshot(g)

        val e = Edge(a, b)
        g.commit(AddEdgesCommand(g, listOf(e)))
        val after = snapshot(g)
        assertTrue(g.edges.contains(e))

        g.history.undo()
        assertEquals(before, snapshot(g))
        assertFalse(g.edges.contains(e))

        g.history.redo()
        assertEquals(after, snapshot(g))
        assertTrue(g.edges.contains(e))
    }

    @Test
    fun `delete with composite removes nodes and edges, undo restores both`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(100, 0))
        val c = Node("c", Vector2(200, 0))
        g.add(a); g.add(b); g.add(c)
        val eAB = Edge(a, b)
        val eBC = Edge(b, c)
        g.add(eAB); g.add(eBC)

        val baseline = snapshot(g)

        // Delete `b`, removing both incident edges.
        val incident = listOf(eAB, eBC)
        g.commit(
            CompositeCommand(
                listOf(
                    RemoveEdgesCommand(g, incident),
                    RemoveNodesCommand(g, listOf(b)),
                )
            )
        )
        assertFalse(g.nodes.contains(b))
        assertFalse(g.edges.contains(eAB))
        assertFalse(g.edges.contains(eBC))

        g.history.undo()
        assertEquals(baseline, snapshot(g))
        assertTrue(g.nodes.contains(b))
        assertTrue(g.edges.contains(eAB))
        assertTrue(g.edges.contains(eBC))

        g.history.redo()
        assertFalse(g.nodes.contains(b))
        assertFalse(g.edges.contains(eAB))
    }

    @Test
    fun `MoveNodesCommand undo restores positions`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(10, 10))
        g.add(a); g.add(b)

        val before = mapOf(a to a.position, b to b.position)
        a.position = Vector2(100, 100)
        b.position = Vector2(110, 110)
        val after = mutableMapOf<Node, Vector2>(a to a.position, b to b.position)

        g.history.commitWithoutRun(MoveNodesCommand(g, before, after))

        assertEquals(Vector2(100, 100), a.position)
        g.history.undo()
        assertEquals(Vector2(0, 0), a.position)
        assertEquals(Vector2(10, 10), b.position)

        g.history.redo()
        assertEquals(Vector2(100, 100), a.position)
        assertEquals(Vector2(110, 110), b.position)
    }

    @Test
    fun `apply clears the redo stack`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        g.commit(AddNodeCommand(g, a))
        g.history.undo()
        assertTrue(g.history.canRedo)

        val b = Node("b", Vector2(50, 50))
        g.commit(AddNodeCommand(g, b))

        // Applying a new command while there's redo state should drop it:
        // otherwise Ctrl+Z Ctrl+Z "I changed my mind" diverges into
        // unreachable history branches.
        assertFalse(g.history.canRedo)
    }

    @Test
    fun `undo on empty history is a no-op`() {
        val g = Graph()
        val before = snapshot(g)
        g.history.undo()
        assertEquals(before, snapshot(g))
    }

    @Test
    fun `history respects max depth by dropping oldest entries`() {
        val g = Graph()
        // Custom small-cap history attached to a throwaway graph.
        val shallow = History(maxDepth = 3)
        val applied = mutableListOf<String>()
        fun step(name: String) = object : Command {
            override fun redo() { applied.add("+$name") }
            override fun undo() { applied.add("-$name") }
        }

        shallow.apply(step("a"))
        shallow.apply(step("b"))
        shallow.apply(step("c"))
        shallow.apply(step("d"))   // a should be evicted here

        // We can undo 3 steps (d, c, b) but not a — a was evicted.
        shallow.undo(); shallow.undo(); shallow.undo()
        assertFalse(shallow.canUndo)
        assertEquals(listOf("+a", "+b", "+c", "+d", "-d", "-c", "-b"), applied)
    }
}
