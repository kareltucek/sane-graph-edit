@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import DotGraphLoader
import Graph
import client.PointerInput.Button
import graph_tools.Depth
import graph_tools.Edge
import graph_tools.Node
import graph_tools.TextMeasurer
import utils.Vector2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nodes with a depth. The view is 800 by 600 at zoom 1 with the
 * workspace origin in its middle, so the viewer is 1000 units in
 * front of the plane at 0: what is at 1000, that far behind it,
 * appears half its size, what is at -500 twice.
 */
class DepthTest {
    private val flat = Node("flat", Vector2(0, 0))
    private val far = Node("far", Vector2(200, 100)).also { Depth.set(it, 1000.0) }
    private val near = Node("near", Vector2(-100, -50)).also { Depth.set(it, -500.0) }
    private val behind = Node("behind", Vector2(300, 0)).also { Depth.set(it, -2000.0) }

    private val messages = mutableListOf<String>()
    private val shell = object : Shell by NoShell {
        override fun message(text: String) {
            messages += text
        }
    }

    private val view = EditorView(
        Graph().also { g ->
            listOf(flat, far, near, behind).forEach { g.add(it) }
            listOf(flat to far, near to flat, behind to flat).forEach { (from, to) -> g.add(Edge(from, to)) }
        },
        NoRenderer,
        shell,
    ).also {
        it.size = Vector2(800, 600)
        it.viewTransform.translate(Vector2(400, 300))
        it.g.ensureCachesFresh()
        it.g.unselectAll(it.g.nodes.toList())
        it.g.lastActiveNode = null
    }

    private val keys = KeyMapper(KeyMapper.defaultBindings(), Scheduler { _, _ -> {} }).also {
        registerEditingCommands()
        it.commandExecutor = { command, view -> CommandRegistry.execute(command, view) }
        it.activeView = view
    }

    private fun assertNear(expected: Vector2, actual: Vector2?) =
        assertTrue(actual != null && abs(expected.x - actual.x) < 1e-6 && abs(expected.y - actual.y) < 1e-6, "expected $expected, got $actual")

    private fun screen(n: Node): Vector2 = view.viewTransform.toScreen(view.apparent(n)!!.position)

    @Test
    fun `what is further away appears smaller and nearer the middle, what is nearer bigger and further from it`() {
        assertEquals(Apparent(Vector2(0, 0), 1.0), view.apparent(flat))
        assertEquals(0.5, view.apparent(far)?.scale)
        assertNear(Vector2(100, 50), view.apparent(far)?.position)
        assertEquals(2.0, view.apparent(near)?.scale)
        assertNear(Vector2(-200, -100), view.apparent(near)?.position)
    }

    @Test
    fun `what is behind the viewer is not shown, nor are its edges`() {
        assertNull(view.apparent(behind))
        val scene = view.scene(TextMeasurer.default)
        assertEquals(listOf(far.id, flat.id, near.id), scene.nodes.map { it.id })
        assertEquals(2, scene.edges.size)
    }

    @Test
    fun `the scene has the nodes from the furthest to the nearest, each with how big it appears`() {
        val scene = view.scene(TextMeasurer.default)
        assertEquals(listOf(0.5, 1.0, 2.0), scene.nodes.map { it.scale })
        assertNear(Vector2(100, 50), scene.nodes.first().position)
    }

    @Test
    fun `speaking of another plane leaves everything where it appears`() {
        val before = listOf(flat, far, near).map { screen(it) }
        assertTrue(view.viewTransform.refocus(1000.0, view.size / 2))
        assertEquals(1000.0, view.viewTransform.depth)
        assertEquals(1.0, view.apparent(far)?.scale)
        listOf(flat, far, near).zip(before).forEach { (n, was) -> assertNear(was, screen(n)) }
        // and nothing is in front of the viewer that was not
        assertNull(view.apparent(behind))
    }

    @Test
    fun `a click finds a node where it appears, the nearest first`() {
        val at = { screen: Vector2 -> Clicker.selectClickedNode(view, view.viewTransform.toWorkspace(screen)).toList() }
        assertEquals(listOf(far), at(Vector2(500, 350)))
        // where the far node is, not where it appears
        assertEquals(emptyList(), at(Vector2(600, 400)))
        // the near one covers the plane's
        near.position = Vector2(0, 0)
        assertEquals(listOf(near), at(Vector2(400, 300)))
    }

    @Test
    fun `a dragged node stays under the pointer`() {
        view.pointer.pressed(Vector2(500, 350), Button.Left, false, false)
        view.pointer.moved(Vector2(540, 350), true, false)
        view.pointer.released(Vector2(540, 350), Vector2(540, 350), Button.Left, false)
        assertNear(Vector2(540, 350), screen(far))
        // twice as far in its own plane as on the screen
        assertNear(Vector2(280, 100), far.position)
    }

    @Test
    fun `the wheel over a node goes towards that node`() {
        view.pointer.wheel(Vector2(500, 350), -1.0)
        assertEquals(1000.0, view.viewTransform.depth)
        assertNear(Vector2(500, 350), screen(far))
        assertTrue(view.apparent(far)!!.scale == 1.0 && view.viewTransform.scale > 0.5)
    }

    @Test
    fun `the command moves the selection to a depth or by one, as one undo step`() {
        view.g.cleanSelect(setOf(flat))
        keys.executeCommandLine("z 300")
        assertEquals(300.0, Depth.of(flat))
        keys.executeCommandLine("z -500")
        assertEquals(-200.0, Depth.of(flat))
        keys.executeCommandLine("z =-500")
        assertEquals(-500.0, Depth.of(flat))
        view.g.undo()
        assertEquals(-200.0, Depth.of(flat))
        keys.executeCommandLine("z")
        keys.executeCommandLine("z up")
        assertEquals(listOf("z = -200.0", "z: `up` is not a number (z 100, z +50, z -50, z =-50)"), messages)
    }

    @Test
    fun `a node in the plane has no attribute, and a depth is written to a file and read back`() {
        Depth.set(far, 0.0)
        assertTrue(Depth.ATTRIBUTE !in far.attributes.other)
        val text = DotGraphLoader.serialize(view.g)
        // as the third number of the position, and not as an attribute of its own
        assertTrue("pos=\"-100.0,-50.0,-500.0!\"" in text && "pos=\"200.0,100.0!\"" in text && "z=" !in text, text)
        val back = DotGraphLoader.parse(DotGraphLoader.tokenize(text))
        assertEquals(listOf(-2000.0, -500.0, 0.0, 0.0), back.nodes.map { Depth.of(it) }.sorted())
    }

    @Test
    fun `an attribute z in a file is a depth too`() {
        val read = DotGraphLoader.parse(DotGraphLoader.tokenize("digraph g { a [label=\"a\"; pos=\"1,2!\"; z=\"30\"]; b [label=\"b\"; pos=\"1,2,-4!\"]; }"))
        assertEquals(listOf(-4.0, 30.0), read.nodes.map { Depth.of(it) }.sorted())
    }

    @Test
    fun `a new node is made in the plane the view speaks of`() {
        view.viewTransform.refocus(1000.0, view.size / 2)
        view.gestures.spawnNewNode(Vector2(0, 0))
        assertEquals(1000.0, Depth.of(view.g.nodes.last()))
    }
}
