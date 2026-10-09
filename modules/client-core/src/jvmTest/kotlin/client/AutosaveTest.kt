package client

import Graph
import graph_tools.AddNodeCommand
import graph_tools.Node
import utils.Vector2
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The periodic snapshots, with a clock that the test moves. */
class AutosaveTest {
    /** Runs what is due when told to; remembers the delay asked for. */
    private class ManualScheduler : Scheduler {
        var due: (() -> Unit)? = null
        var delay = 0
        override fun after(ms: Int, action: () -> Unit): () -> Unit {
            delay = ms
            due = action
            return { due = null }
        }
        fun tick() {
            due?.let { action ->
                due = null
                action()
            }
        }
    }

    private val dir = createTempDirectory("sge-autosave")
    private val backups = dir.resolve("backups")
    private val file = dir.resolve("a.dot")
    private val saved = EditorView(Graph().also { it.add(Node("kept", Vector2(0, 0))) }, NoRenderer, initialFile = file)
    private val untitled = EditorView(Graph(), NoRenderer)
    private val scheduler = ManualScheduler()
    private val autosave = Autosave({ listOf(saved, untitled) }, scheduler, backups, intervalMs = 1000)

    private fun written() = if (Files.exists(backups)) Files.list(backups).use { files -> files.map { it.name }.sorted().toList() } else emptyList()

    private fun change(view: EditorView) = view.g.commit(AddNodeCommand(view.g, Node("more", Vector2(50, 50))))

    @Test
    fun `a view without unsaved changes is not written`() {
        autosave.start()
        scheduler.tick()
        assertEquals(emptyList(), written())
    }

    @Test
    fun `every tick writes a new numbered snapshot of each changed view`() {
        autosave.start()
        assertEquals(1000, scheduler.delay)
        change(saved)
        scheduler.tick()
        change(untitled)
        scheduler.tick()
        val names = written()
        assertEquals(3, names.size)
        val ofFile = BackupPaths.prefixFor(file, "unused")
        assertEquals(listOf("$ofFile.1.dot", "$ofFile.2.dot"), names.filter { it.startsWith("a.") })
        assertEquals("untitled.${untitled.autosaveId.take(8)}.1.dot", names.single { it.startsWith("untitled.") })
        assertTrue("label=\"more\"" in Files.readString(backups.resolve("$ofFile.2.dot")))
    }

    @Test
    fun `the numbers go on from those already in the directory`() {
        Files.createDirectories(backups)
        val ofFile = BackupPaths.prefixFor(file, "unused")
        Files.writeString(backups.resolve("$ofFile.7.dot"), "digraph g {}")
        autosave.start()
        change(saved)
        scheduler.tick()
        assertTrue("$ofFile.8.dot" in written())
    }

    @Test
    fun `nothing is written after it is stopped`() {
        autosave.start()
        change(saved)
        autosave.stop()
        scheduler.tick()
        assertEquals(emptyList(), written())
    }
}
