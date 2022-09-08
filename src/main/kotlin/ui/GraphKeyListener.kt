package ui

import graph_tools.GraphTools.computeGeneration
import graph_tools.Edge
import graph_tools.GraphTools.computeClosure
import graph_tools.LayoutOptimizer
import graph_tools.Node
import ui.GraphKeyListener.impl.selectAll
import ui.GraphKeyListener.impl.editNode
import ui.GraphKeyListener.impl.executeCommand
import ui.GraphKeyListener.impl.unselectAll
import utils.Utils.orElse
import utils.Utils.toUnit
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.awt.event.KeyListener

class GraphKeyListener(
    val graphView: GraphView,
) : KeyListener {
    override fun keyTyped(e: KeyEvent) {
        val used = executeCommand(e.keyChar.toString(), graphView)
        if (used) {
            e.consume()
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

            e.keyCode == VK_ESCAPE -> {
                unselectAll(graphView)
            }

            e.keyCode == VK_SPACE -> {
                editNode(graphView, e.isShiftDown)
            }
        }
        e.consume()
    }


    override fun keyReleased(e: KeyEvent) {}

    object impl {
        fun executeCommand(oneCommand: String, graphView: GraphView): Boolean {
            val used: Unit? = when (oneCommand) {
                "e" -> drawEdge(graphView, true)
                "E" -> drawEdge(graphView, false)
                "v" -> drawNodeWithEdge(graphView, forwardEdge = true, append = false)
                "V" -> drawNodeWithEdge(graphView, forwardEdge = false, append = false)
                "a" -> drawNodeWithEdge(graphView, forwardEdge = true, append = true)
                "A" -> drawNodeWithEdge(graphView, forwardEdge = false, append = true)
                "c" -> clearEdges(graphView)
                "d" -> deleteNode(graphView, false)
                "D" -> deleteNode(graphView, true)
                "o" -> optimize(graphView, false)
                "O" -> optimize(graphView, true)
                "t" -> selectClosure(graphView, true)
                "T" -> selectClosure(graphView, false)
                "l" -> selectLinked(graphView, true)
                "L" -> selectLinked(graphView, false)
                "w" -> unselectOldestGen(graphView, true)
                "W" -> unselectOldestGen(graphView, false)
                "0" -> boundScreen(graphView)
                "1" -> centerScreen(graphView)
                "f" -> pasteFormat(graphView)
                "F" -> copyFormat(graphView)
                "g" -> grab(graphView)
                "G" -> executeMacro(graphView, "tw0").toUnit()
                else -> null
            }
            return used != null
        }

        fun executeMacro(graphView: GraphView, commandSequence: String): Boolean {
            return commandSequence
                .chunked(1)
                .map { executeCommand(it, graphView) }
                .fold(true) { a, b -> a && b }
        }


        fun copyFormat(graphView: GraphView) {
            val target = graphView.g.selectedNodes.firstOrNull()

            graphView.defaultNodeStyle = target?.let { n ->
                NodeStyle.fromNode(n)
            }.orElse(NodeStyle())
        }

        fun pasteFormat(graphView: GraphView) {
            graphView.g.selectedNodes.forEach {
                it.setStyle(graphView.defaultNodeStyle)
            }
            graphView.g.needsRecomputing(graphView.g.selectedNodes)
            graphView.repaint()
        }

        fun grab(graphView: GraphView) {
            graphView.mouseListener.controller.startMove()
        }

        fun unselectAll(graphView: GraphView) {
            graphView.g.cleanSelect(emptySet())
            graphView.repaint()
        }

        fun centerScreen(graphView: GraphView) {
            graphView.centerScreen()
        }

        fun boundScreen(graphView: GraphView) {
            graphView.boundScreen()
        }

        fun selectClosure(graphView: GraphView, forward: Boolean) {
            val nextGen = computeClosure(graphView.g, graphView.g.selectedNodes, forward)
            graphView.g.selectAll(nextGen)
            graphView.repaint()
        }

        fun selectLinked(graphView: GraphView, forward: Boolean) {
            val nextGen = computeGeneration(graphView.g, graphView.g.selectedNodes, forward)
            graphView.g.selectAll(nextGen)
            graphView.repaint()
        }

        fun unselectOldestGen(graphView: GraphView, forward: Boolean) {
            val oldestGen = graphView.g.selectedNodes
                .filter {
                    if (forward) {
                        graphView.g.findEddges(graphView.g.selectedNodes, setOf(it)).isEmpty()
                    } else {
                        graphView.g.findEddges(setOf(it), graphView.g.selectedNodes).isEmpty()
                    }
                }
            graphView.g.unselectAll(oldestGen)
            graphView.repaint()
        }

        fun optimize(graphView: GraphView, restrict: Boolean) {
            LayoutOptimizer.optimize(graphView.g, movingNodes = false, restrictOperator = restrict)
            graphView.repaint()
        }

        fun selectAll(graphView: GraphView) {
            if (graphView.g.selectedNodes.size == graphView.g.nodes.size) {
                graphView.g.cleanSelect(setOf())
            } else {
                graphView.g.cleanSelect(graphView.g.nodes)
            }
            graphView.repaint()
        }

        fun editNode(graphView: GraphView, select: Boolean) {
            graphView.g.lastActiveNode?.let { n ->
                if (select) {
                    graphView.g.cleanSelect(setOf(n))
                }
                graphView.startNodeEdit(n, null)
            }
        }

        fun deleteNode(graphView: GraphView, reconnectNodes: Boolean) {
            val pos = graphView.lastCursorPosition
            val sel = graphView.g.selectedNodes.map { it }

            if (reconnectNodes) {
                sel.flatMap {
                    val ins = graphView.g.findInEdges(it)
                        .map { it.src }
                        .toSet()
                    val outs = graphView.g.findOutEdges(it)
                        .map { it.dst }
                        .toSet()
                    ins.flatMap { src ->
                        outs.map { dst ->
                            src to dst
                        }
                    }
                }
                    .distinct()
                    .forEach {
                        graphView.g.add(Edge(it.first, it.second))
                    }
            }

            graphView.g.edges
                .filter { sel.contains(it.src) || sel.contains(it.dst) }
                .let { graphView.g.removeAllEdges(it.toSet()) }

            graphView.g.unselectAll(sel)
            graphView.g.removeAllNodes(sel)

            graphView.repaint()
        }


        fun clearEdges(graphView: GraphView) {
            val pos = graphView.lastCursorPosition
            val sel = graphView.g.selectedNodes

            sel.forEach { n ->
                graphView.g.edges.filter { it.src == n || it.dst == n }
                    .let { graphView.g.removeAllEdges(it.toSet()) }
            }

            graphView.repaint()
        }

        fun drawNodeWithEdge(graphView: GraphView, forwardEdge: Boolean, append: Boolean) {
            val pos = graphView.lastCursorPosition

            val from = if (append) {
                graphView.g.lastActiveNode
                    ?.let { listOf(it) }
                    .orElse(graphView.g.selectedNodes)
            } else {
                graphView.g.selectedNodes
            }

            val newNode = Node(
                label = "New node",
                pos = pos,
                style = graphView.defaultNodeStyle,
            )

            graphView.g.add(newNode)

            from.forEach { selectedNode ->
                val e = if (forwardEdge) Edge(selectedNode, newNode) else Edge(newNode, selectedNode)
                graphView.g.add(e)
            }


            graphView.repaint()
        }

        fun drawEdge(graphView: GraphView, forward: Boolean) {
            val pos = graphView.lastCursorPosition
            val pointed = Clicker.selectClickedNode(graphView.g, pos)
            val selected = graphView.g.selectedNodes - pointed

            val (src, dst) = if (forward) selected to pointed else pointed to selected

            val existingEdges = graphView.g.findEddges(src, dst)
            val representedSrcs = existingEdges.map { it.src }.toSet()
            val representedDsts = existingEdges.map { it.dst }.toSet()

            if (representedDsts.size == dst.size && representedSrcs.size == src.size) {
                graphView.g.removeAllEdges(existingEdges)
            } else {
                val existingConnections = existingEdges.associateBy { it.src to it.dst }
                val missingEdges = src.flatMap { s ->
                    dst.map { d ->
                        Edge(src = s, dst = d)
                    }
                }
                    .filter { !existingConnections.containsKey(it.src to it.dst) }
                    .filter { it.src != it.dst }

                graphView.g.addAllEdges(missingEdges)
            }

            graphView.repaint()
        }
    }
}