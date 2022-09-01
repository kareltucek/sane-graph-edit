package ui

import Edge
import graph_tools.LayoutOptimizer
import Node
import ui.GraphKeyListener.impl.centerScreen
import ui.GraphKeyListener.impl.clearEdges
import ui.GraphKeyListener.impl.deleteNode
import ui.GraphKeyListener.impl.drawEdge
import ui.GraphKeyListener.impl.drawNodeWithEdge
import ui.GraphKeyListener.impl.optimize
import ui.GraphKeyListener.impl.selectAll
import ui.GraphKeyListener.impl.editNode
import ui.GraphKeyListener.impl.selectLinked
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.awt.event.KeyListener

class GraphKeyListener(
    val graphView: GraphView,
) : KeyListener {
    override fun keyTyped(e: KeyEvent) {
        when (e.keyChar) {
            'e' -> drawEdge(true, graphView)
            'E' -> drawEdge(false, graphView)
            'v' -> drawNodeWithEdge(true, graphView)
            'V' -> drawNodeWithEdge(false, graphView)
            'c' -> clearEdges(graphView)
            'd' -> deleteNode(graphView)
            'o' -> optimize(graphView, false)
            'O' -> optimize(graphView, true)
            ' ' -> editNode(graphView)
            'l' -> selectLinked(true, graphView)
            'L' -> selectLinked(false, graphView)
            '0' -> centerScreen(graphView)
        }
    }


    override fun keyPressed(e: KeyEvent) {
        when {
            e.keyCode == VK_S && e.isControlDown -> {
                graphView.saveFile()
            }

            e.keyCode == VK_O && e.isControlDown -> {
                graphView.loadFile()
                graphView.repaint()
            }

            e.keyCode == VK_A && e.isControlDown -> {
                selectAll(graphView)
            }
        }

    }

    override fun keyReleased(e: KeyEvent) {}

    object impl {

        fun centerScreen(parent: GraphView) {
            parent.centerScreen()
        }

        fun selectLinked(forward: Boolean, parent: GraphView) {
            parent.g.selectedNodes
                .flatMap { n ->
                    parent.g.edgeMap[n]
                        ?.map { if (forward) it.dst else it.src }
                        .orEmpty()
                }
                .let { parent.g.selectAll(it) }
            parent.repaint()
        }

        fun optimize(parent: GraphView, restrict: Boolean) {
            LayoutOptimizer.optimize(parent.g, movingNodes = false, restrictOperator = restrict)
            parent.repaint()
        }

        fun selectAll(parent: GraphView) {
            if (parent.g.selectedNodes.size == parent.g.nodes.size) {
                parent.g.cleanSelect(setOf())
            } else {
                parent.g.cleanSelect(parent.g.nodes)
            }
            parent.repaint()
        }

        fun editNode(parent: GraphView) {
            parent.g.lastActiveNode?.let { n ->
                parent.startNodeEdit(n, null)
            }
        }

        fun deleteNode(parent: GraphView) {
            val pos = parent.lastCursorPosition
            val sel = parent.g.selectedNodes.map { it }

            parent.g.edges
                .filter { sel.contains(it.src) || sel.contains(it.dst) }
                .let { parent.g.removeAllEdges(it.toSet()) }

            parent.g.unselectAll(sel)
            parent.g.removeAllNodes(sel)

            parent.repaint()
        }


        fun clearEdges(parent: GraphView) {
            val pos = parent.lastCursorPosition
            val sel = parent.g.selectedNodes

            sel.forEach { n ->
                parent.g.edges.filter { it.src == n || it.dst == n }
                    .let { parent.g.removeAllEdges(it.toSet()) }
            }

            parent.repaint()
        }

        fun drawNodeWithEdge(forward: Boolean, parent: GraphView) {
            val pos = parent.lastCursorPosition
            val sel = parent.g.selectedNodes
            val newNode = Node(
                label = "New node",
                pos = pos
            )

            parent.g.add(newNode)

            sel.forEach { selectedNode ->
                val e = if (forward) Edge(selectedNode, newNode) else Edge(newNode, selectedNode)
                parent.g.add(e)
            }

            parent.repaint()
        }

        fun drawEdge(forward: Boolean, parent: GraphView) {
            val pos = parent.lastCursorPosition
            val pointed = Clicker.selectClickedNode(parent.g, pos)
            val selected = parent.g.selectedNodes - pointed

            val (src, dst) = if (forward) selected to pointed else pointed to selected

            val existingEdges = parent.g.findEddges(src, dst)
            val representedSrcs = existingEdges.map { it.src }.toSet()
            val representedDsts = existingEdges.map { it.dst }.toSet()

            if (representedDsts.size == dst.size && representedSrcs.size == src.size) {
                parent.g.removeAllEdges(existingEdges)
            } else {
                val existingConnections = existingEdges.associateBy { it.src to it.dst }
                val missingEdges = src.flatMap { s ->
                    dst.map { d ->
                        Edge(src = s, dst = d)
                    }
                }
                    .filter { !existingConnections.containsKey(it.src to it.dst) }
                    .filter { it.src != it.dst }

                parent.g.addAllEdges(missingEdges)
            }

            parent.repaint()
        }
    }
}