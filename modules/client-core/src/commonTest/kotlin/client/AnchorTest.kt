@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import client.Anchor.Bound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnchorTest {
    private val text = listOf("head", "", "Section A", "a1", "", "Section B", "b1", "b2", "", "Section C", "c1")

    @Test
    fun `what git -L writes, with a pattern in it`() {
        assertEquals(Anchor(Bound.Pattern("^Section B"), Bound.Pattern("^$")), Anchor.parse("/^Section B/,/^$/"))
        assertEquals(Anchor(Bound.Pattern("b1"), Bound.Count(2)), Anchor.parse("/b1/,+2"))
        assertEquals(Anchor(Bound.Line(6), Bound.Pattern("^$")), Anchor.parse("6,/^$/"))
        assertEquals(Anchor(Bound.Pattern("a/b"), Bound.Line(9)), Anchor.parse("/a\\/b/,9"))
        // as it is written again
        listOf("/^Section B/,/^$/", "/b1/,+2", "6,/^$/", "/a\\/b/,9").forEach { assertEquals(it, Anchor.parse(it).toString()) }
    }

    @Test
    fun `what is not one`() {
        listOf("10,20", "+3,/a/", "/a/", "/a/,", "/(/,/b/", "//,/b/", "/a/,/b", "/a/,/b/x", "n5", "").forEach { assertNull(Anchor.parse(it), it) }
    }

    @Test
    fun `the start is looked for from the first line, the end after the start`() {
        assertEquals(FileLink.Lines(6, 9), Anchor.parse("/^Section B/,/^$/")!!.find(text))
        assertEquals(FileLink.Lines(7, 8), Anchor.parse("/b1/,+2")!!.find(text))
        assertEquals(FileLink.Lines(3, 5), Anchor.parse("3,/^$/")!!.find(text))
        // a number past the end is the last line
        assertEquals(FileLink.Lines(10, 11), Anchor.parse("/^Section C/,99")!!.find(text))
        assertEquals(FileLink.Lines(10, 11), Anchor.parse("/^Section C/,+5")!!.find(text))
    }

    @Test
    fun `lines a pattern does not find are none`() {
        assertNull(Anchor.parse("/^Section D/,+2")!!.find(text))
        assertNull(Anchor.parse("/^Section C/,/^Section A/")!!.find(text))
        assertNull(Anchor.parse("/^Section C/,3")!!.find(text))
    }
}
