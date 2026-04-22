package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyNotationTest {

    // --- tokenize ---

    @Test
    fun `tokenize plain chars`() {
        assertEquals(listOf("t", "w", "0"), KeyNotation.tokenize("tw0"))
    }

    @Test
    fun `tokenize angle-bracket tokens`() {
        assertEquals(listOf("<C-s>"), KeyNotation.tokenize("<C-s>"))
        assertEquals(listOf("<CR>"), KeyNotation.tokenize("<CR>"))
        assertEquals(listOf("<C-S-z>"), KeyNotation.tokenize("<C-S-z>"))
    }

    @Test
    fun `tokenize mixed sequence`() {
        assertEquals(
            listOf(":", "n", "e", "x", "t", "-", "t", "a", "b", "<CR>"),
            KeyNotation.tokenize(":next-tab<CR>"),
        )
    }

    @Test
    fun `tokenize multiple angle tokens`() {
        assertEquals(
            listOf("<C-a>", "<Esc>"),
            KeyNotation.tokenize("<C-a><Esc>"),
        )
    }

    @Test
    fun `tokenize empty string`() {
        assertEquals(emptyList(), KeyNotation.tokenize(""))
    }

    @Test
    fun `tokenize unclosed angle bracket treated as literal`() {
        assertEquals(listOf("<", "a", "b"), KeyNotation.tokenize("<ab"))
    }

    // --- parse ---

    @Test
    fun `parse plain char`() {
        val p = KeyNotation.parse("a")!!
        assertEquals("a", p.keyName)
        assertEquals(false, p.ctrl)
        assertEquals(false, p.shift)
    }

    @Test
    fun `parse Ctrl combo`() {
        val p = KeyNotation.parse("<C-s>")!!
        assertEquals("s", p.keyName)
        assertEquals(true, p.ctrl)
        assertEquals(false, p.shift)
    }

    @Test
    fun `parse Ctrl+Shift combo`() {
        val p = KeyNotation.parse("<C-S-z>")!!
        assertEquals("z", p.keyName)
        assertEquals(true, p.ctrl)
        assertEquals(true, p.shift)
    }

    @Test
    fun `parse special key`() {
        val p = KeyNotation.parse("<CR>")!!
        assertEquals("CR", p.keyName)
        assertEquals(false, p.ctrl)
        assertEquals(false, p.shift)
    }

    @Test
    fun `parse Ctrl+special`() {
        val p = KeyNotation.parse("<C-Tab>")!!
        assertEquals("Tab", p.keyName)
        assertEquals(true, p.ctrl)
        assertEquals(false, p.shift)
    }

    @Test
    fun `parse rejects multi-char without brackets`() {
        assertNull(KeyNotation.parse("ab"))
    }

    // --- fromKeyTyped ---

    @Test
    fun `fromKeyTyped plain letter`() {
        assertEquals("e", KeyNotation.fromKeyTyped('e'))
        assertEquals("E", KeyNotation.fromKeyTyped('E'))
    }

    @Test
    fun `fromKeyTyped special chars return null`() {
        // Space, Tab, CR, LF are dispatched via keyPressed
        // (specialKeyName). fromKeyTyped must drop them so the
        // mapping doesn't fire twice per keystroke.
        assertNull(KeyNotation.fromKeyTyped(' '))
        assertNull(KeyNotation.fromKeyTyped('\t'))
        assertNull(KeyNotation.fromKeyTyped('\n'))
        assertNull(KeyNotation.fromKeyTyped('\r'))
    }

    @Test
    fun `fromKeyTyped control chars return null`() {
        // Ctrl+A produces char 0x01
        assertNull(KeyNotation.fromKeyTyped('\u0001'))
    }

    // --- normalization ---

    @Test
    fun `tokenize normalizes ESC variants`() {
        assertEquals(listOf("<Esc>"), KeyNotation.tokenize("<ESC>"))
        assertEquals(listOf("<Esc>"), KeyNotation.tokenize("<esc>"))
        assertEquals(listOf("<Esc>"), KeyNotation.tokenize("<Escape>"))
    }

    @Test
    fun `tokenize normalizes CR variants`() {
        assertEquals(listOf("<CR>"), KeyNotation.tokenize("<cr>"))
        assertEquals(listOf("<CR>"), KeyNotation.tokenize("<Enter>"))
        assertEquals(listOf("<CR>"), KeyNotation.tokenize("<Return>"))
    }

    @Test
    fun `tokenize normalizes modifier casing`() {
        assertEquals(listOf("<C-s>"), KeyNotation.tokenize("<c-s>"))
        assertEquals(listOf("<C-S-z>"), KeyNotation.tokenize("<c-s-z>"))
    }

    @Test
    fun `tokenize preserves letter case in key name`() {
        // <C-s> and <C-S> should stay distinct
        assertEquals(listOf("<C-s>"), KeyNotation.tokenize("<C-s>"))
        assertEquals(listOf("<C-S>"), KeyNotation.tokenize("<C-S>"))
    }

    @Test
    fun `mixed sequence with non-canonical notation normalizes`() {
        assertEquals(
            listOf("t", "w", "0", "<Esc>"),
            KeyNotation.tokenize("tw0<ESC>"),
        )
    }

    // --- round-trip: tokenize then parse ---

    @Test
    fun `tokenize then parse round-trips`() {
        val input = "<C-S-z>a<CR>"
        val tokens = KeyNotation.tokenize(input)
        assertEquals(3, tokens.size)

        val parsed = tokens.map { KeyNotation.parse(it)!! }
        assertTrue(parsed[0].ctrl && parsed[0].shift && parsed[0].keyName == "z")
        assertTrue(!parsed[1].ctrl && !parsed[1].shift && parsed[1].keyName == "a")
        assertTrue(!parsed[2].ctrl && !parsed[2].shift && parsed[2].keyName == "CR")
    }
}
