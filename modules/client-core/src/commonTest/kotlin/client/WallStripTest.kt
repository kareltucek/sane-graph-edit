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
    fun `a graph on the walls - its middle straight ahead at the height of the eyes, right is right, down is down`() {
        val on = OnWalls.of(strip, Vector2(-1000, -500), Vector2(1000, 500), fitted = true)
        assertEquals(1.0, on.scale)
        val middle = on.place(Point3(0.0, 0.0, 0.0))
        near(Vector2(0.5, -3 + OnWalls.OFF_WALL), Vector2(middle.x, middle.z))
        assertEquals(OnWalls.EYES, middle.y)
        val aside = on.place(Point3(1000.0, 200.0, -100.0))
        near(Vector2(1.5, -3 + OnWalls.OFF_WALL + 0.1), Vector2(aside.x, aside.z))
        assertTrue(kotlin.math.abs(aside.y - (OnWalls.EYES - 0.2)) < 1e-9)
        // around the corner, on the wall to the right
        val far = on.place(Point3(2500.0, 0.0, 0.0))
        near(Vector2(2 - OnWalls.OFF_WALL, -2.0), Vector2(far.x, far.z))
        near(Vector2(-1, 0), far.out)
    }

    @Test
    fun `a place in the room is a place of the graph again - on the nearest wall, on the lap of the strip the node was on`() {
        val on = OnWalls.of(strip, Vector2(-1000, -500), Vector2(1000, 500), fitted = true)
        listOf(Point3(0.0, 0.0, 0.0), Point3(1000.0, 200.0, 0.0), Point3(2500.0, -300.0, 0.0), Point3(-4000.0, 100.0, 0.0), Point3(20500.0, 0.0, 0.0)).forEach { at ->
            val place = on.place(at)
            val back = on.unplace(place.x, place.y, place.z, at)
            assertTrue(kotlin.math.abs(back.x - at.x) < 1e-6 && kotlin.math.abs(back.y - at.y) < 1e-6, "$at came back as $back")
        }
        // let go of in the middle of the room, nearer to the wall on the right (x = 2) than to the one ahead: it is put on that one
        val moved = on.unplace(1.5, 1.0, 0.0, Point3(0.0, 0.0, 0.0))
        val there = on.place(moved)
        near(Vector2(2 - OnWalls.OFF_WALL, 0.0), Vector2(there.x, there.z))
        assertTrue(kotlin.math.abs(there.y - 1.0) < 1e-9)
    }

    @Test
    fun `a graph too big for the walls is made smaller to fit, but not when asked as it is`() {
        val wide = OnWalls.of(strip, Vector2(0, 0), Vector2(36000, 100), fitted = true)
        assertEquals(0.5, wide.scale)
        val high = OnWalls.of(strip, Vector2(0, 0), Vector2(100, 6400), fitted = true)
        assertEquals(0.25, high.scale)
        assertEquals(1.0, OnWalls.of(strip, Vector2(0, 0), Vector2(36000, 6400), fitted = false).scale)
    }

    @Test
    fun `with no wall ahead it starts at the nearest, with no walls there is none`() {
        val one = WallStrip.of(listOf(Wall(Vector2(-1, 3), Vector2(1, 3))), inside = Vector2(0, 0), ahead = Vector2(0, -1))!!
        near(Vector2(0, 3), one.at(0.0).at)
        near(Vector2(-0.5, 3.0), one.at(0.5).at)
        assertNull(WallStrip.of(emptyList(), Vector2(0, 0), Vector2(0, -1)))
    }
}
