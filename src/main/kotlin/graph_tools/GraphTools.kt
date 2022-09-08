package graph_tools

import Graph
import utils.Rectangle
import utils.Vector2

object GraphTools {

    fun computeCenterOfMass(nodes: Collection<Node>): Vector2 {
        return nodes.fold(Vector2.Zero) { a, b -> a + b.position } / nodes.size
    }

    fun computeBoundingBox(nodes: Collection<Node>): Rectangle? {
        val positions = nodes.flatMap {
            listOf(
                it.position,
                it.position - it.cache.shapeBounds/2,
                it.position + it.cache.shapeBounds/2,
            )
        }
        val maybeUl = positions.reduceOrNull { a, b -> a.min(b) }
        val maybeBr = positions.reduceOrNull { a, b -> a.max(b) }
        return maybeUl?.let { ul ->
           maybeBr?.let { br ->
               Rectangle(ul, br)
           }
        }
    }

    fun computeGeneration(graph: Graph, root: Set<Node>, forward: Boolean): Set<Node> {
        return root
            .flatMap { n ->
                if (forward) {
                    graph.findOutEdges(n).map { it.dst }
                } else {
                    graph.findInEdges(n).map { it.src }
                }
            }
            .filter { !root.contains(it) }
            .toSet()
    }

    fun computeClosure(graph: Graph, root: Set<Node>, forward: Boolean): Set<Node> {
        var currentGen = root
        val closure: MutableSet<Node> = mutableSetOf()
        closure.addAll(root)

        while (currentGen.isNotEmpty()) {
            val nextGen = computeGeneration(graph, currentGen, forward)
                .filter { !closure.contains(it) }
                .toSet()
            closure.addAll(nextGen)
            currentGen = nextGen
        }

        return closure
    }
}