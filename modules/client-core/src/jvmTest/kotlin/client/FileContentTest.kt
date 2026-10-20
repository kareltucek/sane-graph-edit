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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private val untouched = code.readText()

    @kotlin.test.AfterTest
    fun forget() = FileContent.dropAll()

    @Test
    fun `an edit is a change of the graph and not of the file, and undo takes it back`() {
        first.content.saveNow()
        edit(first, "top", "line 2\nnew\nline 3")
        assertEquals(untouched, code.readText())
        assertTrue(first.content.isDirty)
        assertEquals("code-kotlin:a.kt:2-3", type(first, "top"))
        first.g.undo()
        assertEquals("line 2\nline 3", text(first, "top"))
        // and the editor is opened with what the node holds, not with what the file does
        first.g.redo()
        first.startNodeEdit(node(first, "top"), null)
        assertEquals("line 2\nnew\nline 3", text(first, "top"))
    }

    @Test
    fun `saving the graph writes the edits to the file, and the lines of the nodes below them follow, in every open graph`() {
        edit(first, "top", "line 2\nnew\nnewer\nline 3")
        edit(first, "below", "only")
        assertEquals(untouched, code.readText())
        assertEquals("code-kotlin:a.kt:6-7", type(first, "below"))
        assertTrue(first.content.saveNow())
        assertEquals(listOf("line 1", "line 2", "new", "newer", "line 3", "line 4", "line 5", "only", "line 8", "line 9", "line 10", ""), code.readText().lines())
        assertEquals("code-kotlin:a.kt:2-5", type(first, "top"))
        assertEquals("code-kotlin:a.kt:8-8", type(first, "below"))
        // one that holds the edited lines changes with them
        assertEquals("plain:a.kt:1-9", type(first, "around"))
        assertEquals("line 1\nline 2\nnew\nnewer\nline 3\nline 4\nline 5\nonly\nline 8", text(first, "around"))
        assertEquals("code:a.kt:10-10", type(second, "last"))
        assertEquals("line 9", text(second, "last"))
        // the saved graph names the new lines
        assertTrue("a.kt:2-5" in dir.resolve("first.dot").readText())
        assertFalse(first.content.isDirty)
        // and a second save has nothing to write
        code.writeText(code.readText() + "appended\n")
        assertTrue(first.content.saveNow())
        assertTrue(code.readText().endsWith("appended\n"))
    }

    @Test
    fun `a file is saved whole, with the edits made in another graph, whose lines are found where they went`() {
        edit(second, "last", "line 9\nand a half")
        edit(first, "top", "line 2")
        assertEquals("line 9\nand a half", text(second, "last"))
        assertTrue(first.content.saveNow())
        assertEquals("line 1\nline 2\nline 4\nline 5\nline 6\nline 7\nline 8\nline 9\nand a half\nline 10\n", code.readText())
        assertEquals("code:a.kt:8-9", type(second, "last"))
    }

    @Test
    fun `nodes that hold the same lines show one text, whichever of them is edited, and undo takes it back in both`() {
        edit(first, "around", "line 1\nline two\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8")
        assertEquals("line two\nline 3", text(first, "top"))
        edit(first, "top", "line two\nbetween\nline 3")
        assertEquals("line 1\nline two\nbetween\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8", text(first, "around"))
        assertEquals("line 6\nline 7", text(first, "below"))
        first.g.undo()
        assertEquals("line two\nline 3", text(first, "top"))
        assertEquals("line 1\nline two\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8", text(first, "around"))
        first.g.undo()
        assertEquals("line 2\nline 3", text(first, "top"))
        first.g.redo()
        first.g.redo()
        assertEquals("line two\nbetween\nline 3", text(first, "top"))
        assertEquals("line 6\nline 7", text(first, "below"))
        assertTrue(first.content.saveNow())
        assertEquals("line 1\nline two\nbetween\nline 3\nline 4", code.readText().lines().take(5).joinToString("\n"))
        assertEquals("code-kotlin:a.kt:7-8", type(first, "below"))
    }

    @Test
    fun `a file with something unsaved is known of, lives on when its graph is read again, and is written with all the others`() {
        first.content.saveNow()
        edit(first, "top", "changed")
        assertTrue(code.name in FileContent.unsaved())
        graph_tools.ViewModes.discard = true
        try {
            first.content.reload()
        } finally {
            graph_tools.ViewModes.discard = false
        }
        assertEquals("changed", text(first, "top"))
        assertEquals(untouched, code.readText())
        assertTrue(FileContent.saveAll(second))
        assertEquals("line 1\nchanged\nline 4", code.readText().lines().take(3).joinToString("\n"))
        assertFalse(code.name in FileContent.unsaved())
    }

    @Test
    fun `an edit in one graph shows at once in a node of another that holds those lines`() {
        val third = open("third.dot", "digraph g {\n  twin [label=\"\"; \"content-type\"=\"code-kotlin:a.kt:2-3\"];\n}\n")
        edit(first, "top", "line 2\nmore\nline 3")
        assertEquals("line 2\nmore\nline 3", text(third, "twin"))
        edit(third, "twin", "line 2\nline 3")
        assertEquals("line 2\nline 3", text(first, "top"))
        assertEquals("line 9", text(second, "last"))
    }

    @Test
    fun `a section keeps its name, and the node of the whole file shows the edit once it is saved`() {
        edit(second, "section", "## One\n\nfirst\nand more")
        assertTrue(second.content.saveNow())
        assertEquals("# Doc\n\n## One\n\nfirst\nand more\n\n## Two\n\nsecond\n", doc.readText())
        assertEquals("markdown:doc.md#One", type(second, "section"))
        assertEquals(doc.readText().trimEnd('\n'), text(second, "whole"))
    }

    @Test
    fun `a file changed by something else is not written over, and a reload takes its text`() {
        edit(first, "top", "mine")
        code.writeText("zero\n$untouched")
        assertFalse(first.content.saveNow())
        assertEquals("zero\n$untouched", code.readText())
        first.content.reload(force = true)
        assertEquals("line 1\nline 2", text(first, "top"))
    }

    @Test
    fun `an edit of a section shows at once in the node of the whole file`() {
        edit(second, "section", "## One\n\nfirst\nand more")
        assertEquals("# Doc\n\n## One\n\nfirst\nand more\n\n## Two\n\nsecond", text(second, "whole"))
    }

    @Test
    fun `what the file holds now is what is edited, when the node has no edit of its own`() {
        code.writeText("zero\n$untouched")
        first.startNodeEdit(node(first, "top"), null)
        assertEquals("line 1\nline 2", text(first, "top"))
    }
}

