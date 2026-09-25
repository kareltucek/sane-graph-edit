package ui

import graph_tools.Node
import utils.Vector2
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileLinkTest {

    private val file = Paths.get("/data/graphs/a.dot")

    private fun target(path: String?, node: String? = null) = FileLink.Target(path?.let { Paths.get(it) }, node)

    @Test
    fun `paths resolve relative to the containing file, home and absolute`() {
        assertEquals(target("/data/graphs/sub/b.dot"), FileLink.resolve("sub/b.dot", file))
        assertEquals(target("/data/c.dot"), FileLink.resolve("../c.dot", file))
        assertEquals(target("/x/y.dot"), FileLink.resolve("/x/y.dot", file))
        assertEquals(target(System.getProperty("user.home") + "/g.dot"), FileLink.resolve("~/g.dot", file))
    }

    @Test
    fun `a node id after the last colon`() {
        assertEquals(target("/data/graphs/b.dot", "n12"), FileLink.resolve("b.dot:n12", file))
        assertEquals(target("/x/y.dot", "my_node"), FileLink.resolve("/x/y.dot:my_node", file))
        assertEquals(target(null, "n3"), FileLink.resolve(":n3", file))
        // a trailing colon or a slash after it is part of the path
        assertEquals(target("/data/graphs/b.dot:"), FileLink.resolve("b.dot:", file))
        assertEquals(target("/data/graphs/x:y/b.dot"), FileLink.resolve("x:y/b.dot", file))
    }

    @Test
    fun `an existing file with a colon in its name is not split`() {
        val dir = Files.createTempDirectory("links")
        dir.resolve("a:b.dot").writeText("digraph g {}")
        val inDir = dir.resolve("main.dot")
        assertEquals(FileLink.Target(dir.resolve("a:b.dot"), null), FileLink.resolve("a:b.dot", inDir))
    }

    @Test
    fun `link-letter attributes, with link as link-f`() {
        val n = Node("x", Vector2(0, 0))
        n.attributes.other["link"] = "plain.dot"
        n.attributes.other["link-d"] = "docs.dot"
        assertEquals("plain.dot", FileLink.of(n, 'f'))
        assertEquals("docs.dot", FileLink.of(n, 'd'))
        assertNull(FileLink.of(n, 'x'))
        n.attributes.other["link-f"] = "explicit.dot"
        assertEquals("explicit.dot", FileLink.of(n, 'f'))  // link-f wins over the alias
        n.attributes.other["linkage"] = "not a link"
        assertEquals(listOf('d', 'f'), FileLink.letters(n))
    }

    @Test
    fun `a letter is followable unless g-letter is taken`() {
        val mapper = KeyMapper(KeyMapper.defaultBindings())
        assertTrue(mapper.followsLink('d'))
        assertTrue(mapper.followsLink('f'))   // gf = follow-file-link
        assertFalse(mapper.followsLink('t'))  // gt = tabnext
        assertFalse(mapper.followsLink('F'))  // gF = follow-file-link-in-tab
    }
}
