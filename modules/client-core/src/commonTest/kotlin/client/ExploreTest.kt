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
    fun `with nothing selected the mode is not entered`() {
        Explore.set(view, true)
        assertFalse(Explore.isOn(view))
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
