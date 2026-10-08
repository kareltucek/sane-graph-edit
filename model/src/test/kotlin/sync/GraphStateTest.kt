package sync

import Graph
import graph_tools.AddEdgesCommand
import graph_tools.AddNodeCommand
import graph_tools.CompositeCommand
import graph_tools.Edge
import graph_tools.EditTextCommand
import graph_tools.HideCommand
import graph_tools.MoveNodesCommand
import graph_tools.Node
import graph_tools.NodeShape
import graph_tools.RemoveEdgesCommand
import graph_tools.RemoveNodesCommand
import graph_tools.SetShapeCommand
import graph_tools.StyleNodesCommand
import graph_tools.NodeStyle
import utils.Rgb
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphStateTest {
    private fun chain(): Triple<Graph, List<Node>, List<Edge>> {
        val g = Graph()
        val nodes = listOf("a", "b", "c").mapIndexed { i, t -> Node(t, Vector2(i * 100.5, 7.25)) }
        nodes.forEach { g.add(it) }
        val edges = listOf(Edge(nodes[0], nodes[1]), Edge(nodes[1], nodes[2]))
        edges.forEach { g.add(it) }
        return Triple(g, nodes, edges)
    }

    /** Run [change] on [g]; a replica that only gets the patch must end up equal. */
    private fun assertReplicates(g: Graph, change: () -> Unit) {
        val replica = GraphState.of(g).toGraph()
        val before = GraphState.of(g)
        change()
        val after = GraphState.of(g)
        val patch = before.diff(after)
        assertNull(before.conflict(patch))
        assertEquals(after, before.apply(patch))
        replica.applyPatch(patch)
        assertEquals(after, GraphState.of(replica))
        // and the reverse patch undoes it
        replica.applyPatch(after.diff(before))
        assertEquals(before, GraphState.of(replica))
    }

    @Test
    fun `state round trips through a fresh graph`() {
        val (g, nodes, _) = chain()
        nodes[0].attributes.bg = Rgb(1, 2, 3)
        nodes[1].attributes.nodeScale = 1.5
        nodes[1].setShape(NodeShape.Oval)
        nodes[2].attributes.other["link-d"] = "x.dot"
        nodes[2].cache.hideLevel = 2
        g.attributes["rankdir"] = "LR"
        val copy = GraphState.of(g).toGraph()
        assertEquals(GraphState.of(g), GraphState.of(copy))
        assertSame(NodeShape.Oval.impl, copy.node(nodes[1].id)!!.cache.shape)
        assertEquals(nodes[0].position, copy.node(nodes[0].id)!!.position)
    }

    @Test
    fun `every kind of command replicates as a patch`() {
        val (g, nodes, edges) = chain()
        val added = Node("d", Vector2(5, 5))
        assertReplicates(g) { g.commit(CompositeCommand(listOf(AddNodeCommand(g, added), AddEdgesCommand(g, listOf(Edge(nodes[2], added)))))) }
        assertReplicates(g) { g.commit(MoveNodesCommand(g, mapOf(nodes[0] to nodes[0].position), mutableMapOf(nodes[0] to Vector2(-3.125, 9.0)))) }
        assertReplicates(g) { g.commit(EditTextCommand(g, nodes[1], "b", "line one\nline \"two\"")) }
        assertReplicates(g) { g.commit(StyleNodesCommand.toUniform(g, nodes, NodeStyle(Rgb(10, 20, 30), Rgb(0, 0, 0), 2.0))) }
        assertReplicates(g) { g.commit(SetShapeCommand(g, nodes.associateWith { null }, NodeShape.Oval)) }
        assertReplicates(g) { g.commit(HideCommand(listOf(nodes[2]))) }
        assertReplicates(g) { g.commit(CompositeCommand(listOf(RemoveEdgesCommand(g, edges), RemoveNodesCommand(g, listOf(nodes[1]))))) }
        assertReplicates(g) { g.history.undo() }
    }

    @Test
    fun `a patch keeps the objects the graph already has`() {
        val (g, nodes, _) = chain()
        g.cleanSelect(listOf(nodes[0]))
        g.applyPatch(
            Patch(
                addNodes = mapOf("new" to mapOf(NodeFields.POS to "1.0,2.0", NodeFields.TEXT to "n")),
                setNodes = mapOf(nodes[0].id to mapOf(NodeFields.TEXT to "A")),
            )
        )
        assertSame(nodes[0], g.node(nodes[0].id))
        assertEquals("A", nodes[0].attributes.text)
        assertEquals(setOf(nodes[0]), g.selectedNodes)
        // an added node must not steal the cursor
        assertSame(nodes[0], g.lastActiveNode)
        assertNotNull(g.node("new"))
    }

    @Test
    fun `applying a patch twice changes nothing more`() {
        val (g, nodes, edges) = chain()
        val before = GraphState.of(g)
        g.commit(CompositeCommand(listOf(RemoveEdgesCommand(g, listOf(edges[0])), AddNodeCommand(g, Node("d", Vector2(1, 1))))))
        val after = GraphState.of(g)
        g.applyPatch(before.diff(after))
        assertEquals(after, GraphState.of(g))
        assertEquals(4, g.nodes.size)
    }

    @Test
    fun `conflicts name what is gone`() {
        val (g, nodes, edges) = chain()
        val state = GraphState.of(g)
        val gone = state.apply(Patch(removeNodes = setOf(nodes[1].id)))
        // removing a node takes its edges along
        assertTrue(gone.edges.isEmpty())
        assertNotNull(gone.conflict(Patch(setNodes = mapOf(nodes[1].id to mapOf(NodeFields.TEXT to "x")))))
        assertNotNull(gone.conflict(Patch(removeEdges = setOf(edges[0].id))))
        assertNotNull(gone.conflict(Patch(addEdges = mapOf("e" to EdgeState(nodes[0].id, nodes[1].id)))))
        assertNotNull(state.conflict(Patch(addNodes = mapOf(nodes[0].id to emptyMap()))))
        assertNull(gone.conflict(Patch(addEdges = mapOf("e" to EdgeState(nodes[0].id, nodes[2].id)))))
    }

    @Test
    fun `patches that set the same fields are recognised`() {
        val a = Patch(setNodes = mapOf("n" to mapOf(NodeFields.TEXT to "a")))
        val b = Patch(setNodes = mapOf("n" to mapOf(NodeFields.TEXT to "ab")))
        val c = Patch(setNodes = mapOf("n" to mapOf(NodeFields.POS to "0.0,0.0")))
        assertTrue(a.setsSameFieldsAs(b))
        assertTrue(!a.setsSameFieldsAs(c))
        assertTrue(!a.setsSameFieldsAs(b.copy(removeNodes = setOf("m"))))
    }
}
