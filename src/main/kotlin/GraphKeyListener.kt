import GraphKeyListener.impl.clearEdges
import GraphKeyListener.impl.deleteNode
import GraphKeyListener.impl.drawEdge
import GraphKeyListener.impl.drawNodeWithEdge
import GraphKeyListener.impl.editNode
import Utils.orElse
import java.awt.event.KeyEvent
import java.awt.event.KeyListener

class GraphKeyListener(val parent: GraphComponent) : KeyListener {
    override fun keyTyped(e: KeyEvent) {
        when (e.keyChar) {
            'e' -> drawEdge(true, parent)
            'E' -> drawEdge(false, parent)
            'v' -> drawNodeWithEdge(true, parent)
            'V' -> drawNodeWithEdge(false, parent)
            'c' -> clearEdges(parent)
            'd' -> deleteNode(parent)
            ' ' -> editNode(parent)
        }
    }

    override fun keyPressed(e: KeyEvent) {}

    override fun keyReleased(e: KeyEvent) {}

    object impl {
        fun editNode(parent: GraphComponent) {
            Graph.g.lastActiveNode?.let { n ->
                parent.startNodeEdit(n, null)
            }
        }

        fun deleteNode(parent: GraphComponent) {
            val pos = parent.lastCursorPosition
            val sel = Graph.g.selectedNodes.map { it }

            Graph.g.edges
                .filter { sel.contains(it.src) || sel.contains(it.dst) }
                .let { Graph.g.removeAllEdges(it.toSet()) }

            Graph.g.unselectAll(sel)
            Graph.g.removeAllNodes(sel)

            parent.repaint()
        }


        fun clearEdges(parent: GraphComponent) {
            val pos = parent.lastCursorPosition
            val sel = Graph.g.selectedNodes

            sel.forEach { n ->
                Graph.g.edges.filter { it.src == n || it.dst == n }
                    .let { Graph.g.removeAllEdges(it.toSet()) }
            }

            parent.repaint()
        }

        fun drawNodeWithEdge(forward: Boolean, parent: GraphComponent) {
            val pos = parent.lastCursorPosition
            val sel = Graph.g.selectedNodes
            val newNode = Node(
                text = "New node",
                position = pos
            )

            Graph.g.add(newNode)

            sel.forEach { selectedNode ->
                val e = if (forward) Edge(selectedNode, newNode) else Edge(newNode, selectedNode)
                Graph.g.add(e)
            }

            parent.repaint()
        }

        fun drawEdge(forward: Boolean, parent: GraphComponent) {
            val pos = parent.lastCursorPosition
            val src = Graph.g.selectedNodes
            val dst = Clicker.selectClickedNode(Graph.g, pos)

            val possibleEdges = src.flatMap { from ->
                dst.flatMap { to ->
                    if (forward) setOf(Edge(from, to)) else setOf(Edge(to, from))
                }
            }
                .filter { it.src != it.dst}

            val grouped = possibleEdges.groupBy { Graph.g.edges.contains(it) }

            val missingSize = grouped[false]?.size.orElse(0)

            if (missingSize > 0) {
                Graph.g.addAllEdges(grouped[false].orEmpty())
            } else {
                Graph.g.removeAllEdges(grouped[true].orEmpty().toSet())
            }

            parent.repaint()
        }
    }
}