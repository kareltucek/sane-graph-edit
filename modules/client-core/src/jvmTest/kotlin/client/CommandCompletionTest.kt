package client

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandCompletionTest {

    private val commands = KeyMapper.BUILTIN_COMMANDS + listOf("tabnew", "tabclose", "tabedit", "save")

    private fun candidates(line: String) = CommandCompletion.complete(line, commands, cwd).candidates

    private val cwd = Files.createTempDirectory("compl").also {
        it.resolve("graphs/deep").createDirectories()
        it.resolve("graphs/a.dot").writeText("")
        it.resolve("gamma.dot").writeText("")
        it.resolve(".hidden").writeText("")
    }

    @Test
    fun `command names`() {
        assertEquals(listOf("tabclose", "tabedit", "tabnew"), candidates("tab"))
        assertEquals(listOf("se", "set", "source", "suns"), candidates("s").filter { it != "save" })
    }

    @Test
    fun `set options, with no-prefixes and values`() {
        assertEquals(listOf("show-children", "show-children!", "show-parents", "show-parents!"), candidates("set sh"))
        assertEquals(listOf("nodiscard", "nodynamic"), candidates("se nod"))
        assertTrue("picker=" in candidates("set p"))
    }

    @Test
    fun `paths for e, directories only for browse, export formats`() {
        assertEquals(listOf("gamma.dot", "graphs/"), candidates("e g"))
        assertEquals(listOf("graphs/a.dot", "graphs/deep/"), candidates("e graphs/"))
        assertEquals(listOf("graphs/"), candidates("browse g"))
        assertEquals(listOf("graphs/"), candidates("cd g"))
        assertEquals(listOf(".hidden"), candidates("e .h"))
        assertEquals(listOf("svg"), candidates("export s"))
        assertEquals(listOf("gamma.dot"), candidates("export dot ga"))
        assertEquals(emptyList(), candidates("map g"))
    }

    @Test
    fun `the word to replace starts after the last space`() {
        assertEquals(4, CommandCompletion.complete("set sh", commands, cwd).wordStart)
        assertEquals("show-", CommandCompletion.commonPrefix(candidates("set sh")))
    }
}
