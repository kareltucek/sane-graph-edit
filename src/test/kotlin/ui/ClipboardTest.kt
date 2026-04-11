package ui

import Graph
import graph_tools.Edge
import graph_tools.GraphTools
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ClipboardTest {

    /**
     * Replicates `copySelection` without touching any Swing
     * components — the test suite does not spin up a window.
     */
    private fun fragmentOf(g: Graph, sel: List<Node>): NodeFragment {
        val nodeMap = sel.associateWith { it.deepClone() }
        val edges = g.edges
            .filter { it.src in nodeMap && it.dst in nodeMap }
            .map { Edge(nodeMap[it.src]!!, nodeMap[it.dst]!!) }
        val reference = GraphTools.computeCenterOfMass(sel)
        return NodeFragment(
            nodes = nodeMap.values.toList(),
            edges = edges,
            referencePoint = reference,
        )
    }

    @Test
    fun `deepClone produces an independent node`() {
        val a = Node("hello", Vector2(10, 20))
        a.attributes.other["custom"] = "value"
        val b = a.deepClone()

        assertNotSame(a, b)
        assertEquals(a.position, b.position)
        assertEquals(a.attributes.text, b.attributes.text)
        assertEquals("value", b.attributes.other["custom"])

        // Mutating the clone must not touch the source.
        b.attributes.text = "changed"
        assertEquals("hello", a.attributes.text)
    }

    @Test
    fun `copy captures edges where both endpoints are selected`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(100, 0))
        val c = Node("c", Vector2(200, 0))
        g.add(a); g.add(b); g.add(c)
        val eAB = Edge(a, b)
        val eBC = Edge(b, c)
        g.add(eAB); g.add(eBC)

        // Copy only a and b — the b→c edge should be dropped.
        val frag = fragmentOf(g, listOf(a, b))

        assertEquals(2, frag.nodes.size)
        assertEquals(1, frag.edges.size)
        val edge = frag.edges.single()
        assertTrue(edge.src in frag.nodes)
        assertTrue(edge.dst in frag.nodes)
    }

    @Test
    fun `fragment nodes are decoupled from the source graph`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        g.add(a)

        val frag = fragmentOf(g, listOf(a))
        val clone = frag.nodes.single()

        // Removing a from the source must not affect the fragment.
        g.remove(a)
        assertFalse(g.nodes.contains(a))
        assertEquals("a", clone.attributes.text)
        assertEquals(Vector2(0, 0), clone.position)
    }

    @Test
    fun `fragment edges reference fragment nodes, not source nodes`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(100, 0))
        g.add(a); g.add(b); g.add(Edge(a, b))

        val frag = fragmentOf(g, listOf(a, b))
        val cloneA = frag.nodes.first { it.attributes.text == "a" }
        val cloneB = frag.nodes.first { it.attributes.text == "b" }
        val edge = frag.edges.single()

        // The edge must point at the cloned nodes, not the originals.
        assertNotSame(a, edge.src)
        assertNotSame(b, edge.dst)
        assertTrue(edge.src === cloneA)
        assertTrue(edge.dst === cloneB)
    }

    @Test
    fun `referencePoint is the selection center-of-mass`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(100, 0))
        val c = Node("c", Vector2(50, 150))
        g.add(a); g.add(b); g.add(c)

        val frag = fragmentOf(g, listOf(a, b, c))
        // (0+100+50)/3, (0+0+150)/3 = 50, 50
        assertEquals(Vector2(50.0, 50.0), frag.referencePoint)
    }
}
