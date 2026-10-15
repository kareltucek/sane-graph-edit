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
 *
 * It is looked into for every node at every step of the springs, so
 * it is kept in flat arrays: the things by their cells, one after
 * another, their places beside them, and the cells in a table of
 * their own (open addressing), so that a look makes nothing new but
 * what it gives.
 */
class SpatialIndex<T>(things: Collection<T>, private val cell: Double, at: (T) -> Vector2) {
    private companion object {
        /** [nearest] looks this many rings of cells out before it gives up on the grid and looks at everything. */
        const val MOST_RINGS = 24

        fun hash(x: Int, y: Int): Int = (x * -1640531535 xor y * 805306457).let { it xor (it ushr 15) }
    }

    private val total = things.size

    /** A slot for each cell that has something, and as many again empty: the column and the row of its cell, where its things start, how many. */
    private val slots = generateSequence(4) { it * 2 }.first { it >= 2 * total }
    private val mask = slots - 1
    private val slotX = IntArray(slots)
    private val slotY = IntArray(slots)
    private val start = IntArray(slots)
    private val held = IntArray(slots)

    /** The things by their cells, and where each is. */
    private val items: Array<Any?> = arrayOfNulls(total)
    private val xs = DoubleArray(total)
    private val ys = DoubleArray(total)

    private fun column(x: Double) = floor(x / cell).toInt()

    /** The slot of the cell [x], [y]: its own, or the empty one it would have. */
    private fun slot(x: Int, y: Int): Int {
        var s = hash(x, y) and mask
        while (held[s] != 0 && (slotX[s] != x || slotY[s] != y)) s = (s + 1) and mask
        return s
    }

    /** The rows and columns that have anything: nothing is looked for beyond them. */
    private val columns: IntRange
    private val rows: IntRange

    /** How many things the cell of a thing has, on average over the things: where they are in clumps, more than over the cells. */
    val crowding: Double

    init {
        val places = things.map(at)
        val slotOf = IntArray(total)
        places.forEachIndexed { i, p ->
            val (x, y) = column(p.x) to column(p.y)
            val s = slot(x, y)
            slotX[s] = x
            slotY[s] = y
            held[s] += 1
            slotOf[i] = s
        }
        var next = 0
        (0 until slots).forEach { s ->
            start[s] = next
            next += held[s]
        }
        val filled = IntArray(slots)
        things.forEachIndexed { i, thing ->
            val s = slotOf[i]
            val to = start[s] + filled[s]
            filled[s] += 1
            items[to] = thing
            xs[to] = places[i].x
            ys[to] = places[i].y
        }
        val used = (0 until slots).filter { held[it] != 0 }
        columns = if (used.isEmpty()) IntRange.EMPTY else used.minOf { slotX[it] }..used.maxOf { slotX[it] }
        rows = if (used.isEmpty()) IntRange.EMPTY else used.minOf { slotY[it] }..used.maxOf { slotY[it] }
        crowding = used.sumOf { held[it].toDouble() * held[it] } / total.coerceAtLeast(1)
    }

    private val cells = (0 until slots).count { held[it] != 0 }

    @Suppress("UNCHECKED_CAST")
    private fun item(i: Int): T = items[i] as T

    /** [visit] each thing in the cell [x], [y], by where it is in [items]. */
    private inline fun inCell(x: Int, y: Int, visit: (Int) -> Unit) {
        val s = slot(x, y)
        (start[s] until start[s] + held[s]).forEach(visit)
    }

    /** The things no further from [p] than [radius], in no order. */
    fun within(p: Vector2, radius: Double): List<T> {
        val reach = ceil(radius / cell).coerceAtMost(Int.MAX_VALUE / 4.0).toInt()
        val (cx, cy) = column(p.x) to column(p.y)
        val xr = maxOf(cx - reach, columns.first)..minOf(cx + reach, columns.last)
        val yr = maxOf(cy - reach, rows.first)..minOf(cy + reach, rows.last)
        val squared = radius * radius
        val found = mutableListOf<T>()
        val take = { i: Int -> if ((xs[i] - p.x) * (xs[i] - p.x) + (ys[i] - p.y) * (ys[i] - p.y) <= squared) found += item(i) }
        // asked so wide that there are more cells to look into than cells that have anything: those are gone through instead
        val many = (xr.last - xr.first + 1).toLong() * (yr.last - yr.first + 1) > cells
        when {
            xr.isEmpty() || yr.isEmpty() -> Unit
            many -> (0 until slots).forEach { s -> if (held[s] != 0 && slotX[s] in xr && slotY[s] in yr) (start[s] until start[s] + held[s]).forEach(take) }
            else -> xr.forEach { x -> yr.forEach { y -> inCell(x, y, take) } }
        }
        return found
    }

    /**
     * The [count] things nearest to [p], the nearest first; fewer when there are fewer. [but] is left out: the thing asked from, when it is one of them.
     * Of those further than [radius], some may be given and some not: the look stops there.
     */
    fun nearest(p: Vector2, count: Int, but: T? = null, radius: Double = Double.POSITIVE_INFINITY): List<T> {
        val (cx, cy) = column(p.x) to column(p.y)
        // rings beyond this have nothing
        val last = if (cells == 0) -1 else maxOf(cx - columns.first, columns.last - cx, cy - rows.first, rows.last - cy)
        // the best yet, the nearest first: their squared distances, where they are in [items]
        val best = DoubleArray(count.coerceAtLeast(0))
        val which = IntArray(count.coerceAtLeast(0))
        var found = 0
        val offer = { i: Int ->
            val d = (xs[i] - p.x) * (xs[i] - p.x) + (ys[i] - p.y) * (ys[i] - p.y)
            if ((found < count || d < best[found - 1]) && items[i] != but) {
                var k = if (found < count) found++ else found - 1
                while (k > 0 && best[k - 1] > d) {
                    best[k] = best[k - 1]
                    which[k] = which[k - 1]
                    k -= 1
                }
                best[k] = d
                which[k] = i
            }
        }
        // the cells [ring] cells away: the cell itself for 0, the 8 around it for 1, and so on outwards
        val ringOf = { ring: Int ->
            val xr = maxOf(cx - ring, columns.first)..minOf(cx + ring, columns.last)
            (maxOf(cy - ring, rows.first)..minOf(cy + ring, rows.last)).forEach { y ->
                // the top and the bottom row of the ring whole, of the rows between only the two ends
                if (y == cy - ring || y == cy + ring) xr.forEach { x -> inCell(x, y, offer) }
                else {
                    if (cx - ring in xr) inCell(cx - ring, y, offer)
                    if (cx + ring in xr) inCell(cx + ring, y, offer)
                }
            }
        }
        var ring = 0
        var looking = count > 0 && last >= 0
        while (looking) {
            ringOf(ring)
            // what is in a ring further out is further from [p] than this ring is wide: nothing there is nearer than what was found
            val enough = found == count && best[count - 1] <= (ring * cell) * (ring * cell)
            looking = !(enough || ring >= last || ring * cell >= radius)
            // asked from far from everything, or for nearly everything: rather than going on ring by ring, all within [radius] are looked at
            if (looking && ring >= MOST_RINGS) {
                found = 0
                val squared = radius * radius
                (0 until total).forEach { i -> if ((xs[i] - p.x) * (xs[i] - p.x) + (ys[i] - p.y) * (ys[i] - p.y) <= squared) offer(i) }
                looking = false
            }
            ring += 1
        }
        return List(found) { item(which[it]) }
    }
}
