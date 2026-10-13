@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import graph_tools.Edge
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Explore mode. The graph is a chain with a side branch,
 * `up -> a -> b -> c` and `a -> side`, and a second way into the
 * chain, `other -> b`.
 */
class ExploreTest {
    private val up = Node("up", Vector2(100, 100))
    private val a = Node("a", Vector2(300, 100))
    private val b = Node("b", Vector2(500, 100))
    private val c = Node("c", Vector2(700, 100))
    private val side = Node("side", Vector2(500, 300))
    private val other = Node("other", Vector2(300, 500))
    private val all = setOf(up, a, b, c, side, other)

    private val view = EditorView(
        Graph().also { g ->
            all.forEach { g.add(it) }
            listOf(up to a, a to b, b to c, a to side, other to b).forEach { (from, to) -> g.add(Edge(from, to)) }
        },
        NoRenderer,
        NoShell,
    ).also {
        it.size = Vector2(800, 600)
        it.g.ensureCachesFresh()
        it.g.unselectAll(it.g.nodes.toList())
        it.g.lastActiveNode = null
    }
    private val g = view.g

    private fun select(vararg nodes: Node) {
        g.unselectAll(g.nodes.toList())
        nodes.forEach { g.select(it) }
    }

    private fun faint() = g.nodes.filter { g.isFaint(it) }.toSet()

    @Test
    fun `the mode starts from the selection and shows its neighbours faint`() {
        select(a)
        Explore.set(view, true)
        assertEquals(setOf(a), g.pinned)
        assertEquals(setOf(up, a, b, side), g.visibleNodes())
        assertEquals(setOf(up, b, side), faint())
    }

    @Test
    fun `entering the mode puts the faint nodes next to the pinned ones, which stay`() {
        select(a)
        Explore.set(view, true)
        graph_tools.DynamicPlacement.place(g)
        assertEquals(Vector2(300, 100), a.position)
        assertTrue(up.position.x < a.position.x && b.position.x > a.position.x && side.position.x > a.position.x)
        assertTrue(listOf(up, b, side).all { kotlin.math.abs(it.position.y - a.position.y) < 100 }, "level with a, about")
        assertEquals(Vector2(700, 100), c.position)
        g.history.undo()
        assertEquals(Vector2(500, 300), side.position)
    }

    @Test
    fun `several nodes to start from are put together in columns, in the order they were in, around the middle of the view`() {
        // far apart in the graph
        up.position = Vector2(-9000, 50)
        other.position = Vector2(7000, 4000)
        select(up, other, c)
        Explore.set(view, true)
        val middle = view.viewTransform.toWorkspace(view.size / 2)
        assertEquals(setOf(up, other, c), g.pinned)
        // one column: the same x, top to bottom as they were (up, c, other), near the middle of the view
        assertEquals(up.position.x, c.position.x)
        assertEquals(c.position.x, other.position.x)
        assertTrue(up.position.y < c.position.y && c.position.y < other.position.y)
        assertTrue(listOf(up, c, other).all { (it.position - middle).length() < 500 }, "near the middle")
        g.history.undo()
        assertEquals(Vector2(-9000, 50), up.position)
    }

    @Test
    fun `more nodes to start from than a column holds go on in the next one`() {
        val many = (0 until 60).map { Node("n$it", Vector2(0, 100 * it)) }
        many.forEach { g.add(it) }
        g.ensureCachesFresh()
        val to = Explore.arrangement(g, many.toSet(), Vector2(0, 0))
        val columns = to.values.map { it.x }.distinct().sorted()
        assertTrue(columns.size > 1, "several columns")
        assertTrue(to.values.maxOf { it.y } - to.values.minOf { it.y } <= 1500.0)
        // the first column has the first of them, the next the following ones
        assertTrue(to.getValue(many.first()).x == columns.first() && to.getValue(many.last()).x == columns.last())
        assertTrue(Explore.arrangement(g, setOf(many.first()), Vector2(0, 0)).isEmpty(), "one node alone stays")
    }

    @Test
    fun `with nothing selected the mode starts from the nodes that fewest edges come into, of each part of the graph`() {
        // a cycle of its own, beside the chain
        val round = listOf(Node("r1", Vector2(100, 700)), Node("r2", Vector2(300, 700)), Node("r3", Vector2(200, 900)))
        round.forEach { g.add(it) }
        round.indices.forEach { g.add(Edge(round[it], round[(it + 1) % 3])) }
        g.ensureCachesFresh()
        g.unselectAll(g.nodes.toList())
        Explore.set(view, true)
        assertEquals(setOf(up, other) + round, g.pinned)
    }

    @Test
    fun `a graph without nodes is not explored`() {
        val empty = EditorView(Graph(), NoRenderer, NoShell)
        Explore.set(empty, true)
        assertFalse(Explore.isOn(empty))
    }

    @Test
    fun `leaving the mode leaves the graph as it was`() {
        var steps = 0
        g.history.addListener { steps++ }
        side.cache.hideLevel = 2
        select(a)
        Explore.set(view, true)
        Explore.show(view)
        select(b)
        Explore.hide(view)
        Explore.set(view, false)
        assertNull(g.pinned)
        assertEquals(all - side, g.visibleNodes())
        assertEquals(2, side.cache.hideLevel)
        assertEquals(0, steps)
    }

    @Test
    fun `showing pins a faint node, and the children of a pinned one`() {
        select(a)
        Explore.set(view, true)
        select(b)
        Explore.show(view)
        assertEquals(setOf(a, b), g.pinned)
        select(a)
        Explore.show(view)
        assertEquals(setOf(a, b, side), g.pinned)
    }

    @Test
    fun `hiding folds all that is pinned below`() {
        select(a)
        Explore.set(view, true)
        g.pinned = setOf(up, a, b, c, side)
        Explore.hide(view)
        assertEquals(setOf(up, a), g.pinned)
    }

    @Test
    fun `what is below another pinned node too is not folded`() {
        select(a)
        Explore.set(view, true)
        g.pinned = all
        Explore.hide(view)
        assertEquals(all - side, g.pinned)
    }

    @Test
    fun `hiding a pinned node with nothing pinned below lets go of the node itself, but not of the last one`() {
        select(a)
        Explore.set(view, true)
        g.pinned = setOf(a, side)
        select(side)
        Explore.hide(view)
        assertEquals(setOf(a), g.pinned)
        select(a)
        Explore.hide(view)
        assertEquals(setOf(a), g.pinned)
    }

    @Test
    fun `escape leaves the mode and keeps the selection`() {
        select(a)
        Explore.set(view, true)
        Actions.unselectAll(view)
        assertFalse(Explore.isOn(view))
        assertEquals(setOf(a), g.selectedNodes.toSet())
        Actions.unselectAll(view)
        assertTrue(g.selectedNodes.isEmpty())
    }

    @Test
    fun `the pinned nodes are the mark !`() {
        select(a)
        Explore.set(view, true)
        select(b, c)
        Explore.mark(view, MarkChange.Add)
        assertEquals(setOf(a, b, c), g.pinned)
        select(a)
        Explore.mark(view, MarkChange.Remove)
        assertEquals(setOf(b, c), g.pinned)
        Explore.mark(view, MarkChange.Set)
        assertEquals(setOf(a), g.pinned)
        select(c)
        Explore.recall(view)
        assertEquals(setOf(a), g.selectedNodes.toSet())
    }

    @Test
    fun `hiding on a faint node changes nothing`() {
        select(a)
        Explore.set(view, true)
        select(b)
        Explore.hide(view)
        assertEquals(setOf(a), g.pinned)
        assertTrue(g.isFaint(b))
    }
}
