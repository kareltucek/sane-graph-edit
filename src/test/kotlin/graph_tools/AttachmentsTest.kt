package graph_tools

import DotGraphLoader
import Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentsTest {

    private val dot = """
        digraph g {
          m [ label="Billing.charge"; pos="100,100!" ];
          args [ label="(customer: Id, amount: Money)"; attachTo="m"; attachSide="above"; pos="0,0!" ];
          ret [ label="IO[Receipt]"; attachTo="m"; pos="0,0!" ];
          doc [ label="Charges the card"; attachTo="ret"; attachSide="below"; pos="0,0!" ];
          side [ label="note"; attachTo="m"; attachSide="right"; pos="0,0!" ];
          loopA [ label="a"; attachTo="loopB" ];
          loopB [ label="b"; attachTo="loopA" ];
          ghost [ label="g"; attachTo="nope" ];
          m -> args; m -> ret;
        }
    """.trimIndent()

    private fun load(): Pair<Graph, Map<String, Node>> {
        val g = DotGraphLoader.parse(DotGraphLoader.tokenize(dot))
        g.ensureCachesFresh(force = true)
        return g to g.nodes.associateBy { it.attributes.name }
    }

    private fun top(n: Node) = n.position.y - n.cache.shapeBounds.y / 2
    private fun bottom(n: Node) = n.position.y + n.cache.shapeBounds.y / 2

    @Test
    fun `attached nodes are glued to their owner's sides, stacked and chained`() {
        val (_, n) = load()
        val m = n.getValue("m")
        val args = n.getValue("args")
        val ret = n.getValue("ret")
        val doc = n.getValue("doc")
        val side = n.getValue("side")

        assertEquals(m.position.x, args.position.x, 1e-9)
        assertEquals(Attachments.GAP, top(m) - bottom(args), 1e-9)
        assertEquals(Attachments.GAP, top(ret) - bottom(m), 1e-9)
        assertEquals(Attachments.GAP, top(doc) - bottom(ret), 1e-9)  // chain: below ret
        assertEquals(m.position.y, side.position.y, 1e-9)
        assertEquals(
            Attachments.GAP,
            (side.position.x - side.cache.shapeBounds.x / 2) - (m.position.x + m.cache.shapeBounds.x / 2),
            1e-9,
        )
    }

    @Test
    fun `cycles and unknown owners leave nodes unattached`() {
        val (g, n) = load()
        val owners = g.attachmentOwners
        assertFalse(n.getValue("loopA") in owners)
        assertFalse(n.getValue("ghost") in owners)
        assertEquals(n.getValue("m"), Attachments.root(n.getValue("doc"), owners))
    }

    @Test
    fun `moving the owner drags the group, moving an attached node moves its owner`() {
        val (g, n) = load()
        val m = n.getValue("m")
        val doc = n.getValue("doc")
        assertEquals(setOf(m), g.movable(setOf(doc)))

        m.position = m.position + utils.Vector2(500, 0)
        g.needsRecomputing(m)
        g.ensureCachesFresh()
        assertEquals(m.position.x, doc.position.x, 1e-9)
    }

    @Test
    fun `an attached node is hidden with its owner`() {
        val (g, n) = load()
        n.getValue("m").cache.hideLevel = 1
        assertFalse(g.isVisible(n.getValue("doc")))
        n.getValue("m").cache.hideLevel = 0
        assertTrue(g.isVisible(n.getValue("doc")))
    }

    @Test
    fun `saving keeps the attachment attributes`() {
        val (g, _) = load()
        val out = DotGraphLoader.serialize(g)
        assertTrue("attachTo=\"ret\"" in out && "attachSide=\"above\"" in out, out)
    }
}
