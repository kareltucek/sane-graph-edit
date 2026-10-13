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

    /**
     * The other way around: where in the graph a node is that was at [was] there and is put at [x], [y], [z] of the
     * room: on the wall nearest to that, as high as it was put. How far it stands out of the wall stays as it was.
     */
    fun unplace(x: Double, y: Double, z: Double, was: Point3): Point3 {
        val along = strip.along(Vector2(x, z), near = (was.x - middle.x) * scale * METRES_PER_UNIT)
        return Point3(middle.x + along / (scale * METRES_PER_UNIT), middle.y + (EYES - y) / (scale * METRES_PER_UNIT), was.z)
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
         * "As big as it is" is [atMost] millimetres to a unit (`:set wall-scale`).
         */
        fun of(strip: WallStrip, from: Vector2, to: Vector2, fitted: Boolean, atMost: Double = 1.0): OnWalls {
            val size = (to - from) * METRES_PER_UNIT
            val fits = listOf(atMost, strip.length / size.x.coerceAtLeast(1e-9), BAND / size.y.coerceAtLeast(1e-9)).min()
            return OnWalls(strip, (from + to) / 2, if (fitted) fits else atMost)
        }
    }
}
