@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import DotGraphLoader
import Graph
import graph_tools.Node
import graph_tools.NodeType
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a node's text is (plain, code, markdown): the attribute, the command that sets it, and that it survives a file. */
class NodeTypeTest {
    private val a = Node("fun main() {}", Vector2(0, 0))
    private val b = Node("# Title", Vector2(200, 0))

    private val messages = mutableListOf<String>()
    private val shell = object : Shell by NoShell {
        override fun message(text: String) {
            messages += text
        }
    }

    private val view = EditorView(Graph().also { it.add(a); it.add(b) }, NoRenderer, shell).also {
        it.g.unselectAll(it.g.nodes.toList())
        it.g.lastActiveNode = null
    }

    private val keys = KeyMapper(KeyMapper.defaultBindings(), Scheduler { _, _ -> {} }).also { it.activeView = view }

    private fun attribute(n: Node) = n.attributes.other[NodeType.ATTRIBUTE]

    @Test
    fun `the attribute names the kind and, for code, the language`() {
        assertEquals(NodeType(NodeType.Kind.Code, "scala"), NodeType.parse("code-scala"))
        assertEquals(NodeType(NodeType.Kind.Code), NodeType.parse("code"))
        assertEquals(NodeType.Markdown, NodeType.parse("markdown"))
        assertEquals(NodeType.Plain, NodeType.parse("plain"))
        assertEquals("code-c++", NodeType.parse("code-C++")?.attribute)
        assertNull(NodeType.parse("code-"))
        assertNull(NodeType.parse("code-two words"))
        assertNull(NodeType.parse("picture"))
        assertNull(NodeType.Plain.attribute)
    }

    @Test
    fun `a node without the attribute, or with one that names nothing, is plain`() {
        assertEquals(NodeType.Plain, NodeType.of(a))
        a.attributes.other[NodeType.ATTRIBUTE] = "picture"
        assertEquals(NodeType.Plain, NodeType.of(a))
    }

    @Test
    fun `the command sets the type of the selection, as one undo step, and plain takes the attribute away`() {
        view.g.cleanSelect(setOf(a, b))
        keys.executeCommandLine("content-type code-kotlin")
        assertEquals(listOf("code-kotlin", "code-kotlin"), listOf(attribute(a), attribute(b)))
        view.g.undo()
        assertEquals(listOf(null, null), listOf(attribute(a), attribute(b)))
        view.g.cleanSelect(setOf(b))
        keys.executeCommandLine("content-type markdown")
        assertEquals(NodeType.Markdown, NodeType.of(b))
        keys.executeCommandLine("content-type plain")
        assertTrue(NodeType.ATTRIBUTE !in b.attributes.other)
    }

    @Test
    fun `without a selection the last active node is meant, and without that a message`() {
        keys.executeCommandLine("content-type markdown")
        assertEquals(listOf("content-type: no node is selected"), messages)
        view.g.lastActiveNode = a
        keys.executeCommandLine("content-type markdown")
        assertEquals(NodeType.Markdown, NodeType.of(a))
    }

    @Test
    fun `without an argument the command tells the type, and a wrong one is refused`() {
        view.g.cleanSelect(setOf(a))
        keys.executeCommandLine("content-type code-c")
        keys.executeCommandLine("content-type")
        keys.executeCommandLine("content-type picture")
        assertEquals(listOf("code-c", "content-type: `picture` is not one (plain, markdown, code, code-<language>)"), messages)
        assertEquals("code-c", attribute(a))
    }

    @Test
    fun `the scene tells a renderer the type`() {
        a.attributes.other[NodeType.ATTRIBUTE] = "code-scala"
        val scene = view.scene(graph_tools.ApproximateTextMeasurer)
        assertEquals(NodeType(NodeType.Kind.Code, "scala"), scene.nodes.single { it.id == a.id }.type)
        assertEquals(NodeType.Plain, scene.nodes.single { it.id == b.id }.type)
    }

    @Test
    fun `the type is written to a file and read back`() {
        a.attributes.other[NodeType.ATTRIBUTE] = "code-scala"
        b.attributes.other[NodeType.ATTRIBUTE] = "markdown"
        val text = DotGraphLoader.serialize(view.g)
        assertTrue("content-type" in text, text)
        val read = DotGraphLoader.parse(DotGraphLoader.tokenize(text))
        assertEquals(listOf("code-scala", "markdown"), read.nodes.sortedBy { it.position.x }.map { NodeType.of(it).attribute })
    }
}
