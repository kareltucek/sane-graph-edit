@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import graph_tools.Edge
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `z` keys: the view to the target, and folding along the
 * edges. The graph is a chain with a side branch:
 * `up -> a -> b -> c` and `a -> side`, at zoom 1 with the
 * workspace origin in the window's corner.
 */
class ZCommandsTest {
    private val up = Node("up", Vector2(100, 100))
    private val a = Node("a", Vector2(300, 100))
    private val b = Node("b", Vector2(500, 100))
    private val c = Node("c", Vector2(700, 100))
    private val side = Node("side", Vector2(500, 300))

    private val messages = mutableListOf<String>()
    private val shell = object : Shell by NoShell {
        override fun message(text: String) {
            messages += text
        }
    }

    private val view = EditorView(
        Graph().also { g ->
            listOf(up, a, b, c, side).forEach { g.add(it) }
            listOf(up to a, a to b, b to c, a to side).forEach { (from, to) -> g.add(Edge(from, to)) }
        },
        NoRenderer,
        shell,
    ).also {
        it.size = Vector2(800, 600)
        it.g.ensureCachesFresh()
        it.g.unselectAll(it.g.nodes.toList())
        it.g.lastActiveNode = null
    }

    private val keys = KeyMapper(KeyMapper.defaultBindings(), Scheduler { _, _ -> {} }, defaultMappings = KeyMapper.defaultKeyMappings()).also {
        registerEditingCommands()
        it.commandExecutor = { command, view -> CommandRegistry.execute(command, view) }
        it.activeView = view
    }

    /** How often the undo history changed. */
    private var steps = 0

    init {
        view.g.history.addListener { steps++ }
    }

    private fun type(sequence: String) = KeyNotation.tokenize(sequence).forEach { keys.feedKey(it) }

    private fun select(vararg nodes: Node) = view.g.cleanSelect(nodes.toSet())

    private fun hidden() = view.g.nodes.filter { it.isHidden }.map { it.attributes.text }.sorted()

    private fun onScreen(n: Node) = view.viewTransform.toScreen(n.position)

    private fun halfSize(n: Node) = n.cache.shapeBounds / 2

    // --- view ---

    @Test
    fun `zt puts the target at the top edge, moving the view only up and down`() {
        select(b)
        val x = onScreen(b).x
        type("zt")
        assertEquals(ZCommands.EDGE_MARGIN, onScreen(b).y - halfSize(b).y, 1e-9)
        assertEquals(x, onScreen(b).x, 1e-9)
        assertEquals(1.0, view.viewTransform.scale)
    }

    @Test
    fun `zb, zs and ze put it at the other edges`() {
        select(b)
        type("zb")
        assertEquals(600 - ZCommands.EDGE_MARGIN, onScreen(b).y + halfSize(b).y, 1e-9)
        type("zs")
        assertEquals(ZCommands.EDGE_MARGIN, onScreen(b).x - halfSize(b).x, 1e-9)
        type("ze")
        assertEquals(800 - ZCommands.EDGE_MARGIN, onScreen(b).x + halfSize(b).x, 1e-9)
        // the vertical place from zb is kept by the horizontal moves
        assertEquals(600 - ZCommands.EDGE_MARGIN, onScreen(b).y + halfSize(b).y, 1e-9)
    }

    @Test
    fun `a target of several nodes is moved as one box`() {
        select(a, side)
        type("zt")
        assertEquals(ZCommands.EDGE_MARGIN, onScreen(a).y - halfSize(a).y, 1e-9)
    }

    @Test
    fun `without a selection the last active node is the target, and without that a message`() {
        type("zt")
        assertEquals(listOf("view-top: no node is selected"), messages)
        view.g.lastActiveNode = c
        type("zt")
        assertEquals(ZCommands.EDGE_MARGIN, onScreen(c).y - halfSize(c).y, 1e-9)
    }

    @Test
    fun `the view commands leave the selection and the undo history alone`() {
        select(b)
        val before = steps
        type("ztzbzsze")
        assertEquals(setOf(b), view.g.selectedNodes.toSet())
        assertEquals(before, steps)
    }

