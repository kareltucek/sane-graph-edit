@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SectionsTest {
    private val text = """
        # Manual

        intro

        ## Using it

        ### Depth: `z`

        nodes have a depth

        ```sh
        # not a heading
        ```

        ### Keys ###

        keys

        ## Depth of field

        last
    """.trimIndent().lines()

    private fun find(name: String) = Sections.find(text, name)

    @Test
    fun `a section is its heading and what is under it, up to the next heading of its level or above`() {
        assertEquals(FileLink.Lines(7, 13), find("Depth: `z`"))
        assertEquals(FileLink.Lines(5, 17), find("using it"))
        assertEquals(FileLink.Lines(1, 21), find("Manual"))
        assertEquals(FileLink.Lines(15, 17), find("Keys"))
    }

    @Test
    fun `a whole heading is preferred to one that starts with the name, and that to one that has it`() {
        assertEquals(FileLink.Lines(7, 13), find("Depth"))
        assertEquals(FileLink.Lines(19, 21), find("Depth of field"))
        assertEquals(FileLink.Lines(19, 21), find("field"))
    }

    @Test
    fun `there is no section of a name no heading has, nor of a line in a fenced block`() {
        assertNull(find("nothing"))
        assertNull(find("not a heading"))
    }
}
