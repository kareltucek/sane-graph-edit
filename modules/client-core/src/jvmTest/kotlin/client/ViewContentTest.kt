package client

import DotGraphLoader
import Graph
import graph_tools.EditTextCommand
import graph_tools.Node
import utils.Vector2
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Opening, saving and link following with a scripted user: no window, no dialogs. */
class ViewContentTest {
    /** Answers the questions as told and remembers what it was asked. */
    private class ScriptedShell : Shell {
        var agree = false
        var saveTo: Path? = null
        val questions = mutableListOf<String>()
        val messages = mutableListOf<String>()
        val tabs = mutableListOf<EditorView>()

        override fun message(text: String) {
            messages += text
        }
        override fun alert(title: String, text: String, error: Boolean) {
            messages += "$title: $text"
        }
        override fun confirm(title: String, question: String): Boolean {
            questions += question
            return agree
        }
        override fun chooseOpen(): Path? = null
        override fun chooseSave(defaultName: String): Path? = saveTo
        override fun chooseExport(defaultName: String): Path? = null
        override fun stateChanged() {}
        override fun sessionChanged() {}
        override fun newTab(): EditorView = EditorView(Graph(), NoRenderer, this).also { tabs += it }
        override fun edit(path: Path): EditorView =
            EditorView(DotGraphLoader.loadFromFile(path.toString()), NoRenderer, this, initialFile = path).also { tabs += it }
        override fun browse(dir: Path): EditorView = newTab()
        val workspaces = mutableListOf<Path>()
        override fun switchWorkspace(dir: Path) {
            workspaces.add(dir)
        }
        override fun quit(force: Boolean, all: Boolean) {}
        override fun modesChanged() {}
    }

    private val shell = ScriptedShell()
    private val dir: Path = Files.createTempDirectory("content")
    private val a: Path = dir.resolve("a.dot").also { it.writeText("digraph a {\n  s [ link=\"b.dot:t\" ];\n}\n") }

    init {
        dir.resolve("b.dot").writeText("digraph b {\n  t;\n  u;\n}\n")
    }

    private fun viewOfA(): EditorView =
        EditorView(DotGraphLoader.loadFromFile(a.toString()), NoRenderer, shell, initialFile = a).also { it.g.cleanSelect(it.g.nodes) }

    private fun EditorView.change() = g.commit(EditTextCommand(g, g.nodes.first(), g.nodes.first().attributes.text, "changed"))

    @Test
    fun `a link is followed without a question while nothing is unsaved`() {
        val view = viewOfA()
        assertTrue(view.content.followFileLink())
        assertEquals("b.dot", view.content.currentFile?.fileName.toString())
        assertEquals(listOf("t"), view.g.selectedNodes.map { it.attributes.name })
        assertEquals(emptyList(), shell.questions)
    }

    @Test
    fun `a link to a text file shows its node selected, not being edited - keys after it are the graph's`() {
        dir.resolve("notes.txt").writeText("one\ntwo\n")
        val view = viewOfA()
        view.g.nodes.first().attributes.other["link"] = "notes.txt"
        assertTrue(view.content.followFileLink())
        assertEquals(listOf(SourceView.NODE_ID), view.g.selectedNodes.map { it.attributes.name })
        view.content.painted()
        assertFalse(view.content.pendingEdit)
        assertEquals(null, view.editing)
    }

    @Test
    fun `a link's type says what it opens -a workspace, a graph as text, or nothing when it is no link's type`() {
        val other = Files.createDirectory(dir.resolve("other"))
        dir.resolve("b.dot").writeText("digraph b {\n  t;\n  u;\n}\n")
        val view = viewOfA()
        val s = view.g.nodes.first()
        s.attributes.other["link-w"] = "other"
        s.attributes.other["link-content-type-w"] = "workspace"
        assertTrue(view.content.followFileLink('w'))
        assertEquals(listOf(other), shell.workspaces)
        assertEquals("a.dot", view.content.currentFile?.fileName.toString())

        s.attributes.other["link-content-type-w"] = "workspace"
        s.attributes.other["link-w"] = "b.dot"
        assertTrue(view.content.followFileLink('w'))
        assertEquals(listOf(other), shell.workspaces)
        assertTrue(shell.messages.last().startsWith("not a directory"), shell.messages.last())

        s.attributes.other["link-content-type-w"] = "terminal"
        assertFalse(view.content.followFileLink('w'))
        assertTrue(shell.messages.last().contains("not a type a link can have"), shell.messages.last())

        s.attributes.other["link"] = "b.dot"
        s.attributes.other["link-content-type"] = "code-dot"
        assertTrue(view.content.followFileLink())
        assertEquals(null, view.content.currentFile)
        assertEquals("code-dot", view.content.sourceView?.let { SourceView.typeOf(it).copy(source = null).attribute })
    }

    @Test
    fun `unsaved changes are kept when the user says no, and lost when they say yes`() {
        val view = viewOfA()
        view.change()
        assertEquals("*a.dot", view.content.title)

        view.content.followFileLink()
        assertEquals("a.dot", view.content.currentFile?.fileName.toString())
        assertEquals(1, shell.questions.size)

        shell.agree = true
        view.content.followFileLink()
        assertEquals("b.dot", view.content.title)
    }

    @Test
    fun `saving a graph without a file asks where to, and cancelling saves nothing`() {
        val view = EditorView(Graph().also { it.add(Node("n", Vector2(0, 0))) }, NoRenderer, shell)
        view.change()
        assertFalse(view.content.saveNow())
        assertEquals("*(untitled)", view.content.title)

        val target = dir.resolve("new.dot")
        shell.saveTo = target
        assertTrue(view.content.saveNow())
        assertEquals("new.dot", view.content.title)
        assertTrue("changed" in target.readText())
    }

    @Test
    fun `a link opened in a tab leaves this view alone and hands the tab its jump list`() {
        val view = viewOfA()
        view.content.followFileLinkInTab()
        assertEquals("a.dot", view.content.title)
        val tab = shell.tabs.single()
        assertEquals("b.dot", tab.content.title)
        assertEquals("t", tab.content.pendingFocus)
        assertEquals(1, tab.content.jumps.size)
    }

    @Test
    fun `a missing link target is reported and the view keeps its file`() {
        a.writeText("digraph a {\n  s [ link=\"gone.dot\" ];\n}\n")
        val view = viewOfA()
        view.content.followFileLink()
        assertEquals("a.dot", view.content.title)
        assertTrue(shell.messages.single().startsWith("Open error"))
    }
}
