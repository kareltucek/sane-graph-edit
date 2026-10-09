@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import graph_tools.Node
import graph_tools.ViewModes
import utils.Vector2
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `:` / `/` line as a widget sees it: what it is told to show, and what the typed text does. */
class CommandLineTest {
    private val hello = Node("Hello", Vector2(100, 100))
    private val world = Node("World", Vector2(300, 100))
    private val view = EditorView(Graph().also { it.add(hello); it.add(world) }, NoRenderer).also { it.size = Vector2(800, 600) }

    /** What the widget shows: the line's text or null when hidden, and the list beside it. */
    private var shown: String? = null
    private var list: String? = null
    private val widget = object : CommandLineWidget {
        override fun show(text: String) {
            shown = text
        }
        override fun hide() {
            shown = null
        }
        override fun showList(text: String) {
            list = text
        }
        override fun hideList() {
            list = null
        }
    }

    private val keys = KeyMapper(KeyMapper.defaultBindings(), Scheduler { _, _ -> {} }).also {
        registerEditingCommands()
        it.commandExecutor = { command, view -> CommandRegistry.execute(command, view) }
        it.commandNames = { CommandRegistry.list() }
    }
    private val line = CommandLine(view, widget) { keys }

    /** As the user typing [text] after what is there. */
    private fun type(text: String) = line.typed(line.text + text)

    @AfterTest
    fun resetModes() {
        ViewModes.unhide = false
    }

    @Test
    fun `a command runs on Enter and the line closes`() {
        line.open(":")
        assertEquals(":", shown)
        type("set unhide")
        line.execute()
        assertTrue(ViewModes.unhide)
        assertNull(shown)
        assertFalse(line.isOpen)
    }

    @Test
    fun `Escape closes the line and runs nothing`() {
        line.open(":")
        type("set unhide")
        line.cancel()
        assertFalse(ViewModes.unhide)
        assertNull(shown)
    }

    @Test
    fun `a search selects what matches while typing, and Escape puts the selection back`() {
        view.g.cleanSelect(setOf(hello))
        line.open("/")
        type("wor")
        assertEquals(setOf(world), view.g.selectedNodes.toSet())
        line.cancel()
        assertEquals(setOf(hello), view.g.selectedNodes.toSet())
    }

    @Test
    fun `Enter keeps the matches of a search`() {
        line.open("/")
        type("o")
        line.execute()
        assertEquals(listOf(hello, world), view.searchState.results)
        assertEquals("o", view.searchState.query)
        assertEquals(setOf(hello, world), view.g.selectedNodes.toSet())
        assertNull(shown)
    }

    @Test
    fun `Tab completes a command name, and not in a search`() {
        line.open(":")
        type("hel")
        line.complete(backward = false)
        assertEquals(":help ", shown)
        line.cancel()
        line.open("/")
        type("hel")
        line.complete(backward = false)
        assertEquals("/", shown)
    }

    @Test
    fun `the list of completions goes away with the line`() {
        line.open(":")
        type("hel")
        line.listCompletions()
        assertEquals("help", list)
        line.cancel()
        assertNull(list)
    }

    @Test
    fun `the arrows walk through earlier lines and back to what was being typed`() {
        listOf(":set unhide", ":set nounhide").forEach {
            line.open(":")
            line.typed(it)
            line.execute()
        }
        line.open(":")
        type("he")
        line.historyUp()
        assertEquals(":set nounhide", shown)
        line.historyUp()
        line.historyUp()
        assertEquals(":set unhide", shown)
        line.historyDown()
        line.historyDown()
        assertEquals(":he", shown)
    }

    @Test
    fun `the status line names the modes that are on`() {
        assertEquals("", StatusLine.text(view, keys, stylePickerOpen = false))
        ViewModes.unhide = true
        assertEquals("[unhide]", StatusLine.text(view, keys, stylePickerOpen = false))
        view.g.cleanSelect(setOf(hello, world))
        hello.attributes.nodeScale = 1.5
        assertEquals("[unhide]    node scale: 0.00–1.50 (2 nodes)", StatusLine.text(view, keys, stylePickerOpen = true))
    }

    @Test
    fun `two decimals`() {
        assertEquals("0.00", StatusLine.twoDecimals(0.0))
        assertEquals("1.25", StatusLine.twoDecimals(1.254))
        assertEquals("-0.05", StatusLine.twoDecimals(-0.05))
        assertEquals("12.30", StatusLine.twoDecimals(12.3))
    }
}
