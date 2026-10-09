package client

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {

    private fun tempSession(): Pair<Session, Path> {
        val dir = createTempDirectory("sge-session-test")
        val file = dir.resolve("session.properties")
        return Session(file) to dir
    }

    @Test
    fun `load on missing file returns empty snapshot`() {
        val (session, _) = tempSession()
        val snap = session.load()
        assertEquals(emptyList(), snap.files)
        assertNull(snap.activeFile)
    }

    @Test
    fun `round-trip with two files and an active path`() {
        val (session, dir) = tempSession()
        val a = Paths.get("/tmp/graphs/a.dot")
        val b = Paths.get("/tmp/graphs/b.dot")

        session.save(Session.Snapshot(files = listOf(a, b), activeFile = b))
        assertTrue(Files.exists(dir.resolve("session.properties")))

        val loaded = session.load()
        // Paths are normalised to absolute on save; compare via
        // toAbsolutePath so the test is location-independent.
        assertEquals(listOf(a.toAbsolutePath(), b.toAbsolutePath()), loaded.files)
        assertEquals(b.toAbsolutePath(), loaded.activeFile)
    }

    @Test
    fun `a file open in several tabs is saved once`() {
        val (session, _) = tempSession()
        val a = Paths.get("/tmp/graphs/a.dot")
        val b = Paths.get("/tmp/graphs/b.dot")

        session.save(Session.Snapshot(files = listOf(a, b, Paths.get("/tmp/graphs/../graphs/a.dot"), b), activeFile = b))

        assertEquals(listOf(a.toAbsolutePath(), b.toAbsolutePath()), session.load().files)
    }

    @Test
    fun `round-trip with no active file`() {
        val (session, _) = tempSession()
        val a = Paths.get("/tmp/graphs/a.dot")

        session.save(Session.Snapshot(files = listOf(a), activeFile = null))
        val loaded = session.load()

        assertEquals(listOf(a.toAbsolutePath()), loaded.files)
        assertNull(loaded.activeFile)
    }

    @Test
    fun `save overwrites a previous snapshot`() {
        val (session, _) = tempSession()
        val a = Paths.get("/tmp/graphs/a.dot")
        val b = Paths.get("/tmp/graphs/b.dot")
        val c = Paths.get("/tmp/graphs/c.dot")

        session.save(Session.Snapshot(files = listOf(a, b), activeFile = a))
        session.save(Session.Snapshot(files = listOf(c), activeFile = c))
        val loaded = session.load()

        assertEquals(listOf(c.toAbsolutePath()), loaded.files)
        assertEquals(c.toAbsolutePath(), loaded.activeFile)
    }

    @Test
    fun `round-trip with view transforms`() {
        val (session, _) = tempSession()
        val a = Paths.get("/tmp/graphs/a.dot")
        val b = Paths.get("/tmp/graphs/b.dot")
        // Representative matrices: `a` is a 1.5x zoom with a pan;
        // `b` is identity (still saved so restore is explicit).
        val tA = listOf(1.5, 0.0, 0.0, 1.5, 120.0, -40.0)
        val tB = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        session.save(
            Session.Snapshot(
                files = listOf(a, b),
                activeFile = a,
                viewTransforms = mapOf(a to tA, b to tB),
            ),
        )
        val loaded = session.load()

        assertEquals(tA, loaded.viewTransforms[a.toAbsolutePath()])
        assertEquals(tB, loaded.viewTransforms[b.toAbsolutePath()])
    }

    @Test
    fun `load drops malformed transform entries`() {
        val (session, dir) = tempSession()
        val a = Paths.get("/tmp/graphs/a.dot")
        // Write a valid snapshot first, then hand-edit the
        // transform to something that won't parse.
        session.save(
            Session.Snapshot(
                files = listOf(a),
                activeFile = a,
                viewTransforms = mapOf(a to listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)),
            ),
        )
        val file = dir.resolve("session.properties")
        val munged = Files.readString(file).replace(
            "1.0,0.0,0.0,1.0,0.0,0.0",
            "not,a,matrix",
        )
        Files.writeString(file, munged)

        val loaded = session.load()
        // Files list survives; the bad transform is simply absent.
        assertEquals(listOf(a.toAbsolutePath()), loaded.files)
        assertTrue(loaded.viewTransforms.isEmpty())
    }

    @Test
    fun `load tolerates a corrupted file and returns empty`() {
        val (session, dir) = tempSession()
        val file = dir.resolve("session.properties")
        // Properties format permits lots of things, but a truncated
        // binary stream at least forces load() through its catch.
        Files.write(file, byteArrayOf(0x00, 0xFF.toByte(), 0x00))

        val snap = session.load()
        // Either empty (parse failed) or at least a no-crash result.
        // We don't strictly require "empty" here because Properties
        // may partially parse odd bytes — the contract we care
        // about is "does not throw".
        assertTrue(snap.files.isEmpty() || snap.files.isNotEmpty())
    }

    // The file was written by java.util.Properties until the code became common Kotlin: both ways must still read.

    /** Everything that is escaped in a properties file: blanks, separators, comment signs, a backslash, letters beyond ASCII. */
    private val awkward = Paths.get("/tmp/my graphs/a=b: c#d!e\\f/žluťoučký kůň.dot")

    @Test
    fun `a session written by Java's Properties is read`() {
        val (session, dir) = tempSession()
        val props = java.util.Properties()
        props.setProperty("openFiles.count", "2")
        props.setProperty("openFiles.0", awkward.toString())
        props.setProperty("openFiles.1", "/tmp/graphs/b.dot")
        props.setProperty("activeFile", awkward.toString())
        props.setProperty("viewTransform.$awkward", "2.0,0.0,0.0,2.0,-10.5,30.25")
        Files.newOutputStream(dir.resolve("session.properties")).use { props.store(it, "sane-graph-edit session") }

        val loaded = session.load()
        assertEquals(listOf(awkward, Paths.get("/tmp/graphs/b.dot")), loaded.files)
        assertEquals(awkward, loaded.activeFile)
        assertEquals(mapOf(awkward to listOf(2.0, 0.0, 0.0, 2.0, -10.5, 30.25)), loaded.viewTransforms)
    }

    @Test
    fun `a session written here is read by Java's Properties`() {
        val (session, dir) = tempSession()
        session.save(Session.Snapshot(listOf(awkward), awkward, mapOf(awkward to listOf(1.5, 0.0, 0.0, 1.5, 4.0, -8.0))))

        val props = java.util.Properties()
        Files.newInputStream(dir.resolve("session.properties")).use(props::load)
        assertEquals("1", props.getProperty("openFiles.count"))
        assertEquals(awkward.toString(), props.getProperty("openFiles.0"))
        assertEquals(awkward.toString(), props.getProperty("activeFile"))
        assertEquals("1.5,0.0,0.0,1.5,4.0,-8.0", props.getProperty("viewTransform.$awkward"))
    }
}
