package graph_tools

import Graph
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The uniform-outgoing-length spring pulls the destinations of
 * sibling edges (edges sharing a source) toward the same
 * distance from their shared source.
 */
class UniformOutSpringTest {

    @Test
    fun `two siblings at different distances equalize`() {
        // Parent at origin, children at (100, 0) and (300, 0).
        // Average length = 200. Child A should be pulled right
        // (100 → 200), child B should be pulled left (300 → 200).
        val g = Graph()
        val parent = Node("P", Vector2(0, 0))
        val a = Node("A", Vector2(100, 0))
        val b = Node("B", Vector2(300, 0))
        g.add(parent); g.add(a); g.add(b)
        g.add(Edge(parent, a)); g.add(Edge(parent, b))

        val springs = LayoutOptimizer.impl.computeUniformOutSprings(
            g, LayoutOptimizer.SpringTarget.MoveEveryone,
        )

        // Expect two springs — one per destination.
        assertEquals(2, springs.size)
        val byName = springs.associateBy { it.n.attributes.text }

        // Mildness factor 0.5 halves the correction.
        // A: (200 - 100) = 100, halved = 50, direction +x.
        // B: (200 - 300) = -100, halved = -50, direction +x.
        assertEquals(50.0, byName["A"]!!.v.x, 0.001)
        assertEquals(0.0, byName["A"]!!.v.y, 0.001)
        assertEquals(-50.0, byName["B"]!!.v.x, 0.001)
        assertEquals(0.0, byName["B"]!!.v.y, 0.001)
    }

    @Test
    fun `a node with zero or one outgoing edge produces no springs`() {
        val g = Graph()
        val a = Node("A", Vector2(0, 0))
        val b = Node("B", Vector2(100, 0))
        g.add(a); g.add(b)
        g.add(Edge(a, b))

        val springs = LayoutOptimizer.impl.computeUniformOutSprings(
            g, LayoutOptimizer.SpringTarget.MoveEveryone,
        )
        assertTrue(springs.isEmpty(), "single out-edge should produce no uniform spring")
    }

    @Test
    fun `parent itself does not move`() {
        val g = Graph()
        val parent = Node("P", Vector2(0, 0))
        val a = Node("A", Vector2(100, 0))
        val b = Node("B", Vector2(300, 0))
        g.add(parent); g.add(a); g.add(b)
        g.add(Edge(parent, a)); g.add(Edge(parent, b))

        val springs = LayoutOptimizer.impl.computeUniformOutSprings(
            g, LayoutOptimizer.SpringTarget.MoveEveryone,
        )
        val parentSpring = springs.firstOrNull { it.n === parent }
        assertTrue(parentSpring == null, "the source/parent should not get a spring")
    }

    @Test
    fun `MoveSelectedOnly skips unselected destinations`() {
        val g = Graph()
        val parent = Node("P", Vector2(0, 0))
        val a = Node("A", Vector2(100, 0))
        val b = Node("B", Vector2(300, 0))
        g.add(parent); g.add(a); g.add(b)
        g.add(Edge(parent, a)); g.add(Edge(parent, b))
        // Select only A. Under MoveSelectedOnly, only A should
        // get a uniform spring; B is anchored.
        g.cleanSelect(setOf(a))

        val springs = LayoutOptimizer.impl.computeUniformOutSprings(
            g, LayoutOptimizer.SpringTarget.MoveSelectedOnly,
        )

        assertEquals(1, springs.size)
        assertTrue(springs.single().n === a,
            "only the selected destination should get a spring")
    }

    @Test
    fun `siblings at non-axis angles equalize along their rays`() {
        // Three children at different radii and angles. Each
        // should be pulled toward the average radius along its
        // own radial direction.
        val g = Graph()
        val p = Node("P", Vector2(0, 0))
        val a = Node("A", Vector2(100, 0))       // radius 100, angle 0
        val b = Node("B", Vector2(0, 200))       // radius 200, angle 90
        val c = Node("C", Vector2(-300, 0))      // radius 300, angle 180
        g.add(p); g.add(a); g.add(b); g.add(c)
        g.add(Edge(p, a)); g.add(Edge(p, b)); g.add(Edge(p, c))

        val springs = LayoutOptimizer.impl.computeUniformOutSprings(
            g, LayoutOptimizer.SpringTarget.MoveEveryone,
        )
        val byName = springs.associateBy { it.n.attributes.text }
        // Average radius = (100 + 200 + 300) / 3 = 200. Halved
        // correction.
        // A at (100, 0): target (200, 0), diff = (100, 0), halved = (50, 0)
        // B at (0, 200): target (0, 200), diff = (0, 0), halved = (0, 0)
        // C at (-300, 0): target (-200, 0), diff = (100, 0), halved = (50, 0)
        assertEquals(50.0, byName["A"]!!.v.x, 0.001)
        assertEquals(0.0, byName["B"]!!.v.length(), 0.001)
        assertEquals(50.0, byName["C"]!!.v.x, 0.001)
    }
}
