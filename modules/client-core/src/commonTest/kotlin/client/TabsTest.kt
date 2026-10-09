@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import graph_tools.AddNodeCommand
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The tabs of a window without a window: an application that records what it is told and answers as scripted. */
class TabsTest {
    private val left = mutableListOf<EditorView>()
    private var changes = 0
    private var exited = false
    private var agree = false
    private val questions = mutableListOf<String>()

    private val host = object : TabsHost {
        override fun view(graph: Graph, path: Path?, shell: Shell): EditorView = EditorView(graph, NoRenderer, shell, initialFile = path)
        override fun screen(): Vector2? = null
        override fun leaving(view: EditorView) {
            left += view
        }
        override fun tabsChanged() {
            changes++
        }
        override fun modesChanged() {}
        override fun exit() {
            exited = true
        }
    }

    private val dialogs = object : Dialogs by NoShell {
        override fun confirm(title: String, question: String): Boolean {
            questions += question
            return agree
        }
    }

    private val tabs = Tabs(host, dialogs)

    private fun tab(text: String): EditorView = tabs.newTab(Graph().also { it.add(Node(text, Vector2(0, 0))) })

    private fun change(view: EditorView) = view.g.commit(AddNodeCommand(view.g, Node("more", Vector2(50, 50))))

    @Test
    fun `a new tab is the one shown, and the one before is told it is left`() {
        val a = tab("a")
        val b = tab("b")
        assertEquals(listOf(a, b), tabs.views)
        assertSame(b, tabs.current)
        assertEquals(listOf(a), left)
    }

    @Test
    fun `next and previous go round`() {
        val a = tab("a")
        val b = tab("b")
        val c = tab("c")
        tabs.selectNext()
        assertSame(a, tabs.current)
        tabs.selectPrevious()
        assertSame(c, tabs.current)
        tabs.select(1)
        assertSame(b, tabs.current)
    }

    @Test
    fun `closing a tab shows a neighbour, and the last one is emptied instead`() {
        val a = tab("a")
        val b = tab("b")
        val c = tab("c")
        tabs.select(1)
        assertTrue(tabs.close())
        assertEquals(listOf(a, c), tabs.views)
        assertSame(c, tabs.current)
        assertTrue(tabs.close(0))
        assertSame(c, tabs.current)
        assertTrue(tabs.close())
        assertEquals(listOf(c), tabs.views)
        assertTrue(c.g.nodes.isEmpty())
        assertTrue(b !in tabs.views)
    }

    @Test
    fun `a tab with unsaved changes asks before it closes`() {
        tab("a")
        val b = tab("b")
        change(b)
        assertFalse(tabs.close())
        assertEquals(2, tabs.views.size)
        assertEquals(1, questions.size)
        agree = true
        assertTrue(tabs.close())
        assertEquals(1, tabs.views.size)
        // forced: no question
        change(tabs.views.single())
        assertTrue(tabs.close(force = true))
        assertEquals(2, questions.size)
    }

    @Test
    fun `quitting the last tab ends the application, with the user's agreement about what is unsaved`() {
        val a = tab("a")
        change(a)
        tabs.quitCurrent(force = false)
        assertFalse(exited)
        agree = true
        tabs.quitCurrent(force = false)
        assertTrue(exited)
    }

    @Test
    fun `quitting one of several tabs closes it`() {
        val a = tab("a")
        tab("b")
        tabs.quitCurrent(force = false)
        assertFalse(exited)
        assertEquals(listOf(a), tabs.views)
    }

    @Test
    fun `the tab commands reach the tabs of the view they are run on`() {
        registerTabCommands()
        val a = tab("a")
        val b = tab("b")
        CommandRegistry.execute("tabnext", b)
        assertSame(a, tabs.current)
        CommandRegistry.execute("tabclose", a)
        assertEquals(listOf(b), tabs.views)
    }
}
