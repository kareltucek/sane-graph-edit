package graph_tools

import utils.Vector2
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Things at places of the plane, kept so that those near a place
 * are found without looking at all of them: which are within a
 * distance of it ([within]), which few are nearest to it
 * ([nearest]).
 *
 * A grid: the plane cut into square cells of one size, each thing
 * in the cell its place is in, the cells that have something kept
 * by their row and column. Looking near a place is looking into the
 * cells around it. It is made for the places the things have when
 * it is made and does not follow them: one that moved is found
 * where it was. Making it costs as much as there are things, so for
 * things that all move (a pass of the layout optimizer) it is made
 * anew each time.
 *
 * Why a grid and not a tree: the nodes of a graph are of much the
 * same size and spread without great holes, which is what a grid is
 * good at; it is made in one go over the things, where a tree would
 * have to be kept balanced or built with sorting; and what is asked
 * here is always "near this place", never "in this odd shape".
 * Where it does badly is things in clumps very far apart with
 * [cell] chosen for the clumps: [nearest] from between the clumps
 * then walks over many empty cells, though never beyond those that
 * have something.
 *
 * [cell] is best about as big as the distances asked for: much
 * smaller and a look goes through many cells, much bigger and a cell
 * holds many things that are then all looked at.
 */
class SpatialIndex<T>(things: Collection<T>, private val cell: Double, private val at: (T) -> Vector2) {
    private val cells: Map<Pair<Int, Int>, List<T>> = things.groupBy { cellOf(at(it)) }

    private companion object {
        /** [nearest] looks this many rings of cells out before it gives up on the grid and looks at everything. */
        const val MOST_RINGS = 24
    }

    /** The rows and columns that have anything: nothing is looked for beyond them. */
    private val columns = cells.keys.minOfOrNull { it.first }?.let { it..cells.keys.maxOf { c -> c.first } } ?: IntRange.EMPTY
    private val rows = cells.keys.minOfOrNull { it.second }?.let { it..cells.keys.maxOf { c -> c.second } } ?: IntRange.EMPTY

    /** How many things the cell of a thing has, on average over the things: where they are in clumps, more than over the cells. */
    val crowding: Double = cells.values.sumOf { it.size.toDouble() * it.size } / things.size.coerceAtLeast(1)

    private fun cellOf(p: Vector2): Pair<Int, Int> = floor(p.x / cell).toInt() to floor(p.y / cell).toInt()

    /** The things in the cells [ring] cells away from the cell [of]: the cell itself for 0, the 8 around it for 1, and so on outwards. */
    private fun around(of: Pair<Int, Int>, ring: Int): List<T> {
        val (cx, cy) = of
        val xs = (cx - ring..cx + ring).filter { it in columns }
        val ys = (cy - ring..cy + ring).filter { it in rows }
        // the top and the bottom row of the ring whole, of the rows between only the two ends
        val edge = { y: Int -> y == cy - ring || y == cy + ring }
        return ys.flatMap { y -> (if (edge(y)) xs else xs.filter { it == cx - ring || it == cx + ring }).flatMap { x -> cells[x to y].orEmpty() } }
    }

    /** The things no further from [p] than [radius], in no order. */
    fun within(p: Vector2, radius: Double): List<T> {
        val reach = ceil(radius / cell).coerceAtMost(Int.MAX_VALUE / 4.0).toInt()
        val (cx, cy) = cellOf(p)
        val xs = maxOf(cx - reach, columns.first)..minOf(cx + reach, columns.last)
        val ys = maxOf(cy - reach, rows.first)..minOf(cy + reach, rows.last)
        val squared = radius * radius
        val near = { thing: T -> (at(thing) - p).lengthSquared() <= squared }
        // asked so wide that there are more cells to look into than cells that have anything: those are gone through instead
        val many = (xs.last - xs.first + 1).toLong() * (ys.last - ys.first + 1) > cells.size
        return when {
            xs.isEmpty() || ys.isEmpty() -> emptyList()
            many -> cells.filterKeys { it.first in xs && it.second in ys }.values.flatMap { it.filter(near) }
            else -> xs.flatMap { x -> ys.flatMap { y -> cells[x to y].orEmpty().filter(near) } }
        }
    }

    /**
     * The [count] things nearest to [p], the nearest first; fewer when there are fewer. [but] is left out: the thing asked from, when it is one of them.
     * Of those further than [radius], some may be given and some not: the look stops there.
     */
    fun nearest(p: Vector2, count: Int, but: T? = null, radius: Double = Double.POSITIVE_INFINITY): List<T> {
        val from = cellOf(p)
        // rings beyond this have nothing
        val last = if (cells.isEmpty()) -1 else maxOf(from.first - columns.first, columns.last - from.first, from.second - rows.first, rows.last - from.second)
        val far = { of: List<T> -> of.filter { it != but }.map { it to (at(it) - p).length() } }
        tailrec fun look(ring: Int, found: List<Pair<T, Double>>): List<Pair<T, Double>> {
            val now = (found + far(around(from, ring))).sortedBy { it.second }.take(count)
            // what is in a ring further out is further from [p] than this ring is wide: nothing there is nearer than what was found
            val enough = now.size == count && now.last().second <= ring * cell
            return when {
                enough || ring >= last || ring * cell >= radius -> now
                // asked from far from everything, or for nearly everything: rather than going on ring by ring, all within [radius] are looked at
                ring >= MOST_RINGS -> far(within(p, radius)).sortedBy { it.second }.take(count)
                else -> look(ring + 1, now)
            }
        }
        return if (count <= 0 || last < 0) emptyList() else look(0, emptyList()).map { it.first }
    }
}
