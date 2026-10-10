package client

import DotGraphLoader
import graph_tools.Node
import graph_tools.NodeType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** Nodes whose text is kept in a file: two graphs speak of lines of one source file and of a section of a document. */
class FileContentTest {
    private val dir: Path = Files.createTempDirectory("filecontent")
    private val code: Path = dir.resolve("a.kt").also { it.writeText((1..10).joinToString("\n", postfix = "\n") { "line $it" }) }
    private val doc: Path = dir.resolve("doc.md").also { it.writeText("# Doc\n\n## One\n\nfirst\n\n## Two\n\nsecond\n") }

    private val views = mutableListOf<EditorView>()
    private val shell = object : Shell by NoShell {
        override fun views(): List<EditorView> = views
    }

    private fun open(name: String, text: String): EditorView {
        val file = dir.resolve(name).also { it.writeText(text) }
        return EditorView(DotGraphLoader.loadFromFile(file.toString()), NoRenderer, shell, initialFile = file).also { views += it }
    }

    private val first = open(
        "first.dot",
        """
        digraph g {
          top [label="stale"; "content-type"="code-kotlin:a.kt:2-3"];
          below [label=""; "content-type"="code-kotlin:a.kt:6-7"];
          around [label=""; "content-type"="plain:a.kt:1-8"];
          own [label="own text"];
        }
        """.trimIndent(),
    )
    private val second = open(
        "second.dot",
        """
        digraph g {
          last [label=""; "content-type"="code:a.kt:9"];
          section [label=""; "content-type"="markdown:doc.md#One"];
          whole [label=""; "content-type"="markdown:doc.md"];
        }
        """.trimIndent(),
    )

    private fun node(view: EditorView, id: String): Node = view.g.nodes.single { it.attributes.name == id }
    private fun text(view: EditorView, id: String): String = node(view, id).attributes.text
    private fun type(view: EditorView, id: String): String? = node(view, id).attributes.other[NodeType.ATTRIBUTE]

    private fun edit(view: EditorView, id: String, text: String) {
        view.startNodeEdit(node(view, id), null)
        view.textEditStarted(node(view, id).id)
        view.textTyped(text)
        view.textEditEnded(text)
    }

    @Test
    fun `an opened graph shows what the files hold`() {
        assertEquals("line 2\nline 3", text(first, "top"))
        assertEquals("line 9", text(second, "last"))
        assertEquals("## One\n\nfirst", text(second, "section"))
        assertEquals("# Doc\n\n## One\n\nfirst\n\n## Two\n\nsecond", text(second, "whole"))
        assertEquals("own text", text(first, "own"))
    }

    @Test
    fun `an edit is written to the file, and the lines of the nodes below it follow, in every open graph`() {
        edit(first, "top", "line 2\nnew\nnewer\nline 3")
        assertEquals(listOf("line 1", "line 2", "new", "newer", "line 3", "line 4"), code.readText().lines().take(6))
        assertEquals(13, code.readText().lines().size)
        assertEquals("code-kotlin:a.kt:2-5", type(first, "top"))
        assertEquals("code-kotlin:a.kt:8-9", type(first, "below"))
        assertEquals("line 6\nline 7", text(first, "below"))
        // one that holds the edited lines grows with them
        assertEquals("plain:a.kt:1-10", type(first, "around"))
        assertEquals("line 1\nline 2\nnew\nnewer\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8", text(first, "around"))
        assertEquals("code:a.kt:11-11", type(second, "last"))
        assertEquals("line 9", text(second, "last"))
    }

    @Test
    fun `lines taken away move what is below up, and one line that becomes several is a range`() {
        edit(first, "below", "only")
        assertEquals("code-kotlin:a.kt:6-6", type(first, "below"))
        assertEquals("code:a.kt:8-8", type(second, "last"))
        edit(second, "last", "line 9\nand a half")
        assertEquals("code:a.kt:8-9", type(second, "last"))
        assertEquals("line 9\nand a half\nline 10\n", code.readText().substringAfter("line 8\n"))
        // above the edits nothing moved
        assertEquals("code-kotlin:a.kt:2-3", type(first, "top"))
    }

    @Test
    fun `a section keeps its name, and the node of the whole file shows the edit`() {
        edit(second, "section", "## One\n\nfirst\nand more")
        assertEquals("# Doc\n\n## One\n\nfirst\nand more\n\n## Two\n\nsecond\n", doc.readText())
        assertEquals("markdown:doc.md#One", type(second, "section"))
        assertEquals(doc.readText().trimEnd('\n'), text(second, "whole"))
    }

    @Test
    fun `what the file holds now is what is edited`() {
        code.writeText("zero\n" + code.readText())
        first.startNodeEdit(node(first, "top"), null)
        assertEquals("line 1\nline 2", text(first, "top"))
    }
}
