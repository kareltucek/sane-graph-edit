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

    /** The visible node that seems to be at [clickCoordinates] (workspace, in the plane [view] speaks of), if any; within ten screen pixels. */
    fun selectClickedNode(view: EditorView, clickCoordinates: Vector2): MutableSet<Node> {
        val g = view.g
        val tolerance = 10.0 / view.viewTransform.scale
        return g.nodes
            .filter { g.isVisible(it) }
            // where and how big each seems to be; none for one that is not shown
            .mapNotNull { n -> view.apparent(n)?.let { n to it } }
            .filter { (n, seen) ->
                val half = n.cache.shapeBounds * seen.scale / 2
                clickCoordinates.isInSquare(seen.position - half, seen.position + half, tolerance)
            }
            // the one nearest the viewer is the one on top
            .sortedWith(compareByDescending<Pair<Node, Apparent>> { it.second.scale }.thenBy { (it.second.position - clickCoordinates).lengthSquared() })
            .firstOrNull()
            ?.let { mutableSetOf(it.first) }
            ?: mutableSetOf()
    }
}
