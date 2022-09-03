package graph_tools

import Graph
import graph_tools.LayoutOptimizer.impl.computeBBSprings
import graph_tools.LayoutOptimizer.impl.computeCollisionSprings
import graph_tools.LayoutOptimizer.impl.computeGravitySprings
import ui.Utils
import ui.Utils.fold
import ui.Utils.letIf
import ui.Utils.orElse
import utils.Vector2
import utils.Vector2.Companion.Zero

/**
 * Please note that this implementation is very naive.
 *
 * In order to work with large graphs collision strings desperately need spatial search instead of
 * "try all" approach.
 */

/**
 * Todo:
 * - rething tartgets w.r.t. subtrees
 * - mask gravity spring nodes so that they not shoot selected nodes away
 * - think about bbq enery conservation:
 *   - Should energy be distributed one directionally?
 *   - Should it be proportional to the degree ofthe node?
 */
object LayoutOptimizer {
    data class Spring(val n: Node, val v: Vector2)

    fun optimize(g: Graph, movingNodes: Boolean, restrictOperator: Boolean) {
        Utils.PerformanceData.withPerformanceCheck("LayoutOptimizer", 5.0, onIssue = { g.printStats() }) {
            val tgt = SpringTarget.fromContext(movingNodes, restrictOperator, g.selectedNodes.size)

            doubleRun(g, tgt, movingNodes, restrictOperator)
        }
    }

    private fun doubleRun(
        g: Graph,
        tgt: SpringTarget,
        movingNodes: Boolean,
        restrictOperator: Boolean
    ) {
        compute(g, tgt, movingNodes, restrictOperator, 1.1)
    }


    fun compute(
        g: Graph,
        tgt: SpringTarget,
        movingNodes: Boolean,
        restrictOperator: Boolean,
        deltaMultiplier: Double,
    ) {
        if (tgt != SpringTarget.MoveNoOne) {
            val springSet = listOf(
                Triple({ computeCollisionSprings(g, tgt, movingNodes) }, true, "collision"),
                Triple({ computeGravitySprings(g, tgt, movingNodes) }, true, "grav"),
                Triple({ computeBBSprings(g, tgt) }, true, "bb")
            )

            val takeAPeek = if (false) {
                springSet
                    .filter { it.second }
                    .flatMap { set -> (set.first)().map { it to set.third } }
                    .groupBy { it.first.n }
            } else {
                null
            }

            val springMap = springSet
                .filter { it.second }
                .flatMap { (it.first)() }
                .groupBy { it.n }

            springMap.forEach { (n, springs) ->
                val weight = when (tgt) {
                    //energy conservation!
//                            SpringTarget.MoveEveryone -> 1 / g.edgeMap[n]!!.size
//                            SpringTarget.MoveAllNotSelected -> 1 / (g.findInEdges(n).size + 1.0)
                    else -> 1.0
                }

                val delta = (springs.map { it -> it.v }).reduce { a, b -> a + b } / springs.size

                n.position = n.position + delta*deltaMultiplier
            }

            g.needsRecomputing(g.nodes)
        }
    }

    enum class SpringTarget(val moveSelected: Boolean, moveSubtree: Boolean, moveRest: Boolean) {
        MoveEveryone(true, true, true),
        MoveChildsOnly(false, true, false),
        MoveSelectedOnly(true, false, false),
        MoveEntireFamily(true, true, false),
        MoveAllNotSelected(false, true, true),
        MoveNoOne(false, false, false),
        ;

        companion object {

            fun fromContext(movingNodes: Boolean, restrictOperator: Boolean, selectionSize: Int): SpringTarget {
                val moving = movingNodes
                val restrict = restrictOperator
                val size = selectionSize.coerceAtMost(2)
                val res = when {
                    !moving && !restrict && size == 0 -> MoveEveryone
                    !moving && !restrict && size == 1 -> MoveEntireFamily
                    !moving && !restrict && size == 2 -> MoveEntireFamily
                    !moving && restrict && size == 0 -> MoveEveryone
                    !moving && restrict && size == 1 -> MoveChildsOnly
                    !moving && restrict && size == 2 -> MoveSelectedOnly
                    moving && !restrict && size == 0 -> MoveNoOne //should never happen
//                    moving && !restrict && size == 1 -> MoveChildsOnly
//                    moving && !restrict && size == 2 -> MoveChildsOnly
                    moving && !restrict && size == 1 -> MoveAllNotSelected
                    moving && !restrict && size == 2 -> MoveAllNotSelected
                    moving && restrict && size == 0 -> MoveNoOne //should never happen
                    moving && restrict && size == 1 -> MoveNoOne //should never happen
                    moving && restrict && size == 2 -> MoveNoOne //should never happen
                    else -> MoveNoOne
                }
                return res
            }
        }
    }

