package ui

import DotGraphLoader
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FilePickerTest {

    @AfterTest
    fun restoreDefault() {
        FilePicker.command = FilePicker.DEFAULT_COMMAND
    }

    /** Run the picker and wait until its result has been applied on the EDT. */
    private fun pick(gv: GraphView, done: () -> Boolean) {
        FilePicker.run(gv)
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait { }
            Thread.sleep(20)
        }
    }

    @Test
    fun `the picked path opens in the tab, relative to the current file's directory, with node focus`() {
        val dir = Files.createTempDirectory("picker")
        val a = dir.resolve("a.dot").also { it.writeText("digraph a {\n  s;\n}\n") }
        dir.resolve("b.dot").writeText("digraph b {\n  target;\n  x;\n}\n")
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(a.toString()), initialFile = a)
        // the command sees SGE_DIR and runs there
        FilePicker.command = "test \"\$SGE_DIR\" = \"\$(pwd)\" && echo b.dot:target"

        pick(gv) { gv.currentFile?.fileName?.toString() == "b.dot" }

        assertEquals("b.dot", gv.currentFile?.fileName?.toString())
        assertEquals(setOf("target"), gv.g.selectedNodes.map { it.attributes.name }.toSet())
        gv.jumpBack()
        assertEquals("a.dot", gv.currentFile?.fileName?.toString())
    }

    @Test
    fun `empty output changes nothing`() {
        val dir = Files.createTempDirectory("picker")
        val a = dir.resolve("a.dot").also { it.writeText("digraph a {\n  s;\n}\n") }
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(a.toString()), initialFile = a)
        var finished = false
        FilePicker.command = "true"
        FilePicker.run(gv)
        Thread.sleep(500)
        SwingUtilities.invokeAndWait { finished = true }
        assertEquals(true, finished)
        assertEquals("a.dot", gv.currentFile?.fileName?.toString())
    }
}
