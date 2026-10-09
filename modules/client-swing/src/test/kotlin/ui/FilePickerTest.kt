package ui

import DotGraphLoader
import client.FilePicker
import client.Workspace
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
        Workspace.dir = launchDir
    }

    private val launchDir = Workspace.dir

    init {
        // as the application does when it registers its commands
        FilePicker.runner = SwingShellRunner
    }

    /** Run the picker and wait until its result has been applied on the EDT. */
    private fun pick(gv: GraphView, done: () -> Boolean) {
        FilePicker.run(gv.model)
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait { }
            Thread.sleep(20)
        }
        // done() can turn true mid-way through the EDT task that applies the result; let it finish
        SwingUtilities.invokeAndWait { }
    }

    @Test
    fun `the picked path opens in the tab, relative to the workspace, with node focus`() {
        val dir = Files.createTempDirectory("picker")
        Files.createDirectory(dir.resolve("sub"))
        val a = dir.resolve("sub/a.dot").also { it.writeText("digraph a {\n  s;\n}\n") }
        dir.resolve("b.dot").writeText("digraph b {\n  target;\n  x;\n}\n")
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(a.toString()), initialFile = a)
        Workspace.dir = dir
        // the command sees SGE_DIR (the workspace, not the current file's directory) and runs there
        FilePicker.command = "test \"\$SGE_DIR\" = \"\$(pwd)\" && test \"\$SGE_DIR\" = \"$dir\" && echo b.dot:target"

        pick(gv) { gv.currentFile?.fileName?.toString() == "b.dot" }

        assertEquals("b.dot", gv.currentFile?.fileName?.toString())
        assertEquals(setOf("target"), gv.g.selectedNodes.map { it.attributes.name }.toSet())
        gv.content.jumpBack()
        assertEquals("a.dot", gv.currentFile?.fileName?.toString())
    }

    @Test
    fun `empty output changes nothing`() {
        val dir = Files.createTempDirectory("picker")
        val a = dir.resolve("a.dot").also { it.writeText("digraph a {\n  s;\n}\n") }
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(a.toString()), initialFile = a)
        var finished = false
        FilePicker.command = "true"
        FilePicker.run(gv.model)
        Thread.sleep(500)
        SwingUtilities.invokeAndWait { finished = true }
        assertEquals(true, finished)
        assertEquals("a.dot", gv.currentFile?.fileName?.toString())
    }
}