    object impl {
        fun degFactor(g: Graph, node: Node, other: Node): Double {
            val d1 = g.edgeMap[node]?.size.orElse(0).toDouble()
            val d2 = g.edgeMap[other]?.size.orElse(0).toDouble()
//            return 1.0 + 0.3 * Math.min(d1, d2).coerceAtLeast(1.5).toDouble()

//            return 0.7 * Math.min(d1, d2).toDouble().let { if (it > 2) it + 1 else it }

            val f1 = 0.7 * Math.min(d1, d2).let { if (it > 2) it + 1 else it }
            val f2 = 0.15 * (Math.max(d1, d2) - 5).coerceAtLeast(0.0)
            return f1 + f2
        }

        fun computeBBSpring(g: Graph, node: Node, othr: Node, e: Edge, strength: Double = 1.0): Spring {
            val cn = (e.src == node).fold(e.cache.dstPt, e.cache.srcPt).let { +it - node.position }
            val co = (e.src == othr).fold(e.cache.dstPt, e.cache.srcPt).let { -it + othr.position }
            val c1 = listOf(cn, co).minBy { it.lengthSquared() }
            val c2 = listOf(cn, co).maxBy { it.lengthSquared() }

            val f = degFactor(g, node, othr) // degree factor
            val d = 1.5 // distance factor

            val desiredLocation = (othr.position - (c1 + c2) * d - c1 * f * d)
            val diff = desiredLocation - node.position

            return Spring(
                n = node,
                v = diff * 0.5  // this makes it converge faster
            )
        }


        fun computeBBSprings(g: Graph, tgt: SpringTarget): List<Spring> {
            val edges = computeConnectedEdgeSet(g, tgt)
            val springs = edges.map { (src, dst, e) -> computeBBSpring(g, src, dst, e) }
            return springs
        }

        fun computeGravitySprings(g: Graph, tgt: SpringTarget, moving: Boolean): List<Spring> {
            val strength = moving.fold(20.0, 20.0)
            val nodes = computeNodeSet(g, tgt)
            val gravities = Utils.CachedMap() { n: Node ->
                g.edgeMap[n]
                    .orEmpty()
                    .filter { it.dst == n }
                    .map { e ->
                        (e.dst.position - e.src.position).toUnit()
                    }
                    .fold(Zero, Vector2::plus)
                    .toUnit()
            }

            val springs = nodes.flatMap { computeGravitySpring(g, it, moving, gravities) }
            /*
            val correction = springs
                .takeIf { tgt == SpringTarget.MoveEveryone }
                ?.map { it.v }
                ?.fold(Zero, Vector2::plus)
                ?.let { it / springs.size }
                .orElse { Zero }
                .let { Zero }

            val correctedSprings = springs.map { it.copy(v = it.v * strength - correction * strength) }
             */

            return springs.filter { tgt.moveSelected == true || !g.selectedNodes.contains(it.n) }
        }

        fun computeGravitySpring(
            g: Graph,
            node: Node,
            moving: Boolean,
            gravities: Utils.CachedMap<Node, Vector2>
        ): List<Spring> {
            val strength = moving.fold(10.0, 10.0)
            return g.edgeMap[node].orEmpty().filter { e -> e.dst == node }
                .flatMap {
                    val grav = gravities[it.src]
                    val dir = (it.dst.position - it.src.position).toUnit()
                    val fac = if (g.findOutEdges(it.src).size < 3) {
                        ((1 - grav.dot(dir)) / 2)
                            .let { it }
                    } else {
                        (-grav.dot(dir))
                            .coerceAtLeast(0.0)
                            .let { it * it }
                    }
                    val res = (grav * fac).toUnit() * strength

                    listOfNotNull(
                        Spring(n = it.dst, v = res),
                        Spring(n = it.src, v = -res),
                    )
                }
        }

