package graph_tools

import Graph
import graph_tools.LayoutOptimizer.impl.computeBBSprings
import graph_tools.LayoutOptimizer.impl.computeCollisionSprings
import graph_tools.LayoutOptimizer.impl.computeGravitySprings
import utils.Utils
import utils.Utils.fold
import utils.Utils.letIf
import utils.Utils.orElse
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

/**
 * Force-directed graph layout. One pass of [optimize] gathers
 * three kinds of spring force, averages them per node, and
 * applies one position update.
 *
 * ## The three spring types
 *
 * **BB spring** ([impl.computeBBSpring]) — *edge attraction*.
 * For each edge, pulls both endpoints toward a target distance
 * derived from the nodes' connection points on their shape
 * boundaries (hence "BB" for bounding-box boundary), modulated
 * by a degree factor (higher-degree nodes pull harder) and the
 * session-wide [springScale]. Only fires on connected pairs.
 *
 * **Collision spring** ([impl.computeCollisionSpring]) — *node
 * repulsion*. For every pair of nodes that are closer than
 * `sum-of-radii × distanceCf × springScale` (with a floor on
 * distanceCf at 1.0 so nodes can touch but not overlap regardless
 * of scale), pushes them apart. Fires on *every* pair, connected
 * or not — this is what keeps the graph from collapsing into a
 * point under BB-spring attraction.
 *
 * **Gravity spring** ([impl.computeGravitySpring]) —
 * *directional alignment*. For each node with incoming edges,
 * applies a force that tries to align the in-edges' directions
 * so "flows" (chains of directed edges) stay consistent in
 * orientation. Only fires on nodes with at least one in-edge.
 *
 * ## Per-pass combining
 *
 * Each spring function returns a `List<Spring>`. [compute]
 * flattens all three lists, groups by target node, averages the
 * per-node vector, and adds it to the node's position. One pass
 * is one nudge — the user's `o` key hits it once; dragging with
 * `optimizeOnDrag` hits it every mouse-move event.
 *
 * ## [SpringTarget]
 *
 * Controls *which* nodes get moved during a pass. `MoveEveryone`
 * (no selection) relaxes the whole graph; `MoveSelectedOnly`
 * (non-empty selection) relaxes just the selected nodes but
 * still considers cross-boundary edges as pulls on the
 * selected end. See [SpringTarget.fromContext] for the full
 * table.
 *
 * ## [springScale] (session-wide)
 *
 * Multiplier on BB-spring target distance AND collision
 * distance threshold — the whole graph breathes together when
 * the user dials it with `-`/`=`. See `:help init` for the user-
 * facing story.
 *
 * ## Known limitations
 *
 * - **O(n²) collision**: every step tests every node pair.
 *   Fine for a few hundred nodes, not for thousands. Spatial
 *   hashing is the obvious fix but not done yet.
 * - **No energy conservation**: forces are applied as-is, no
 *   damping or timestep control. Large scale changes can
 *   oscillate.
 * - **Gravity springs can launch selected nodes** when their
 *   in-edge directions conflict with the overall flow; the
 *   old top-of-file TODO flagged this.
 */
object LayoutOptimizer {
    data class Spring(val n: Node, val v: Vector2)

    /**
     * Session-wide scale for the BB spring target distance.
     *
     * 1.0 is the baseline density. `-` / `=` keybindings shrink
     * / grow this by a small multiplicative step. `:set
     * spring-scale=<n>` sets it directly.
     *
     * Not persisted to DOT — it's a workflow preference, not a
     * property of the graph. Survives tab switches within a
     * session; resets to 1.0 on restart.
     */
    var springScale: Double = 1.0

