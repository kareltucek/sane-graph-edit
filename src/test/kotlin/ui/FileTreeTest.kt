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

    /** Large enough that nothing is cut. */
    private val bigScreen = utils.Vector2(10000.0, 10000.0)

    @Test
    fun `browse shows parents, dirs and dot files, with links`() {
        val root = tree()
        val g = FileTree.build(root, bigScreen)
        val links =g.nodes.associate { it.attributes.text to it.attributes.other[FileLink.ATTRIBUTE] }

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
    fun `gf on directory nodes re-renders with parents, also for the root`() {
        val root = tree()
        val gv = GraphView(initialGraph = FileTree.build(root, bigScreen))
        gv.setSize(3000, 2000)
        fun node(label: String) = gv.g.nodes.first { it.attributes.text == label }
        fun labels() = gv.g.nodes.map { it.attributes.text }.toSet()

        gv.g.cleanSelect(setOf(node("graphs/")))
        gv.followFileLink()
        assertTrue("/" in labels(), "parents shown after gf: ${labels()}")
        assertTrue("${root.fileName}/" in labels())
        assertTrue("deep/" in labels())

        gv.g.cleanSelect(setOf(node("/")))
        gv.followFileLink()
        assertTrue("tmp/" in labels() || labels().size > 1, "root browsed: ${labels()}")
        assertEquals("browse: /", gv.untitledName)
    }

    @Test
    fun `layout goes left to right, centred on the origin`() {
        val root = tree()
        val g = FileTree.build(root, bigScreen)
        val byName = g.nodes.associateBy { it.attributes.text }
        val current = byName.getValue("${root.fileName}/")

        assertTrue(byName.getValue("/").position.x < current.position.x)
        assertTrue(current.position.x < byName.getValue("graphs/").position.x)
        assertTrue(byName.getValue("graphs/").position.x < byName.getValue("x.dot").position.x)

        val left = g.nodes.minOf { it.position.x - it.cache.shapeBounds.x / 2 }
        val right = g.nodes.maxOf { it.position.x + it.cache.shapeBounds.x / 2 }
        assertEquals(0.0, left + right, 1e-6)
    }

    @Test
    fun `files are one column right of their directory, subdirectories two`() {
        val root = tree()
        root.resolve("top.dot").writeText("digraph g {}")
        val byName = FileTree.build(root, bigScreen).nodes.associateBy { it.attributes.text }
        fun leftEdge(name: String) = byName.getValue(name).let { it.position.x - it.cache.shapeBounds.x / 2 }

        assertTrue(leftEdge("top.dot") < leftEdge("graphs/"))
        assertTrue(leftEdge("graphs/") < leftEdge("y.dot"))
        assertTrue(leftEdge("y.dot") < leftEdge("deep/"))
        // a directory's files and subdirectories start on the same row
        assertEquals(byName.getValue("y.dot").position.y, byName.getValue("deep/").position.y, 1e-9)
    }

    @Test
    fun `the tree is cut to fit the screen, keeping one level`() {
        val root = tree()
        val labels = FileTree.build(root, utils.Vector2(10.0, 10.0)).nodes.map { it.attributes.text }.toSet()

        assertTrue("graphs/ …" in labels, "last level is unexpanded: $labels")
        assertFalse("y.dot" in labels)
        assertFalse("deep/" in labels)

        val all = FileTree.build(root, bigScreen).nodes.map { it.attributes.text }.toSet()
        assertTrue("x.dot" in all && "graphs/" in all, "everything fits: $all")
    }
}
