package graph_tools

import Graph
import utils.Vector2
import kotlin.math.floor

/**
 * The springs of [LayoutOptimizer] on a few nodes only, until they
 * rest: for nodes that were just put somewhere
 * ([DynamicPlacement]) and those that are in their way.
 *
 * Only the nodes given move, and with them every node they run
 * into that may give way (it joins them, and so on from it); all
 * else stays where it is and is pushed off. Of the springs only the
 * pushing one is used, and with a small gap ([APART]): the nodes
 * get out of each other's way and are otherwise left where they
 * were put (the pull of the edges would gather a column of
 * children into a clump around their parent).
 * Which are near is looked up in a grid, so a step costs as much as
 * there are nodes seen, not that squared. Each step moves a node a
 * part of the way the springs ask for, a smaller part each time, so
 * that two nodes pushing each other do not swing.
 *
 * One very big node makes the cells of the grid as big, and the
 * looking up slow.
 */
object Settling {
    private const val MAX_STEPS = 150

    /** The part of what the springs ask for that the first step moves. */
    private const val DAMPING = 0.5

    /** By how much that part shrinks with each step. */
    private const val COOLING = 0.98

    /** Nothing moved further than this in a step: the nodes rest. */
    private const val SETTLED = 0.5

    /** How far apart nodes are kept, in times the distance at which they touch. */
    private const val APART = 1.25

    private data class Step(val moving: Set<Node>, val furthest: Double)

    /**
     * Move [fresh], and of [among] (the nodes seen, [fresh] too)
     * those in their way that [yields] says may move, until they
     * rest; a node of [fresh] itself moves only off what may not.
     * The nodes are moved in place; those that took part are
     * returned.
     */
    fun settle(g: Graph, fresh: Set<Node>, among: Set<Node>, yields: (Node) -> Boolean): Set<Node> {
        val cell = (among.maxOfOrNull { it.cache.shapeBounds.length() } ?: 1.0).coerceAtLeast(1.0) * APART
        tailrec fun run(moving: Set<Node>, damping: Double, left: Int): Set<Node> {
            val done = step(g, fresh, moving, among, yields, cell, damping)
            val rests = done.furthest < SETTLED && done.moving.size == moving.size
            return if (rests || left <= 1) done.moving else run(done.moving, damping * COOLING, left - 1)
        }
        return if (fresh.isEmpty()) fresh else run(fresh, DAMPING, MAX_STEPS)
    }

    /** What [LayoutOptimizer.impl.computeCollisionSpring] is asked for so that it keeps the nodes [APART]: it has a factor of its own. */
    private fun strength(): Double = APART / (2.0 * LayoutOptimizer.springScale).coerceAtLeast(1.0)

    private fun step(
        g: Graph,
        fresh: Set<Node>,
        moving: Set<Node>,
        among: Set<Node>,
        yields: (Node) -> Boolean,
        cell: Double,
        damping: Double,
    ): Step {
        val cellOf = { p: Vector2 -> floor(p.x / cell).toInt() to floor(p.y / cell).toInt() }
        val grid = among.groupBy { cellOf(it.position) }
        val near = { n: Node ->
            val (cx, cy) = cellOf(n.position)
            (-1..1).flatMap { dx -> (-1..1).flatMap { dy -> grid[cx + dx to cy + dy].orEmpty() } }.filter { it != n }
        }
        val strength = strength()
        val pushes = moving.flatMap { n -> near(n).mapNotNull { o -> LayoutOptimizer.impl.computeCollisionSpring(g, n, o, strength)?.let { it to o } } }
        // a fresh node holds its place against what gives way to it
        val felt = pushes.filter { (push, by) -> push.n !in fresh || !yields(by) }
        val moves = felt.map { it.first }.groupBy({ it.n }, { it.v }).mapValues { (_, vs) -> vs.reduce(Vector2::plus) / vs.size * damping }
        moves.forEach { (n, by) -> n.position = n.position + by }
        if (g.attachmentOwners.isNotEmpty()) Attachments.reglue(g)
        val woken = pushes.map { it.second }.filter { it !in moving && yields(it) }
        return Step(moving + woken, moves.values.maxOfOrNull { it.length() } ?: 0.0)
    }
}
