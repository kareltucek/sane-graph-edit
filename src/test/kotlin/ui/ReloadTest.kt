package ui

import DotGraphLoader
import graph_tools.AddNodeCommand
import graph_tools.Node
import utils.Vector2
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadTest {

    private fun names(gv: GraphView) = gv.g.nodes.map { it.attributes.name }.toSet()

    @Test
    fun `reload is a step in the undo history`() {
        val file = Files.createTempDirectory("reload").resolve("a.dot")
        file.writeText("digraph a {\n  one;\n  two;\n  one -> two;\n}\n")
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(file.toString()), initialFile = file)
        val graph = gv.g

        gv.g.commit(AddNodeCommand(gv.g, Node("mine", Vector2(0, 0))))
        assertEquals(3, gv.g.nodes.size)

        // another editor saves the file
        file.writeText("digraph a {\n  one;\n  theirs;\n}\n")
        gv.reloadFile(force = true)
        assertEquals(setOf("one", "theirs"), names(gv))
        assertEquals(0, gv.g.edges.size)
        assertFalse(gv.isDirty)
        assertTrue(gv.g === graph, "same graph, same history")

        // undo the reload: my state, with my unsaved node
        gv.g.history.undo()
        assertEquals(3, gv.g.nodes.size)
        assertTrue("one" in names(gv) && "two" in names(gv))
        assertEquals(1, gv.g.edges.size)
        assertTrue(gv.isDirty)

        // and further back, before my edit
        gv.g.history.undo()
        assertEquals(setOf("one", "two"), names(gv))

        gv.g.history.redo()
        gv.g.history.redo()
        assertEquals(setOf("one", "theirs"), names(gv))
    }
}