    @Test
    fun `zz and z1 centre like 1, z0 fits like 0`() {
        select(b)
        type("zz")
        assertEquals(Vector2(400, 300), onScreen(b))
        view.viewTransform.translate(Vector2(50, 50))
        type("z1")
        assertEquals(Vector2(400, 300), onScreen(b))
        select(up, c)
        type("z0")
        val fitted = view.viewTransform.matrix
        view.viewTransform.translate(Vector2(50, 50))
        type("0")
        assertEquals(fitted, view.viewTransform.matrix)
    }

    // --- folding ---

    @Test
    fun `zf with a motion hides what the motion reaches`() {
        select(a)
        type("zfl")
        assertEquals(listOf("b", "side"), hidden())
        view.g.undo()
        assertEquals(emptyList(), hidden())
        type("zft")
        assertEquals(listOf("b", "c", "side"), hidden())
        type("zfL")
        assertEquals(listOf("b", "c", "side", "up"), hidden())
    }

    @Test
    fun `the target and the selection stay visible`() {
        select(a, b)
        type("zft")
        assertEquals(listOf("c", "side"), hidden())
        assertEquals(setOf(a, b), view.g.selectedNodes.toSet())
    }

    @Test
    fun `zd unhides what the motion reaches, through hidden nodes`() {
        select(a)
        type("zft")
        type("zdl")
        assertEquals(listOf("c"), hidden())
        type("zdt")
        assertEquals(emptyList(), hidden())
    }

    @Test
    fun `za folds, and unfolds when the motion reaches something hidden`() {
        select(a)
        type("zal")
        assertEquals(listOf("b", "side"), hidden())
        type("zal")
        assertEquals(emptyList(), hidden())
        // one hidden node among those reached is enough to unfold
        select(b)
        type("zfl")
        select(a)
        type("zat")
        assertEquals(emptyList(), hidden())
    }

    @Test
    fun `each operator is one undo step`() {
        select(a)
        val before = steps
        type("zft")
        assertEquals(before + 1, steps)
        type("zdt")
        assertEquals(before + 2, steps)
        view.g.undo()
        assertEquals(listOf("b", "c", "side"), hidden())
        view.g.undo()
        assertEquals(emptyList(), hidden())
    }

    @Test
    fun `zi zI zo zO toggle children, everything downstream, parents, everything upstream`() {
        select(b)
        type("zi")
        assertEquals(listOf("c"), hidden())
        type("zi")
        assertEquals(emptyList(), hidden())
        type("zo")
        assertEquals(listOf("a"), hidden())
        type("zO")
        assertEquals(emptyList(), hidden())
        type("zO")
        assertEquals(listOf("a", "up"), hidden())
        select(a)
        type("zR")
        type("zI")
        assertEquals(listOf("b", "c", "side"), hidden())
    }

    @Test
    fun `zm hides the selection, zr unhides one level, zR all of them`() {
        select(c)
        type("zm")
        select(b)
        type("zm")
        assertEquals(listOf("b", "c"), hidden())
        // both are at level 1: one level shows them
        type("zr")
        assertEquals(emptyList(), hidden())
        select(c)
        type("zm")
        select(a)
        type("zft")
        assertEquals(listOf("b", "c", "side"), hidden())
        type("zR")
        assertEquals(emptyList(), hidden())
        view.g.undo()
        assertEquals(listOf("b", "c", "side"), hidden())
    }

    @Test
    fun `Escape takes an operator back, and a key that is no motion is refused`() {
        select(a)
        type("zf<Esc>")
        type("l")
        assertEquals(emptyList(), hidden())
        assertEquals(setOf(a, b, side), view.g.selectedNodes.toSet())
        select(a)
        type("zfh")
        assertEquals(emptyList(), hidden())
        assertEquals(listOf("fold: `hide` is not a motion (t, T, l, L)"), messages)
    }

    @Test
    fun `the operators are commands with the motion as argument, and a mapping can spell them in keys`() {
        select(a)
        keys.executeCommandLine("fold t")
        assertEquals(listOf("b", "c", "side"), hidden())
        keys.executeCommandLine("unfold select-closure-forward")
        assertEquals(emptyList(), hidden())
        keys.executeCommandLine("map x zfl")
        type("x")
        assertEquals(listOf("b", "side"), hidden())
        assertFalse(a.isHidden)
        assertTrue(view.g.selectedNodes.contains(a))
    }
}
