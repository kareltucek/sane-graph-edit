import graph_tools.*
import parser_dot.IdGen
import parser_dot.ParseLog
import utils.Constants
import utils.Vector2


/**
 * In-memory model for one graph. Owns the set of nodes, edges, current
 * selection, and the lazy "needs recomputing" bookkeeping that the renderer
 * relies on.
 *
 * Design note — dirty tracking: layout measurements (text bounds, shape
 * bounds, edge endpoints) live on `Node.cache` / `Edge.cache`. Whenever a
 * caller mutates something that affects those measurements (position,
 * text, font size, shape) it must call [needsRecomputing] so the next
 * [recompute] pass refreshes the cache. The render loop in
 * `GraphCanvas.doDrawing` calls [recompute] on every paint, so callers
 * normally only need to mark nodes dirty, not compute anything themselves.
 *
 * Design note — identity vs equality: edges intentionally use referential
 * equality (see `Edge.kt:32-47`), which means two edges with the same
 * endpoints are distinct objects. This supports multi-edges and makes it
 * possible to undo a removal by re-inserting the exact same object.
 *
 * See `docs/developer/architecture.md` for the broader picture.
 */
class Graph(
    var id: String = IdGen.global.new("g"),
) {
    // The `*Restricted` names exist purely so that the read-only views
    // below can expose `Set<...>` instead of `MutableSet<...>` to callers.
    private val nodesRestricted: MutableSet<Node> = mutableSetOf()
    private val edgesRestricted: MutableSet<Edge> = mutableSetOf()
    private val selectedNodesRestricted: MutableSet<Node> = mutableSetOf()
    private val edgeMapRestricted: MutableMap<Node, MutableSet<Edge>> = mutableMapOf()
    private val needsRecomputing: MutableSet<Node> = mutableSetOf()

    /**
     * The node that input should default to when there is no cursor under
     * the pointer — e.g. `Space` to edit, `a` to append. Tracks the last
     * add/select operation, which is distinct from `selectedNodes` (a set).
     */
    var lastActiveNode: Node? = null
    /**
     * Structural log produced by the DOT parser. Used by the serializer to
     * emit output that preserves the original file's layout (comment
     * positions, section breaks, declaration order) on round-trip. Null on
     * graphs that were constructed programmatically.
     */
    var parseLog: ParseLog? = null

    /**
     * Graph-level attributes (`rankdir=LR;`, `graph [...]`), not
     * interpreted by the editor, written back on save. Insertion
     * order is kept.
     */
    val attributes: MutableMap<String, String> = linkedMapOf()

    /** Visible set at the last [DynamicPlacement.place]; null while `:set dynamic` is off. */
    var dynamicBaseline: Set<Node>? = null

    private var attachmentOwnersCache: Map<Node, Node>? = null

    /** Attached node → its owner ([Attachments]); recomputed after nodes are added or removed. */
    val attachmentOwners: Map<Node, Node>
        get() = attachmentOwnersCache ?: Attachments.owners(this).also { attachmentOwnersCache = it }

    /** What moving [nodes] really moves: attached nodes are replaced by their (top) owners. */
    fun movable(nodes: Set<Node>): Set<Node> {
        val owners = attachmentOwners
        return if (owners.isEmpty()) nodes else nodes.map { Attachments.root(it, owners) }.toSet()
    }

    /**
     * Undo/redo stack attached to this graph. Every user-visible mutation
     * should go through [commit] (or be pushed via [History.commitWithoutRun]
     * for gestures that mutate the graph directly during the gesture — like
     * node drag — and only record the reversible command on release).
     *
     * Low-level mutators on this class (`add`, `remove`, `addAllEdges`…)
     * intentionally bypass the history: the DOT parser builds a graph from
     * scratch by calling them, and we don't want the user to see a parse
     * log as hundreds of undoable steps. Commands are responsible for
     * calling the low-level mutators from their `redo`/`undo`.
     */
    val history: History = History()

    /**
     * Set when this graph is a client's replica of a shared document:
     * changes then go to the editor, which owns the undo history,
     * and [history] stays empty.
     */
    var committer: Committer? = null

    /** Execute [cmd] and record it as one undo step. */
    fun commit(cmd: Command) {
        committer?.commit(cmd, applied = false) ?: history.apply(cmd)
    }

    /**
     * Record [cmd], whose effect is already in the graph, as one undo
     * step: for gestures that change the graph as they go (a drag)
     * and are recorded once they end.
     */
    fun commitApplied(cmd: Command) {
        committer?.commit(cmd, applied = true) ?: history.commitWithoutRun(cmd)
    }

    fun undo() {
        committer?.undo() ?: history.undo()
    }

    fun redo() {
        committer?.redo() ?: history.redo()
    }

    /** What a file holds: everything a (re)load replaces. See [ReplaceContentCommand]. */
    class Content(
        val id: String,
        val nodes: List<Node>,
        val edges: List<Edge>,
        val selectedNodes: List<Node>,
        val lastActiveNode: Node?,
        val parseLog: ParseLog?,
        val attributes: Map<String, String>,
    )

    fun content(): Content =
        Content(id, nodes.toList(), edges.toList(), selectedNodes.toList(), lastActiveNode, parseLog, LinkedHashMap(attributes))

    /** Make this graph hold [content] instead of what it holds now; the [history] stays. */
    fun replaceContent(content: Content) {
        removeAllNodes(nodes.toList())
        id = content.id
        parseLog = content.parseLog
        attributes.clear()
        attributes.putAll(content.attributes)
        dynamicBaseline = null
        addAllNodes(content.nodes)
        addAllEdges(content.edges)
        cleanSelect(content.selectedNodes)
        lastActiveNode = content.lastActiveNode
    }

    val nodes: Set<Node>
        get() {
            return nodesRestricted
        }
    val edges: Set<Edge>
        get() {
            return edgesRestricted
        }
    val selectedNodes: Set<Node>
        get() {
            return selectedNodesRestricted
        }
    val edgeMap: Map<Node, Set<Edge>>
        get() {
            return edgeMapRestricted
        }

    val subGraphs: MutableSet<Graph> = mutableSetOf()

    fun add(e: Node) {
        addAllNodes(setOf(e))
    }

    fun add(e: Edge) {
        addAllEdges(setOf(e))
    }

    private val nodesById: MutableMap<String, Node> = mutableMapOf()
    private val edgesById: MutableMap<String, Edge> = mutableMapOf()

    fun node(id: String): Node? = nodesById[id]

    fun edge(id: String): Edge? = edgesById[id]

    fun addAllNodes(i: Iterable<Node>) {
        attachmentOwnersCache = null
        nodesRestricted.addAll(i)
        i.forEach { nodesById[it.id] = it }
        edgeMapRestricted.putAll(i.map { it to mutableSetOf() })
        needsRecomputing(i)
        lastActiveNode = i.lastOrNull()
    }

    fun addAllEdges(i: Iterable<Edge>) {
        i.forEach {
            edgesRestricted.add(it)
            edgesById[it.id] = it
            edgeMapRestricted[it.src]!!.add(it)
            edgeMapRestricted[it.dst]!!.add(it)
        }
        // once for all of them: inside the loop it was every edge again for each edge
        EdgeGeometry.recompute(i)
    }

    fun remove(e: Node) {
        removeAllNodes(setOf(e))
    }

    fun remove(e: Edge) {
        removeAllEdges(setOf(e))
    }

    fun removeAllNodes(i: Iterable<Node>) {
        attachmentOwnersCache = null
        i.forEach {
            edgeMapRestricted[it]!!.forEach { e -> edgesById.remove(e.id) }
            edgesRestricted.removeAll(edgeMapRestricted[it]!!)
            edgeMapRestricted.remove(it)
            nodesById.remove(it.id)
            selectedNodesRestricted.remove(it)
            if (lastActiveNode == it) {
                lastActiveNode = null
            }
        }
        nodesRestricted.removeAll(i)
    }

    fun removeAllEdges(i: Iterable<Edge>) {
        i.forEach {
            edgeMapRestricted[it.src]!!.remove(it)
            edgeMapRestricted[it.dst]!!.remove(it)
            edgesRestricted.remove(it)
            edgesById.remove(it.id)
        }
    }

    fun select(e: Node, commitHistory: Boolean = true) {
        selectAll(setOf(e))
    }

    fun selectAll(i: Iterable<Node>, commitHistory: Boolean = true) {
        selectedNodesRestricted.addAll(i)
        i.lastOrNull()?.let {
            lastActiveNode = it
        }
    }

    fun unselect(e: Node, commitHistory: Boolean = true) {
        selectedNodesRestricted.remove(e)
    }

    fun unselectAll(i: Iterable<Node>, commitHistory: Boolean = true) {
        selectedNodesRestricted.removeAll(i)
    }

    fun cleanSelect(i: Iterable<Node>, commitHistory: Boolean = true) {
        selectedNodesRestricted.clear(); selectAll(i)
    }

    fun needsRecomputing(e: Node) {
        needsRecomputing.add(e)
    }

    fun needsRecomputing(e: Iterable<Node>) {
        needsRecomputing.addAll(e)
    }

    fun recompute(measurer: TextMeasurer = TextMeasurer.default) {
        val nodes = needsRecomputing
        nodes.forEach { NodeText.recompute(it, measurer) }
        // after the sizes: attached nodes follow their owners' new positions / sizes
        val glued = Attachments.reglue(this)
        val edges = (nodes + glued).flatMap { edgeMapRestricted[it].orEmpty() }
        EdgeGeometry.recompute(edges)
        needsRecomputing.clear()
    }

    /**
     * Flush pending cache recomputations with the default
     * [TextMeasurer]. Interactive rendering does this every paint
     * frame via [recompute]; code paths that run without a window
     * (headless CLI, SVG export, tests) must call this themselves —
     * otherwise [Node.cache.shapeBounds] and edge endpoint caches
     * stay at their defaults and the layout optimiser ends up
     * treating nodes as dimensionless points.
     *
     * No-op when the dirty set is empty, so it's cheap to call
     * defensively. Pass `force = true` to recompute every node
     * regardless of dirty state.
     */
    fun ensureCachesFresh(force: Boolean = false) {
        if (force) needsRecomputing(nodesRestricted)
        if (needsRecomputing.isEmpty()) return
        recompute()
    }

    fun findEddges(from: Set<Node>, to: Set<Node>): List<Edge> {
        //the first filter ensures that every edge is returned just once in O(n) - otherwise edges inside from will be returned twice
        return from
            .flatMap { n -> edgeMap[n].orEmpty().filter { it.src == n } }
            .filter { from.contains(it.src) && to.contains(it.dst) }
    }

    /**
     * True when [n] should be drawn and is clickable. Normally: not
     * hidden. With [ViewModes.showParents] / [ViewModes.showChildren]
     * on, "shown" nodes are the non-hidden and the selected ones, and
     * [n] is also visible when it is shown, or a parent / child of a
     * shown node. An attached node ([Attachments]) is visible exactly
     * when its owner is.
     */
    fun isVisible(n: Node): Boolean {
        val shown = { m: Node -> !m.isHidden || m in selectedNodesRestricted }
        val anyMode = ViewModes.showParents || ViewModes.showChildren
        val parentOfShown = ViewModes.showParents && findOutEdges(n).any { shown(it.dst) }
        val childOfShown = ViewModes.showChildren && findInEdges(n).any { shown(it.src) }
        val own = !n.isHidden || (anyMode && shown(n)) || parentOfShown || childOfShown
        // glued nodes inherit their owner's visibility, whatever their own state
        val owner = attachmentOwners[n]
        return if (owner != null) isVisible(owner) else own
    }

    /** All nodes passing [isVisible]; linear in nodes + edges. */
    fun visibleNodes(): Set<Node> = nodesRestricted.filter { isVisible(it) }.toSet()

    fun findInEdges(n: Node): List<Edge> = edgeMap[n].orEmpty().filter { it.dst == n }

    fun findOutEdges(n: Node): List<Edge> = edgeMap[n].orEmpty().filter { it.src == n }

    fun findEdge(src: Node, dst: Node, forward: Boolean = true, backward: Boolean = true): Edge? {
        var e: Edge? = null
        //todo: optimize this!
        if (forward) {
            e = edgeMap[src]?.find { it.dst == dst }
        }
        if (backward && e == null) {
            e = edgeMap[src]?.find { it.src == dst }
        }
        return e
    }

    fun printStats() {
        println("Graph has ${nodes.size} nodes, and ${edges.size} vertices!")
    }

    companion object {
        /**
         * The help graph, opened from the `help` node of a file tree
         * (the first launch in a workspace, `:tabnew`, `:browse`).
         * Holds the in-app keystroke and command reference, so a new
         * user can read it without having to find a manual.
         *
         * Layout: a star. The node a reader starts with is in the
         * middle; on each ray from it a small coloured node that
         * names a category, and further out what there is to say of
         * it. The texts are markdown, in [utils.Help]; this function
         * only places them.
         */
        fun defaultGraph(): Graph {
            val g = Graph()
            val markdown = { text: String, at: Vector2 -> Node(text, at).also { it.attributes.other[NodeType.ATTRIBUTE] = NodeType.Markdown.attribute!! } }
            val root = markdown(utils.Help.commands, Vector2(0, 0)).also(g::add)
            val placed = utils.Help.topics.zip(helpRays).map { (topic, where) ->
                val (degrees, distance) = where
                val ray = (degrees * kotlin.math.PI / 180).let { Vector2(kotlin.math.cos(it), -kotlin.math.sin(it)) }
                val category = Node(topic.name, ray * 380.0).also { it.attributes.bg = utils.Rgb(topic.colour) }
                val detail = markdown(topic.text.trimIndent(), ray * distance)
                listOf(category, detail).forEach(g::add)
                g.add(Edge(root, category))
                g.add(Edge(category, detail))
                category to detail
            }
            val selection = placed[0].second
            val marks = markdown(utils.Help.marks, selection.position + Vector2(0, -260)).also(g::add)
            g.add(Edge(selection, marks))
            // a client writes the examples out when it starts and knows where (`sge:examples`)
            val (about, attribution) = placed[3]
            val examples = Node("examples\n(press gf)", attribution.position + Vector2(0, 120)).also {
                it.attributes.bg = utils.Rgb(0xF6EFC4)
                it.attributes.other["link"] = "sge:examples"
            }
            g.add(examples)
            g.add(Edge(about, examples))
            return g
        }

        /** Where the categories of the help graph are: degrees from the right, anticlockwise, and how far out the text of each is. */
        private val helpRays = listOf(90.0 to 640.0, 0.0 to 800.0, -90.0 to 620.0, 180.0 to 760.0) +
            listOf(52.0, 28.0, -28.0, -52.0, -128.0, -152.0, 152.0, 128.0).map { it to 760.0 }
    }
}

