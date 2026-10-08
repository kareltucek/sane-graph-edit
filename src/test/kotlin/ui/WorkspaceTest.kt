package ui

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WorkspaceTest {

    private val config = Paths.get("/cfg")

    @Test
    fun `each directory has its own state dir`() {
        assertEquals(
            Paths.get("/cfg/workspaces/%2Fhome%2Fme%2Fgraphs"),
            Workspace.stateDir(Paths.get("/home/me/graphs"), config),
        )
        assertEquals(Paths.get("/cfg/workspaces/%2F"), Workspace.stateDir(Paths.get("/"), config))
    }

    @Test
    fun `the directory is normalized`() {
        assertEquals(Workspace.encode(Paths.get("/home/me")), Workspace.encode(Paths.get("/home/me/graphs/..")))
    }

    @Test
    fun `a percent sign in a name doesn't collide with a separator`() {
        assertNotEquals(Workspace.encode(Paths.get("/a%2Fb")), Workspace.encode(Paths.get("/a/b")))
    }

    @Test
    fun `relative paths are relative to the workspace`() {
        assertEquals(Workspace.dir.resolve("a.dot"), Workspace.resolve(Paths.get("sub/../a.dot")))
        assertEquals(Paths.get("/tmp/a.dot"), Workspace.resolve(Paths.get("/tmp/a.dot")))
    }
}
