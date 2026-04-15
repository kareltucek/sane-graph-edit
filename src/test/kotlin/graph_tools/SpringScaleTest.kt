package graph_tools

import Graph
import utils.Vector2
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `computeCollisionSpring scales with springScale`() {
        // Build two close-enough-to-collide nodes. Baseline
        // scale produces some repulsion vector; a smaller scale
        // should relax the collision (let them be closer), and
        // at very small scale the collision should vanish
        // entirely (below the touching floor of 1.0 × radii).
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(50, 0))  // close: likely to collide
        a.cache.shapeBounds = Vector2(40, 40)
        b.cache.shapeBounds = Vector2(40, 40)
        g.add(a); g.add(b)

        LayoutOptimizer.springScale = 1.0
        val baselineSpring = LayoutOptimizer.impl.computeCollisionSpring(g, a, b)

        LayoutOptimizer.springScale = 0.4  // below the 0.5 floor
        val tightSpring = LayoutOptimizer.impl.computeCollisionSpring(g, a, b)

        // At scale=1.0 the nodes are within the baseline
        // desired-distance (40/2 + 40/2 = 40 per radius, *2 = 80
        // desired, current 50 < 80 → collision force produced).
        assertTrue(baselineSpring != null,
            "expected collision force at baseline scale")
        // At scale=0.4, distanceCf clamps to max(0.8, 1.0) = 1.0,
        // so desired distance is 40 (sum of radii). Current 50 >
        // 40 → no collision. Force should be null.
        assertTrue(tightSpring == null,
            "expected no collision force once scale is small enough to let nodes touch")
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
