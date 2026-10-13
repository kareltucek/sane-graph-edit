@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A room 4 m wide (x from -2 to 2) and 5 m deep (z from -3 to 2), its walls in no order and turned either way. */
class WallStripTest {
    private val walls = listOf(
        Wall(Vector2(2, 2), Vector2(-2, 2)),
        Wall(Vector2(2, -3), Vector2(-2, -3)),
        Wall(Vector2(2, -3), Vector2(2, 2)),
        Wall(Vector2(-2, 2), Vector2(-2, -3)),
    )
    private val strip = WallStrip.of(walls, inside = Vector2(0.5, 0.0), ahead = Vector2(0, -1))!!

    private fun near(expected: Vector2, got: Vector2) = assertTrue((expected - got).length() < 1e-9, "expected $expected, got $got")

    @Test
    fun `the strip starts straight ahead and goes to the right`() {
        assertEquals(18.0, strip.length)
        near(Vector2(0.5, -3.0), strip.at(0.0).at)
        near(Vector2(1.5, -3.0), strip.at(1.0).at)
        near(Vector2(-0.5, -3.0), strip.at(-1.0).at)
        near(Vector2(0, 1), strip.at(0.0).out)
    }

    @Test
    fun `it goes around the corners, wall after wall, and around again`() {
        near(Vector2(2, -2), strip.at(2.5).at)
        near(Vector2(-1, 0), strip.at(2.5).out)
        near(Vector2(1, 2), strip.at(7.5).at)
        near(Vector2(0, -1), strip.at(7.5).out)
        near(Vector2(-2, -2), strip.at(-3.5).at)
        near(Vector2(1, 0), strip.at(-3.5).out)
        near(strip.at(1.0).at, strip.at(19.0).at)
        near(strip.at(-1.0).at, strip.at(-37.0).at)
    }

    @Test
    fun `with no wall ahead it starts at the nearest, with no walls there is none`() {
        val one = WallStrip.of(listOf(Wall(Vector2(-1, 3), Vector2(1, 3))), inside = Vector2(0, 0), ahead = Vector2(0, -1))!!
        near(Vector2(0, 3), one.at(0.0).at)
        near(Vector2(-0.5, 3.0), one.at(0.5).at)
        assertNull(WallStrip.of(emptyList(), Vector2(0, 0), Vector2(0, -1)))
    }
}
