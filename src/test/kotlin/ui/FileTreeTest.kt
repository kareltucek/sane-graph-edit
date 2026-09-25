package ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTreeTest {

    private fun tree(): Path {
        val root = Files.createTempDirectory("filetree")
        root.resolve("graphs/deep").createDirectories()
        root.resolve("graphs/deep/x.dot").writeText("digraph g {}")
        root.resolve("graphs/y.dot").writeText("digraph g {}")
        root.resolve("graphs/notes.txt").writeText("")
        root.resolve("empty/nothing").createDirectories()
        root.resolve(".git").createDirectories()
        root.resolve(".git/z.dot").writeText("digraph g {}")
        return root
    }

    @Test
    fun `browse shows parents, dirs and dot files, with links`() {
        val root = tree()
        val g = FileTree.build(root)
        val links = g.nodes.associate { it.attributes.text to it.attributes.other[FileLink.ATTRIBUTE] }

        assertEquals(root.resolve("graphs/deep/x.dot").toString(), links["x.dot"])
        assertEquals(root.resolve("graphs").toString(), links["graphs/"])
        assertEquals("/", links["/"])
        assertTrue("y.dot" in links)
        assertFalse("notes.txt" in links)  // not a graph
        assertFalse("empty/" in links)     // no .dot below
        assertFalse(".git/" in links)      // hidden
        assertEquals(g.nodes.size - 1, g.edges.size)  // a tree
    }

    @Test
    fun `layout goes left to right with the browsed directory at the origin`() {
        val root = tree()
        val g = FileTree.build(root)
        val byName = g.nodes.associateBy { it.attributes.text }
        val current = byName.getValue("${root.fileName}/")

        assertEquals(0.0, current.position.x, 1e-9)
        assertEquals(0.0, current.position.y, 1e-9)
        assertTrue(byName.getValue("/").position.x < current.position.x)
        assertTrue(current.position.x < byName.getValue("graphs/").position.x)
        assertTrue(byName.getValue("graphs/").position.x < byName.getValue("x.dot").position.x)
    }
}
