package client

import DotGraphLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The examples lead to each other and to the documentation: no link in them goes nowhere. */
class ExampleLinksTest {
    private val examples: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("examples") }
        .first { Files.exists(it.resolve("index.dot")) }

    private val graphs = examples.listDirectoryEntries("*.dot").associateWith { DotGraphLoader.loadFromFile(it.toString()) }

    private fun links(file: Path): List<FileLink.Target> =
        graphs.getValue(file).nodes.flatMap { n -> FileLink.letters(n).mapNotNull { FileLink.of(n, it) } }.map { FileLink.resolve(it, file) }

    @Test
    fun `every link leads to a file there is, and to a node that file has`() {
        val broken = graphs.keys.flatMap { file ->
            links(file).filterNot { to ->
                val path = to.path
                when {
                    to.help -> true
                    path == null || !Files.exists(path) -> false
                    to.nodeId != null -> DotGraphLoader.loadFromFile(path.toString()).nodes.any { it.attributes.name == to.nodeId }
                    else -> to.lines != null || path.extension == "dot"
                }
            }.map { "${file.name} -> ${it.path}:${it.nodeId}" }
        }
        assertEquals(emptyList(), broken)
    }

    @Test
    fun `the index leads to every example, and every example back to it`() {
        val index = examples.resolve("index.dot")
        // not `keys - index`: a path is itself a list of its parts
        val others = graphs.keys.filter { it != index }
        assertEquals(others.map { it.name }.toSet(), links(index).mapNotNull { it.path }.filter { it.extension == "dot" }.map { it.name }.toSet())
        others.forEach { file -> assertTrue(links(file).any { it.path == index }, "${file.name} does not lead back") }
    }
}
