package client

import utils.Vector2

/** A wall of a room as seen from above, from one end to the other: metres, the room's x and z. */
data class Wall(val from: Vector2, val to: Vector2) {
    val length: Double get() = (to - from).length()
    val turned: Wall get() = Wall(to, from)
}

/** A place on a wall, seen from above, and the direction from it into the room (of length 1). */
data class OnWall(val at: Vector2, val out: Vector2)

/**
 * The walls of a room laid out after each other as one strip, so
 * that what is flat (a graph) can be put on them: a distance along
 * the strip is a place on a wall ([at]).
 *
 * The strip starts straight ahead of who stands at [inside] and
 * looks [ahead], and goes to the right from there, wall after wall
 * around the room, and on around it again: past the last wall comes
 * the first. Left of the start is the end of the strip. Doors,
 * windows and gaps between walls are not known of here.
 */
class WallStrip private constructor(private val walls: List<Wall>, private val inside: Vector2, private val start: Double) {
    /** Once around the room. */
    val length: Double = walls.sumOf { it.length }

    /** The place [along] the strip (metres from its start, to the right; any number). */
    fun at(along: Double): OnWall {
        val around = ((start + along) % length + length) % length
        val ends = walls.runningFold(0.0) { sum, w -> sum + w.length }
        val index = ends.drop(1).indexOfFirst { around < it }.let { if (it < 0) walls.lastIndex else it }
        val wall = walls[index]
        val direction = (wall.to - wall.from).toUnit()
        val at = wall.from + direction * (around - ends[index])
        return OnWall(at, into(direction, at, inside))
    }

    /**
     * How far along the strip the place on the walls nearest to [p] is: the other way around from [at]. The strip goes
     * around and around, so there are many such distances, once around apart: the one nearest to [near] is given.
     */
    fun along(p: Vector2, near: Double): Double {
        val ends = walls.runningFold(0.0) { sum, w -> sum + w.length }
        val nearest = walls.indices.minBy { i ->
            val w = walls[i]
            (w.from + (w.to - w.from).toUnit() * nearestAlong(w, p) - p).length()
        }
        val once = ((ends[nearest] + nearestAlong(walls[nearest], p) - start) % length + length) % length
        return once + kotlin.math.round((near - once) / length) * length
    }

    companion object {
        /** The direction to the right of who looks along [d], seen from above (x right, z towards the viewer of the plan). */
        private fun rightOf(d: Vector2) = Vector2(-d.y, d.x)

        /** Of the two directions across [along], the one that leads from [at] towards [inside]. */
        private fun into(along: Vector2, at: Vector2, inside: Vector2): Vector2 =
            rightOf(along).let { if (it.dot(inside - at) >= 0) it else -it }

        /** How far along [w] the point of it nearest to [p] is. */
        private fun nearestAlong(w: Wall, p: Vector2): Double =
            if (w.length == 0.0) 0.0 else ((p - w.from).dot((w.to - w.from).toUnit())).coerceIn(0.0, w.length)

        /** Where a look from [from] in the direction [ahead] meets [w], as a distance from [from]; null when it does not. */
        private fun met(w: Wall, from: Vector2, ahead: Vector2): Double? {
            val along = w.to - w.from
            val cross = ahead.x * along.y - ahead.y * along.x
            val to = w.from - from
            val far = if (cross == 0.0) null else (to.x * along.y - to.y * along.x) / cross
            val part = if (cross == 0.0) null else (to.x * ahead.y - to.y * ahead.x) / cross
            return if (far != null && part != null && far > 0 && part in 0.0..1.0) far else null
        }

        /** [w] so that it goes to the right for who looks at it from [inside]. */
        private fun rightwards(w: Wall, inside: Vector2): Wall {
            val middle = (w.from + w.to) / 2
            return if ((w.to - w.from).dot(rightOf(middle - inside)) >= 0) w else w.turned
        }

        /** [walls] after each other from [first] on: each next one is the one that begins nearest to where the last ended. */
        private tailrec fun chained(done: List<Wall>, left: List<Wall>): List<Wall> =
            if (left.isEmpty()) done
            else {
                val end = done.last().to
                val next = left.flatMap { listOf(it, it.turned) }.minBy { (it.from - end).length() }
                chained(done + next, left.filter { it != next && it.turned != next })
            }

        /** The strip of [walls] for who stands at [inside] and looks [ahead]; null of no walls. */
        fun of(walls: List<Wall>, inside: Vector2, ahead: Vector2): WallStrip? {
            val real = walls.filter { it.length > 0 }
            val first = real.mapNotNull { w -> met(w, inside, ahead)?.let { w to it } }.minByOrNull { it.second }?.first
                ?: real.minByOrNull { ((it.from + it.to) / 2 - inside).length() }
            return first?.let {
                val begun = rightwards(it, inside)
                val looked = met(begun, inside, ahead)?.let { far -> nearestAlong(begun, inside + ahead * far) } ?: nearestAlong(begun, inside)
                WallStrip(chained(listOf(begun), real.filter { w -> w != it }), inside, looked)
            }
        }
    }
}
