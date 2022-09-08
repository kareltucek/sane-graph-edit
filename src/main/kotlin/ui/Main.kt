package ui

import Graph
import graph_tools.Node
import graph_tools.Plotter
import utils.Utils.orElse
import utils.Vector2
import java.awt.*


object Clicker {
    fun Vector2.isInSquare(lt: Vector2, br: Vector2, tolerance: Double): Boolean {
        val xMatches = lt.x - tolerance < this.x && this.x < br.x + tolerance
        val yMatches = lt.y - tolerance < this.y && this.y < br.y + tolerance
        return xMatches && yMatches
    }

    fun selectClickedNode(g: Graph, clickCoordinates: Vector2): MutableSet<Node> {
        return g.nodes
            .filter {
                it.cache?.shapeBounds?.let { bounds ->
                    clickCoordinates.isInSquare(
                        it.position - bounds / 2,
                        it.position + bounds / 2,
                        10.0 / Plotter.t.scaleX.toDouble()
                    )
                }.orElse(false)
            }
            .minByOrNull { (it.position - clickCoordinates).lengthSquared() }
            ?.let { mutableSetOf(it) }
            ?: mutableSetOf()
    }
}

fun createAndShowGUI() {
    val frame = Window("Sane Graph Edit")
    frame.isVisible = true
}

fun main(args: Array<String>) {
    println("Program arguments: ${args.joinToString()}")
    EventQueue.invokeLater(::createAndShowGUI)
}