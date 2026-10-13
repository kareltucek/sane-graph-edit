package client

import utils.Vector2

/**
 * A graph put on the walls of a room (`:set walls`): flat as it
 * is, bent around the room along its walls ([WallStrip]). Only how
 * it is shown where there is a room, in a headset: the nodes are
 * where they were in the graph.
 *
 * The point [middle] of the graph is on the wall straight ahead, at
 * the height of the eyes; what is right of it in the graph is to
 * the right along the walls, what is below it lower. A unit of the
 * graph is [scale] millimetres. What is nearer than the graph's
 * plane stands out of the wall.
 */
class OnWalls(private val strip: WallStrip, private val middle: Vector2, val scale: Double) {
    /** A place in the room (metres, y up from the floor) and the direction out of the wall there, seen from above. */
    data class Place(val x: Double, val y: Double, val z: Double, val out: Vector2)

    fun place(at: Point3): Place {
        val on = strip.at((at.x - middle.x) * scale * METRES_PER_UNIT)
        val off = OFF_WALL + (-at.z).coerceAtLeast(0.0) * scale * METRES_PER_UNIT
        val stands = on.at + on.out * off
        return Place(stands.x, EYES - (at.y - middle.y) * scale * METRES_PER_UNIT, stands.y, on.out)
    }

    companion object {
        /** A unit of the graph in a room, as the pages have it (`room.ts`). */
        const val METRES_PER_UNIT = 0.001

        /** How high the middle of the graph is, and how much of a wall's height a graph is given. */
        const val EYES = 1.4
        const val BAND = 1.6

        /** How far from the wall the graph's plane is. */
        const val OFF_WALL = 0.1

        /**
         * The graph whose nodes are within [from] and [to] on [strip]:
         * [fitted], so much smaller that all of it is seen, once around
         * the room at most and [BAND] high; otherwise as big as it is,
         * going around again over itself when it is longer than the walls.
         */
        fun of(strip: WallStrip, from: Vector2, to: Vector2, fitted: Boolean): OnWalls {
            val size = (to - from) * METRES_PER_UNIT
            val fits = listOf(1.0, strip.length / size.x.coerceAtLeast(1e-9), BAND / size.y.coerceAtLeast(1e-9)).min()
            return OnWalls(strip, (from + to) / 2, if (fitted) fits else 1.0)
        }
    }
}
