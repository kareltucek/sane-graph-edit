@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import kotlin.test.Test
import kotlin.test.assertEquals

class LineDiffTest {
    private fun hunks(before: String, after: String): List<String> =
        LineDiff.of(before.split(","), after.split(",")).map { "${it.from}-${it.to}:${it.lines.joinToString("+")}" }

    @Test
    fun `lines put in, taken out and changed are where they are`() {
        assertEquals(emptyList(), hunks("a,b,c", "a,b,c"))
        assertEquals(listOf("1-1:x"), hunks("a,b,c", "a,x,b,c"))
        assertEquals(listOf("1-2:"), hunks("a,b,c", "a,c"))
        assertEquals(listOf("1-2:x"), hunks("a,b,c", "a,x,c"))
    }

    @Test
    fun `changes in several places are several hunks`() {
        assertEquals(listOf("1-1:x", "3-3:y+z"), hunks("a,b,c,d", "a,x,b,c,y,z,d"))
        assertEquals(listOf("0-1:", "3-4:w"), hunks("a,b,c,d,e", "b,c,w,e"))
    }

    @Test
    fun `all of it changed is one hunk`() {
        assertEquals(listOf("0-2:x+y+z"), hunks("a,b", "x,y,z"))
        assertEquals(listOf("0-0:x"), hunks("", "x,"))
    }
}
