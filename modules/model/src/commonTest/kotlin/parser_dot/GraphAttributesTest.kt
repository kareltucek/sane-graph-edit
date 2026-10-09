@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package parser_dot

import DotGraphLoader
import Graph
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphAttributesTest {

    private fun roundTrip(dot: String): String =
        DotGraphLoader.serialize(DotGraphLoader.parse(DotGraphLoader.tokenize(dot)))

    @Test
    fun `graph-level attributes survive a round trip in place`() {
        val out = roundTrip(
            """
            digraph g {
              rankdir=LR;
              a [label="A"];
              label="my graph";
              a -> b;
            }
            """.trimIndent()
        )
        assertEquals(
            listOf("digraph g {", "rankdir=\"LR\";", "a [ label=\"A\"; pos=\"0.0,0.0!\" ];", "label=\"my graph\";"),
            out.lines().take(4),
        )
    }

    @Test
    fun `graph attribute list is read as graph attributes, not a node`() {
        val g = DotGraphLoader.parse(DotGraphLoader.tokenize("digraph g {\n  graph [rankdir=LR; splines=false];\n  a;\n}"))
        assertEquals(mapOf("rankdir" to "LR", "splines" to "false"), g.attributes)
        assertEquals(listOf("a"), g.nodes.map { it.attributes.text })
        assertEquals(
            listOf("rankdir=\"LR\";", "splines=\"false\";"),
            DotGraphLoader.serialize(g).lines().filter { it.startsWith("rankdir") || it.startsWith("splines") },
        )
    }

    @Test
    fun `attributes of a graph built in code are written after the brace`() {
        val g = Graph()
        g.attributes["rankdir"] = "LR"
        assertEquals("rankdir=\"LR\";", DotGraphLoader.serialize(g).lines()[1])
    }
}
