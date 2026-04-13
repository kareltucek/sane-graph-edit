package graph_tools

import Graph
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarksTest {

    @Test
    fun `uppercase mark writes to attributes`() {
        val n = Node("n", Vector2(0, 0))
        n.setMarks(uppercase = true, value = "A")
        assertEquals("A", n.attributes.marks)
        assertEquals("", n.cache.sessionMarks)
    }

    @Test
    fun `lowercase mark writes to cache`() {
        val n = Node("n", Vector2(0, 0))
        n.setMarks(uppercase = false, value = "a")
        assertEquals("", n.attributes.marks)
        assertEquals("a", n.cache.sessionMarks)
    }

    @Test
    fun `hasMark routes by case`() {
        val n = Node("n", Vector2(0, 0))
        n.attributes.marks = "A"
        n.cache.sessionMarks = "a"
        assertTrue(n.hasMark('A'))
        assertTrue(n.hasMark('a'))
        assertFalse(n.hasMark('B'))
        assertFalse(n.hasMark('b'))
    }

    @Test
    fun `SetMarksCommand redo applies after-state`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(100, 0))
        g.add(a); g.add(b)

        val before = mapOf(a to "", b to "")
        val after = mapOf(a to "A", b to "")
        g.commit(SetMarksCommand(g, uppercase = true, before, after))

        assertEquals("A", a.attributes.marks)
        assertEquals("", b.attributes.marks)
    }

    @Test
    fun `SetMarksCommand undo restores before-state`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        g.add(a)
        a.attributes.marks = "B"

        val before = mapOf(a to "B")
        val after = mapOf(a to "A")
        g.commit(SetMarksCommand(g, uppercase = true, before, after))
        assertEquals("A", a.attributes.marks)

        g.history.undo()
        assertEquals("B", a.attributes.marks)
    }

    @Test
    fun `marks attribute round-trips through DOT`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        a.attributes.marks = "AB"
        g.add(a)

        val dot = parser_dot.Serializer(g).serialize()
        assertTrue(dot.contains("marks"), "DOT output should mention marks")
        assertTrue(dot.contains("AB"), "DOT output should contain mark letters")

        // Round-trip
        val tokens = parser_dot.Tokenizer(dot).lex()
        val parsed = parser_dot.Parser(tokens).parseGraph()
        val parsedNode = parsed.nodes.first()
        assertEquals("AB", parsedNode.attributes.marks)
    }

    @Test
    fun `empty marks attribute is not serialised`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        g.add(a)

        val dot = parser_dot.Serializer(g).serialize()
        assertFalse(
            dot.contains("marks"),
            "DOT output should not mention marks when empty",
        )
    }

    @Test
    fun `session marks do not round-trip through DOT`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        a.cache.sessionMarks = "ab"  // lowercase, transient
        g.add(a)

        val dot = parser_dot.Serializer(g).serialize()
        // Session marks should NOT appear in DOT output
        assertFalse(dot.contains("\"ab\""), "session marks must not serialise")
    }
}
