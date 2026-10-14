package client

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class FileTreeTest {
    @Test
    fun `graphs and markdown files are shown, other files and directories without either are not`() {
        val dir = Files.createTempDirectory("tree")
        dir.resolve("a.dot").writeText("digraph a {}\n")
        dir.resolve("b.md").writeText("# b\n")
        dir.resolve("c.txt").writeText("c\n")
        dir.resolve("notes").createDirectories().resolve("d.md").writeText("# d\n")
        dir.resolve("other").createDirectories().resolve("e.txt").writeText("e\n")
        val labels = FileTree.build(dir).nodes.map { it.attributes.text }.toSet()
        assertEquals(listOf(true, true, true, true), listOf("a.dot", "b.md", "notes/", "d.md").map { it in labels })
        assertEquals(listOf(false, false, false), listOf("c.txt", "other/", "e.txt").map { it in labels })
    }
}
