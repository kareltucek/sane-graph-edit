package graph_tools

import Graph
import utils.Vector2
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SpringScaleTest {

    @BeforeTest
    fun resetScale() {
        // The scale is a global singleton — reset between tests
        // so parallel/sequence behaviour is deterministic.
        LayoutOptimizer.springScale = 1.0
    }

    @AfterTest
    fun restoreScale() {
        LayoutOptimizer.springScale = 1.0
    }

    @Test
    fun `springScale defaults to 1`() {
        assertEquals(1.0, LayoutOptimizer.springScale)
    }

    @Test
    fun `springScale can be set directly`() {
        LayoutOptimizer.springScale = 1.5
        assertEquals(1.5, LayoutOptimizer.springScale)
    }

    @Test
    fun `computeBBSpring uses springScale in distance factor`() {
        // Build a minimal graph with populated caches.
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(100, 0))
        a.cache.shapeBounds = Vector2(20, 20)
        b.cache.shapeBounds = Vector2(20, 20)
        g.add(a); g.add(b)
        val e = Edge(a, b)
        g.add(e)  // Plotter.recomputeEdges runs via addAllEdges

        LayoutOptimizer.springScale = 1.0
        val baseline = LayoutOptimizer.impl.computeBBSpring(g, a, b, e).v

        LayoutOptimizer.springScale = 2.0
        val scaled = LayoutOptimizer.impl.computeBBSpring(g, a, b, e).v

        // The actual invariant: different springScale values
        // produce different spring vectors. Whether the magnitude
        // grows or shrinks for a given node pair depends on
        // whether the current distance is above or below the
        // desired target. Just assert the scale change had a
        // measurable effect.
        val diff = (baseline - scaled).length()
        assertEquals(true, diff > 0.0 && diff.isFinite(),
            "expected spring vector to differ between scales; baseline=$baseline scaled=$scaled")
    }

    @Test
    fun `springScale persists across successive optimize calls`() {
        // Applying a scale once should keep the value set for
        // subsequent calls — that's the whole "persistent" part
        // of the feature.
        LayoutOptimizer.springScale = 0.7
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        a.cache.shapeBounds = Vector2(20, 20)
        g.add(a)
        LayoutOptimizer.optimize(g, movingNodes = false, restrictOperator = false)
        assertEquals(0.7, LayoutOptimizer.springScale)
    }
}
