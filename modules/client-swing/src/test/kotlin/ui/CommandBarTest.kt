package ui

import Graph
import graph_tools.Node
import graph_tools.ViewModes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import utils.Vector2
import java.awt.event.KeyEvent

/** The Swing text field of the `:` line: that typing and keys in it reach the line, and that it shows and hides. */
class CommandBarTest {
    private val hello = Node("Hello", Vector2(100, 100))
    private val gv = GraphView(initialGraph = Graph().also { it.add(hello) }).also {
        registerAllCommands()
        it.keyMapper = applicationKeyMapper()
    }
    private val bar = CommandBar(gv).also {
        gv.commandBar = it
        gv.add(it)
    }

    private fun press(keyCode: Int) {
        val e = KeyEvent(bar, KeyEvent.KEY_PRESSED, 0, 0, keyCode, KeyEvent.CHAR_UNDEFINED)
        bar.keyListeners.forEach { it.keyPressed(e) }
    }

    @AfterEach
    fun resetModes() {
        ViewModes.unhide = false
    }

    @Test
    fun `a command typed into the bar runs on Enter and the bar hides`() {
        bar.open(":")
        assertTrue(bar.isVisible)
        assertEquals(":", bar.text)
        bar.text = ":set unhide"
        press(KeyEvent.VK_ENTER)
        assertTrue(ViewModes.unhide)
        assertFalse(bar.isVisible)
        assertEquals("", bar.text)
    }

    @Test
    fun `typing a search selects, Escape hides the bar`() {
        bar.open("/")
        bar.text = "/hel"
        assertEquals(setOf(hello), gv.g.selectedNodes.toSet())
        press(KeyEvent.VK_ESCAPE)
        assertFalse(bar.isVisible)
        assertTrue(gv.g.selectedNodes.isEmpty())
    }

    @Test
    fun `Tab completes in the bar`() {
        bar.open(":")
        bar.text = ":hel"
        press(KeyEvent.VK_TAB)
        assertEquals(":help ", bar.text)
    }
}
