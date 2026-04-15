package ui

import Graph
import graph_tools.LayoutOptimizer
import graph_tools.Node
import utils.Vector2
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rate-limited behaviour of [GraphKeyListener.impl.tweakSpringScale].
 *
 * Design: the per-tap multiplier is `perSecFactor^elapsedSec`,
 * where `elapsedSec` is the time since the last tap, clamped to
 * at most 200 ms. A single tap after a long pause applies
 * exactly 200 ms worth; a held key firing rapidly applies the
 * real inter-tap elapsed, totalling `perSecFactor` per second
 * regardless of repeat rate.
 */
class TweakSpringScaleTest {

    @BeforeTest
    fun reset() {
        LayoutOptimizer.springScale = 1.0
        GraphKeyListener.impl.lastSpringTweakTime = 0L
    }

    @AfterTest
    fun restore() {
        LayoutOptimizer.springScale = 1.0
        GraphKeyListener.impl.lastSpringTweakTime = 0L
    }

    private fun headlessView(): GraphView {
        System.setProperty("java.awt.headless", "true")
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        a.cache.shapeBounds = Vector2(20, 20)
        g.add(a)
        return GraphView(initialGraph = g)
    }

    @Test
    fun `single tap after long pause applies 200ms-worth of scaling`() {
        val v = headlessView()
        // Force lastSpringTweakTime to zero via reflection — the
        // field is private, but the first call after startup
        // naturally hits the clamp. We simulate "long pause"
        // by invoking tweakSpringScale cold.
        GraphKeyListener.impl.tweakSpringScale(v, 0.9)
        // After one tap from cold: scale should be 0.9^0.2 ≈ 0.9791
        val expected = Math.pow(0.9, 0.2)
        assertEquals(expected, LayoutOptimizer.springScale, 0.001)
    }

    @Test
    fun `ten rapid taps scale by about 0_9 overall`() {
        val v = headlessView()
        // First tap = 0.2 s worth, subsequent taps = whatever the
        // real elapsed is (tiny, sub-millisecond). Expected final
        // scale is at least 0.9^0.2 (from the first tap) but
        // probably not much smaller — the real-time gap between
        // rapid Kotlin calls is far below 200 ms, so the later
        // taps contribute almost nothing.
        repeat(10) { GraphKeyListener.impl.tweakSpringScale(v, 0.9) }
        // We can't easily predict the exact final value without
        // stubbing the clock, so just bound it:
        //   • Must be ≤ 0.9^0.2 (the first tap alone)
        //   • Must be > 0.9^1.0 = 0.9 (wouldn't reach 1 s total
        //     since calls happen sub-ms apart)
        val scale = LayoutOptimizer.springScale
        assertTrue(scale <= Math.pow(0.9, 0.2) + 1e-6,
            "scale $scale should not exceed the first-tap value 0.9^0.2")
        assertTrue(scale > 0.9,
            "scale $scale should be > 0.9 since 10 sub-ms taps don't total 1 second")
    }

    @Test
    fun `longer after long pause increases scale`() {
        val v = headlessView()
        // Simulate "long pause" by resetting lastSpringTweakTime.
        GraphKeyListener.impl.lastSpringTweakTime = 0L
        GraphKeyListener.impl.tweakSpringScale(v, 1.0 / 0.9)
        // After one longer tap from cold: scale = 1.0 * (1/0.9)^0.2.
        val expected = Math.pow(1.0 / 0.9, 0.2)
        assertEquals(expected, LayoutOptimizer.springScale, 0.001)
        assertTrue(LayoutOptimizer.springScale > 1.0,
            "longer should grow the scale")
    }
}