    /**
     * Per-second multiplier for the `-` / `=` keys (and a
     * matching `1/step` in the other direction). Default 0.8
     * = "spring-scale shrinks by 20% over one second of
     * holding the `-` key". Larger values toward 1.0 are
     * gentler; smaller values are more aggressive.
     *
     * Tuneable via `:set spring-scale-step=<n>`. Session-only
     * like [springScale].
     */
    var springScaleStep: Double = 0.8

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
        compute(g, tgt, movingNodes, restrictOperator, 1.0)
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
                Pair({ computeCollisionSprings(g, tgt, movingNodes) }, "collision"),
                Pair({ computeGravitySprings(g, tgt, movingNodes) }, "grav"),
                Pair({ computeBBSprings(g, tgt) }, "bb")
            )

            val takeAPeek = if (true) {
                springSet
                    .flatMap { set -> (set.first)().map { it to set.second } }
                    .groupBy { it.first.n }
            } else {
                null
            }

            val springMap = springSet
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

            /** We need to recompute connection points immediately because this function can be called multiple times per drawn frame. */
            Plotter.recomputeEdges(g.edges)
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
                    // No selection → relax the whole graph.
                    !moving && size == 0 -> MoveEveryone
                    // Non-empty selection via `o` or `O`: move ONLY the
                    // selected nodes. Unselected nodes stay anchored,
                    // and cross-boundary edges are ignored (so an
                    // unselected neighbour has no spring influence).
                    // This is the "optimize the selected subset against
                    // itself" behaviour the user asked for.
                    !moving -> MoveSelectedOnly
                    // Drag-time optimisation: the selected nodes are
                    // the drag anchor; relax everything else around
                    // them.
                    moving && size > 0 -> MoveAllNotSelected
                    else -> MoveNoOne // no selection while dragging shouldn't happen
                }
                return res
            }
        }
    }

    object impl {
        fun degFactor(g: Graph, node: Node, other: Node): Double {
            val d1 = g.edgeMap[node]?.size.orElse(0).toDouble()
            val d2 = g.edgeMap[other]?.size.orElse(0).toDouble()

            val f1 = 0.2 * Math.min(d1, d2).let { if (it > 2) it + 1 else it }
            val f2 = 0.0 * (Math.max(d1, d2) - 5).coerceAtLeast(0.0)
            return 1.0 + f1 + f2
        }

        fun computeBBSpring(g: Graph, node: Node, othr: Node, e: Edge, strength: Double = 1.0): Spring {
            val cn = (e.src == node).fold(e.cache.dstPt, e.cache.srcPt).let { +it - node.position }
            val co = (e.src == othr).fold(e.cache.dstPt, e.cache.srcPt).let { -it + othr.position }
            val c1 = listOf(cn, co).minBy { it.lengthSquared() }
            val c2 = listOf(cn, co).maxBy { it.lengthSquared() }

            val f = degFactor(g, node, othr) // degree factor
            val d = springScale // distance factor, tuneable via -/=

            val desiredRelativeLocation =  - (c1 + c2) * d * f
//            val desiredRelativeLocation =  - (c1 + c2)
            val desiredAbsoluteLocation = (node.position + othr.position + desiredRelativeLocation) / 2
            val diff = desiredAbsoluteLocation - node.position

            return Spring(
                n = node,
                v = diff
            )
        }


        fun computeBBSprings(g: Graph, tgt: SpringTarget): List<Spring> {
            val edges = computeConnectedEdgeSet(g, tgt)
            val springs = edges.map { (src, dst, e) -> computeBBSpring(g, dst, src, e) }

            return springs
        }

        fun computeGravitySprings(g: Graph, tgt: SpringTarget, moving: Boolean): List<Spring> {
            val strength = moving.fold(20.0, 20.0)
            val nodes = computeNodeSet(g, tgt)
            val gravities = Utils.CachedMap() { n: Node ->
                g.findInEdges(n)
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
            return g.findInEdges(node)
                .flatMap {
                    val grav = gravities[it.src]
                    val dir = (it.dst.position - it.src.position).toUnit()
                    val fac = if (g.findOutEdges(it.src).size < 3) {
                        ((1 - grav.dot(dir)) / 2)
                        // 0-1, across entire circumference
                    } else {
                        (-grav.dot(dir))
                            .coerceAtLeast(0.0)
                            .let { it * it }
                        0.0
                        // 0-1, just around in edge
                    }
                    val res = (grav * fac).toUnit() * strength
                    val correction = (-dir).toScale(res.length())

                    //TODO: this does not seem to preserve distance!

                    listOfNotNull(
                        Spring(n = it.dst, v = res + correction),
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

                SpringTarget.MoveSelectedOnly -> {
                    // Include every edge incident to the selection,
                    // including cross-boundary edges. Each triple
                    // `(a, b, e)` produces a spring for `b` (the
                    // "moving" end — second slot), so we emit one
                    // triple per selected endpoint:
                    //   • edge fully inside the selection → both
                    //     endpoints get springs.
                    //   • edge crossing the boundary → only the
                    //     selected endpoint gets a spring; the
                    //     unselected end stays anchored but still
                    //     exerts a pull on its selected neighbour.
                    val sel = g.selectedNodes
                    g.edges
                        .filter { it.src in sel || it.dst in sel }
                        .flatMap { e ->
                            listOfNotNull(
                                Triple(e.dst, e.src, e).takeIf { e.src in sel },
                                Triple(e.src, e.dst, e).takeIf { e.dst in sel },
                            )
                        }
                }

                else -> emptySet()
            }
        }

        fun computeCollisionSpring(g: Graph, node: Node, othr: Node, strengthModulator: Double = 1.0): Spring? {
            val (n, o) = node.position to othr.position
            val dir = (o - n).toUnit()
            val c1 = node.cache.shape.connectionPoint(dir, node)
            val c2 = othr.cache.shape.connectionPoint(dir, othr)

            // Baseline 2.0 = "nodes want a gap equal to their own
            // size between them". Scales with springScale so the
            // whole graph breathes together — edge attraction and
            // unconnected-node repulsion both change by the same
            // factor. Floor at 1.0 so the collision distance never
            // drops below "just touching" — otherwise very small
            // scale values would permit visual overlap.
            val distanceCf = (2.0 * springScale).coerceAtLeast(1.0)
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