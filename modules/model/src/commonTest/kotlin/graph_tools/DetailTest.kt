@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package graph_tools

import Graph
import DotGraphLoader
import sync.GraphState
import sync.applyPatch
import utils.Rgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Levels of detail. `both` is something at levels 0 and 1, `plain`
 * at 0 only, `deep` at 1 only (by its content type), and `only2` at
 * 2 only.
 */
class DetailTest {
    private val source = """
        digraph G {
          both [label="short"; fillcolor="#C4E0F6"; label-l1="much longer"; fontsize-l1="20"; color-l1="#112233"; pos="0,0!"];
          plain [label="plain"; pos="100,0!"];
          deep [content-type-l1="markdown"; pos="200,0!"];
          only2 [label-l2="far down"; pos="300,0!"];
          both -> plain; both -> deep; deep -> only2;
        }
    """.trimIndent()

    private fun load(text: String = source): Graph = DotGraphLoader.parse(DotGraphLoader.tokenize(text))

    private fun Graph.named(name: String): Node = nodes.first { it.attributes.name == name }

    private fun Graph.seen(): Set<String> = visibleNodes().map { it.attributes.name }.toSet()

    @Test
    fun `at level 0 a node shows what is written without a level, and one that has nothing there is not shown`() {
        val g = load()
        assertEquals(setOf("both", "plain"), g.seen())
        assertEquals("short", g.named("both").attributes.text)
        assertEquals(Rgb(0xC4, 0xE0, 0xF6), g.named("both").attributes.bg)
        assertEquals(2, g.topLevel)
    }

    @Test
    fun `another level shows its own attributes and defaults for the rest`() {
        val g = load()
        g.showLevel(1)
        val both = g.named("both")
        assertEquals(setOf("both", "deep"), g.seen())
        assertEquals("much longer", both.attributes.text)
        assertEquals(Rgb(0x11, 0x22, 0x33), both.attributes.fg)
        assertNull(both.attributes.bg)
        assertEquals("markdown", g.named("deep").attributes.other[NodeType.ATTRIBUTE])
        g.showLevel(2)
        assertEquals(setOf("only2"), g.seen())
        g.showLevel(3)
        assertEquals(emptySet(), g.seen())
        assertEquals(2, g.topLevel)
        g.showLevel(0)
        assertEquals(setOf("both", "plain"), g.seen())
        assertEquals("short", both.attributes.text)
        assertNull(both.attributes.fg)
    }

    @Test
    fun `l0 after a name is the same as nothing after it`() {
        val g = load("""digraph G { a [label-l0="zero"; pos="0,0!"]; }""")
        assertEquals("zero", g.named("a").attributes.text)
    }

    @Test
    fun `a file is written the same from whatever level, and read back the same`() {
        val g = load()
        val attributes = { graph: Graph -> graph.nodes.associate { it.attributes.name to it.retrieveAttributes() } }
        val atZero = attributes(g)
        g.showLevel(1)
        assertEquals(atZero, attributes(g))
        assertEquals(atZero, attributes(load(DotGraphLoader.serialize(g))))
        assertFalse("label" in atZero.getValue("deep"))
        assertEquals("much longer", atZero.getValue("both")["label-l1"])
    }

    @Test
    fun `what is changed at a level is of that level only`() {
        val g = load()
        g.showLevel(1)
        g.named("both").attributes.text = "edited"
        g.showLevel(0)
        assertEquals("short", g.named("both").attributes.text)
        assertEquals("edited", g.named("both").detail(1).text)
    }

    @Test
    fun `a node made at a level is of that level`() {
        val g = load()
        g.showLevel(1)
        val made = Node("new", utils.Vector2(0, 100))
        g.add(made)
        assertTrue(g.isVisible(made))
        g.showLevel(0)
        assertFalse(g.isVisible(made))
        assertEquals("new", made.detail(1).text)
    }

    @Test
    fun `replicas at different levels have the same state, and a change at one level reaches the other at that level`() {
        val here = load()
        val there = GraphState.of(here).toGraph()
        there.showLevel(1)
        assertEquals(GraphState.of(here), GraphState.of(there))

        val before = GraphState.of(there)
        there.nodes.first { it.attributes.text == "much longer" }.attributes.text = "changed"
        here.applyPatch(before.diff(GraphState.of(there)))
        assertEquals("short", here.named("both").attributes.text)
        assertEquals("changed", here.named("both").detail(1).text)
        assertEquals(GraphState.of(here), GraphState.of(there))
        assertEquals(setOf("both", "deep"), there.visibleNodes().map { it.id }.toSet().let { ids -> here.nodes.filter { it.id in ids }.map { it.attributes.name }.toSet() })
    }
}
