package client

import Graph
import graph_tools.Node
import utils.Utils.orElse
import utils.Vector2

object Clicker {
    fun Vector2.isInSquare(lt: Vector2, br: Vector2, tolerance: Double): Boolean {
        val xMatches = lt.x - tolerance < this.x && this.x < br.x + tolerance
        val yMatches = lt.y - tolerance < this.y && this.y < br.y + tolerance
        return xMatches && yMatches
    }

    /** The visible node at [clickCoordinates] (workspace), if any; [scale] is the view's zoom, for a tolerance of ten screen pixels. */
    fun selectClickedNode(g: Graph, clickCoordinates: Vector2, scale: Double): MutableSet<Node> {
        return g.nodes
            .filter { g.isVisible(it) }
            .filter {
                it.cache?.shapeBounds?.let { bounds ->
                    clickCoordinates.isInSquare(
                        it.position - bounds / 2,
                        it.position + bounds / 2,
                        10.0 / scale
                    )
                }.orElse(false)
            }
            .minByOrNull { (it.position - clickCoordinates).lengthSquared() }
            ?.let { mutableSetOf(it) }
            ?: mutableSetOf()
    }
}
