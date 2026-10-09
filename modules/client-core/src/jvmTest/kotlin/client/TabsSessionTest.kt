package client

import Graph
import utils.Vector2
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the tabs remember between runs: one window's tabs are closed and another's are opened from the same session file. */
class TabsSessionTest {
    private val dir = createTempDirectory("sge-tabs-session")
    private val a = dir.resolve("a.dot").also { it.writeText("digraph a {\n  s;\n}\n") }
    private val b = dir.resolve("b.dot").also { it.writeText("digraph b {\n  t;\n}\n") }
    private val sessionFile = dir.resolve("state/session.properties")

    private val host = object : TabsHost {
        override fun view(graph: Graph, path: Path?, shell: Shell): EditorView =
            EditorView(path?.let { DotGraphLoader.loadFromFile(it.toString()) } ?: graph, NoRenderer, shell, initialFile = path)
        override fun screen(): Vector2? = null
        override fun leaving(view: EditorView) {}
        override fun tabsChanged() {}
        override fun modesChanged() {}
        override fun exit() {}
    }

    private fun window(): Tabs = Tabs(host, NoShell) { Session(sessionFile) }

    private fun files(tabs: Tabs) = tabs.views.map { it.content.currentFile }

    @Test
    fun `the files, the tab shown and where each view looked come back`() {
        val first = window()
        first.restore()
        first.open(a).viewTransform.matrix = listOf(2.0, 0.0, 0.0, 2.0, 30.0, -40.0)
        first.open(b)
        first.select(first.views.indexOfFirst { it.content.currentFile == a })
        first.saveSession()

        val second = window()
        second.restore()
        assertEquals(listOf(a, b), files(second))
        assertEquals(a, second.current?.content?.currentFile)
        assertEquals(listOf(2.0, 0.0, 0.0, 2.0, 30.0, -40.0), second.current?.viewTransform?.matrix)
        assertTrue(second.views[1].viewTransform.isIdentity)
    }

    @Test
    fun `without a session the workspace directory is browsed, and that tab is not remembered`() {
        val tabs = window()
        tabs.restore()
        assertEquals(1, tabs.views.size)
        assertNull(tabs.current?.content?.currentFile)
        assertTrue(tabs.current!!.content.title.startsWith("browse: "))
        tabs.saveSession()
        assertTrue(Session(sessionFile).load().files.isEmpty())
    }

    @Test
    fun `a file that is gone is left out`() {
        val first = window()
        first.restore()
        first.open(a)
        first.open(b)
        Files.delete(a)

        val second = window()
        second.restore()
        assertEquals(listOf(b), files(second))
    }

    @Test
    fun `opening and closing a tab is remembered at once`() {
        val tabs = window()
        tabs.restore()
        tabs.open(a)
        tabs.open(b)
        assertEquals(listOf(a, b), Session(sessionFile).load().files)
        tabs.close()
        assertEquals(listOf(a), Session(sessionFile).load().files)
    }

    @Test
    fun `restoring does not write the session`() {
        val first = window()
        first.restore()
        first.open(a)
        first.open(b)
        val before = Files.readString(sessionFile)
        Files.delete(b)
        // b is skipped, but the session still names it until a tab is opened or closed
        window().restore()
        assertEquals(before, Files.readString(sessionFile))
    }
}