        fun shouldTakePseudoLeafs(g: Graph, e: Edge): Boolean {
            return g.edgeMap[e.dst]?.size.orElse(0) < 2 || !g.selectedNodes.contains(e.src)
        }

        fun computeConnectedEdgeSet(g: Graph, tgt: SpringTarget): Iterable<Triple<Node, Node, Edge>> {
            return when (tgt) {
                SpringTarget.MoveEveryone, SpringTarget.MoveAllNotSelected -> g.edges.flatMap {
                    listOf(
                        Triple(it.src, it.dst, it),
                        Triple(it.dst, it.src, it)
                    )
                }.letIf(!tgt.moveSelected) {
                    it.filter { !g.selectedNodes.contains(it.first) }
                }

                SpringTarget.MoveEntireFamily, SpringTarget.MoveChildsOnly -> {
                    g.selectedNodes
                        .flatMap { n ->
                            val nodes = g.edgeMap[n]
                                .orEmpty()
                                .filter { it.src == n || !g.selectedNodes.contains(it.src) } //make sure we have each ege just once: Bo
                            val edges = nodes
                                .flatMap { e ->
                                    listOfNotNull(
                                        Triple(e.src, e.dst, e).takeIf { g.selectedNodes.contains(e.src) },
                                        Triple(e.dst, e.src, e).takeIf { shouldTakePseudoLeafs(g, e) },
                                    )
                                }
                            edges
                        }
                        .letIf(!tgt.moveSelected) {
                            it.filter { !g.selectedNodes.contains(it.first) }
                        }
                }

                SpringTarget.MoveSelectedOnly -> g.findEddges(g.selectedNodes, g.selectedNodes)
                    .flatMap { listOf(Triple(it.src, it.dst, it), Triple(it.dst, it.src, it)) }

                else -> emptySet()
            }
        }

        fun computeCollisionSpring(g: Graph, node: Node, othr: Node, strengthModulator: Double = 1.0): Spring? {
            val (n, o) = node.position to othr.position
            val dir = (o - n).toUnit()
            val c1 = node.cache.shape.connectionPoint(dir, node)
            val c2 = othr.cache.shape.connectionPoint(dir, othr)

//            val distanceCf = g.findEdge(node, other).isNotNull().fold( 2.0, 1.5)
            val distanceCf = 1.5
            val desiredDistance = (c1 + c2).length() * distanceCf * strengthModulator

            return if ((n - o).length() < desiredDistance) {
                Spring(
                    n = node,
                    v = (othr.position - (c1 + c2) * distanceCf * strengthModulator) - node.position
                )
            } else {
                null
            }
        }


        fun computeCollisionSprings(g: Graph, tgt: SpringTarget, moving: Boolean): List<Spring> {
            val strengthModulator = moving.fold(1.0, 1.3)

            val nodes = computeCollisionEdgeSet(g, tgt)

            return nodes
                .mapNotNull { computeCollisionSpring(g, it.first, it.second, strengthModulator) }
        }

        fun computeNodeSet(g: Graph, tgt: SpringTarget): Collection<Node> {
            val nodes = when (tgt) {
                SpringTarget.MoveEveryone, SpringTarget.MoveAllNotSelected -> g.nodes
                    .letIf(!tgt.moveSelected) {
                        it.filter { !g.selectedNodes.contains(it) }.toSet()
                    }

                SpringTarget.MoveEntireFamily, SpringTarget.MoveChildsOnly -> {
                    g.selectedNodes.flatMap { n ->
                        g.edgeMap[n]
                            .orEmpty()
                            .mapNotNull { e -> e.dst.takeIf { shouldTakePseudoLeafs(g, e) } }
                            .let { it + n }
                    }
                        .letIf(!tgt.moveSelected) {
                            it.filter { !g.selectedNodes.contains(it) }
                        }
                }

                SpringTarget.MoveSelectedOnly -> g.selectedNodes

                else -> emptySet()
            }

            return nodes
        }

        fun computeCollisionEdgeSet(g: Graph, tgt: SpringTarget): List<Pair<Node, Node>> {
            val nodes = computeNodeSet(g, tgt)

            return nodes.flatMap { a ->
                g.nodes.mapNotNull { b ->
                    (a to b)
                        .takeIf { a != b }
                }
            }
        }
    }

}