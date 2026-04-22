package graph_tools

import Graph
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for the optimize-anchor fix: when `o` or `O`
 * is pressed with a non-empty selection, only the selected
 * nodes should move. Nodes outside the selection must stay
 * exactly where they were — including neighbours of selected
 * nodes that would otherwise be pulled along by the spring
 * model.
 */
class LayoutOptimizerAnchorTest {

    @Test
    fun `optimize with selection does not move unselected nodes`() {
        val g = Graph()
        val anchor1 = Node("anchor1", Vector2(-500, 0))
        val anchor2 = Node("anchor2", Vector2(500, 0))
        val selected = Node("selected", Vector2(0, 0))
        g.add(anchor1); g.add(anchor2); g.add(selected)
        g.add(Edge(anchor1, selected))
        g.add(Edge(selected, anchor2))

        val a1Before = anchor1.position
        val a2Before = anchor2.position

        g.cleanSelect(setOf(selected))
        LayoutOptimizer.optimize(g, movingNodes = false, restrictOperator = false)

        assertEquals(a1Before, anchor1.position, "anchor1 moved, but only selected should have moved")
        assertEquals(a2Before, anchor2.position, "anchor2 moved, but only selected should have moved")
    }

    @Test
    fun `optimize without selection relaxes the whole graph`() {
        val g = Graph()
        val a = Node("a", Vector2(0, 0))
        val b = Node("b", Vector2(1, 0))  // overlapping → collision push
        g.add(a); g.add(b)
        g.add(Edge(a, b))

        LayoutOptimizer.optimize(g, movingNodes = false, restrictOperator = false)

        // Either node may have moved (no selection → move anyone).
        // We just want to confirm the optimise pass did something
        // and didn't throw.
        val moved = a.position != Vector2(0.0, 0.0) || b.position != Vector2(1.0, 0.0)
        // With nodes that close the collision force should fire.
        assertEquals(true, moved, "expected at least one node to move when no selection")
    }

    @Test
    fun `cross-boundary edges only contribute for incoming direction`() {
        // Set-logic check:
        //   • inside edge → two triples (both endpoints move).
        //   • incoming cross-boundary (outside → selected) → one
        //     triple, selected end in the "moving" slot.
        //   • outgoing cross-boundary (selected → outside) → no
        //     triple. When optimising just the subgraph, an edge
        //     whose destination is anchored outside should not
        //     drag the selected source around.
        val g = Graph()
        val sel1 = Node("sel1", Vector2(0, 0))
        val sel2 = Node("sel2", Vector2(100, 0))
        val outside = Node("outside", Vector2(200, 0))
        g.add(sel1); g.add(sel2); g.add(outside)

        val edgeInside = Edge(sel1, sel2)
        val edgeCrossingOut = Edge(sel2, outside)
        val edgeCrossingIn = Edge(outside, sel1)
        g.add(edgeInside); g.add(edgeCrossingOut); g.add(edgeCrossingIn)

        g.cleanSelect(setOf(sel1, sel2))
        val triples = LayoutOptimizer.impl.computeConnectedEdgeSet(
            g, LayoutOptimizer.SpringTarget.MoveSelectedOnly,
        ).toList()

        // Inside edge (sel1 ↔ sel2): two triples, one per endpoint.
        val insideTriples = triples.filter { it.third === edgeInside }
        assertEquals(2, insideTriples.size, "inside edge should yield 2 triples")
        assertEquals(setOf(sel1, sel2), insideTriples.map { it.second }.toSet(),
            "both endpoints should appear as the moving (second) node")

        // Out-crossing edge (sel2 → outside): zero triples.
        val outCrossing = triples.filter { it.third === edgeCrossingOut }
        assertEquals(0, outCrossing.size,
            "out-crossing edge should yield no triples — unselected dst shouldn't drag selected src")

        // In-crossing edge (outside → sel1): one triple, sel1 moves.
        val inCrossing = triples.filter { it.third === edgeCrossingIn }
        assertEquals(1, inCrossing.size, "in-crossing edge should yield 1 triple")
        assertEquals(sel1, inCrossing.single().second,
            "the selected endpoint should be the moving node")
    }

    @Test
    fun `O with single selection still keeps anchors still`() {
        // restrict=true with size=1 — also an "only selected
        // moves" path post-fix (previously moved children).
        val g = Graph()
        val anchor = Node("anchor", Vector2(-200, 0))
        val selected = Node("selected", Vector2(0, 0))
        g.add(anchor); g.add(selected)
        g.add(Edge(anchor, selected))

        val anchorBefore = anchor.position
        g.cleanSelect(setOf(selected))
        LayoutOptimizer.optimize(g, movingNodes = false, restrictOperator = true)

        assertEquals(anchorBefore, anchor.position, "anchor must not move with `O` and a selection")
    }
}
