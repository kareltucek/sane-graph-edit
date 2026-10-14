package client

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspacesTest {
    private val root = Files.createTempDirectory("workspaces")
    private val config = root.resolve("config")
    private val was = Workspace.dir

    @AfterTest
    fun back() {
        Workspace.dir = was
    }

    @Test
    fun `the workspaces with their graphs, the last shown first, and a gone one left out`() {
        val a = root.resolve("a").createDirectories()
        val b = root.resolve("b").createDirectories()
        val gone = root.resolve("gone").createDirectories()
        Workspace.session(a, config).save(Session.Snapshot(listOf(a.resolve("one.dot"), a.resolve("two.dot")), a.resolve("two.dot")))
        Workspace.session(b, config).save(Session.Snapshot(emptyList(), null))
        Workspace.session(gone, config).save(Session.Snapshot(listOf(gone.resolve("x.dot")), null))
        Files.delete(gone)
        Workspace.dir = b

        val g = Workspaces.build(config)
        val link = { text: String -> g.nodes.single { it.attributes.text == text }.attributes.other.let { it["link"] to it["link-content-type"] } }
        assertEquals(setOf(a.toString(), b.toString(), "two.dot", "one.dot"), g.nodes.map { it.attributes.text }.toSet())
        assertEquals(a.toString() to "workspace", link(a.toString()))
        assertEquals(a.resolve("one.dot").toString() to "graph", link("one.dot"))
        // the one last shown on the workspace's row, the other below it
        val y = { text: String -> g.nodes.single { it.attributes.text == text }.position.y }
        assertEquals(listOf(y(a.toString()), y(a.toString())), listOf(y("two.dot"), y(a.toString())))
        assertEquals(true, y("one.dot") > y("two.dot"))
        assertEquals(listOf("two.dot", "one.dot"), g.findOutEdges(g.nodes.single { it.attributes.text == a.toString() }).map { it.dst.attributes.text }.sortedBy { y(it) })
        assertEquals(b.toString(), g.lastActiveNode?.attributes?.text)
    }

    @Test
    fun `a workspace's name is its path, and back`() {
        val dir = Fs.path("/home/me/100%/x")
        assertEquals(dir, Workspace.decode(Workspace.encode(dir)))
        assertEquals(null, Workspace.decode("not-one"))
    }
}
