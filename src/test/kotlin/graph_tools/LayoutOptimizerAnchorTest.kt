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
