package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseArgsTest {
    @Test
    fun `no WebSocket unless asked for`() {
        val cli = parseArgs(arrayOf("a.dot"))
        assertNull(cli.webSocketPort)
        assertFalse(cli.attach)
        assertEquals("a.dot", cli.file)
    }

    @Test
    fun `--ws takes a port, 0 included`() {
        assertEquals(8765, parseArgs(arrayOf("--ws", "8765", "a.dot")).webSocketPort)
        assertEquals(0, parseArgs(arrayOf("--ws", "0")).webSocketPort)
        assertFalse(parseArgs(arrayOf("--ws", "0")).help)
    }

    @Test
    fun `--ws without a usable port, or together with --attach, ends in the usage text`() {
        assertTrue(parseArgs(arrayOf("--ws")).help)
        assertTrue(parseArgs(arrayOf("--ws", "a.dot")).help)
        assertTrue(parseArgs(arrayOf("--ws", "70000")).help)
        assertTrue(parseArgs(arrayOf("--attach", "--ws", "0")).help)
    }
}
