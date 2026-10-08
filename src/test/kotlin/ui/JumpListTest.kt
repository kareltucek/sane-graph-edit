package ui

import client.*
import DotGraphLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JumpListTest {

    private fun loc(name: String) = JumpList.Location(Paths.get("/$name.dot"), null, emptySet(), List(6) { 0.0 })

    @Test
    fun `back records the current spot so forward can return`() {
        val j = JumpList()
        j.record(loc("a"))                        // jumped a -> b
        assertEquals(loc("a"), j.back(loc("b")))  // back to a
        assertEquals(loc("b"), j.forward())       // forward to b
        assertNull(j.forward())
    }

    @Test
    fun `back stops at the oldest entry`() {
        val j = JumpList()
        j.record(loc("a"))
        j.back(loc("b"))
        assertNull(j.back(loc("a")))
    }

    @Test
    fun `revisiting a spot moves it to the end instead of duplicating`() {
        val j = JumpList()
        j.record(loc("a"))
        j.record(loc("b"))
        j.record(loc("a"))
        assertEquals(2, j.size)
        assertEquals(loc("a"), j.back(loc("c")))
        assertEquals(loc("b"), j.back(loc("c")))
    }

    @Test
    fun `with discard set a dirty tab follows a link without asking`() {
        val dir = Files.createTempDirectory("discard")
        val a = dir.resolve("a.dot").also { it.writeText("digraph a {\n  s [ link=\"b.dot\" ];\n}\n") }
        dir.resolve("b.dot").writeText("digraph b {\n  t;\n}\n")
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(a.toString()), initialFile = a)
        gv.g.commit(graph_tools.EditTextCommand(gv.g, gv.g.nodes.first(), "s", "changed"))  // makes the tab dirty
        assertEquals(true, gv.isDirty)
        gv.g.cleanSelect(gv.g.nodes)
        graph_tools.ViewModes.discard = true
        try {
            gv.followFileLink()  // a dialog would throw in a headless test
        } finally {
            graph_tools.ViewModes.discard = false
        }
        assertEquals("b.dot", gv.currentFile?.fileName.toString())
    }

    @Test
    fun `gf then Ctrl+O and Ctrl+I move between the files`() {
        val dir = Files.createTempDirectory("jumps")
        val a = dir.resolve("a.dot").also {
            it.writeText("digraph a {\n  start [label=\"S\"; link=\"b.dot:target\"];\n  other;\n}\n")
        }
        dir.resolve("b.dot").writeText("digraph b {\n  target [label=\"T\"];\n  x;\n}\n")
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(a.toString()), initialFile = a)
        gv.g.cleanSelect(gv.g.nodes.filter { it.attributes.name == "start" })

        gv.followFileLink()
        assertEquals("b.dot", gv.currentFile?.fileName.toString())
        assertEquals(setOf("target"), gv.g.selectedNodes.map { it.attributes.name }.toSet())

        gv.jumpBack()
        assertEquals("a.dot", gv.currentFile?.fileName.toString())
        assertEquals(setOf("start"), gv.g.selectedNodes.map { it.attributes.name }.toSet())

        gv.jumpForward()
        assertEquals("b.dot", gv.currentFile?.fileName.toString())
        assertEquals(setOf("target"), gv.g.selectedNodes.map { it.attributes.name }.toSet())
    }
}
