package client

import Graph
import graph_tools.Edge
import graph_tools.Node
import utils.Rgb
import utils.Vector2

/**
 * The `open-workspace` graph: the workspaces that have a session
 * kept ([Workspace.stateDir]) and whose directory is still there,
 * each with the graphs that were open in it. A workspace's node
 * links to it as a `workspace` ([FileLink.Type.Workspace]): `gf`
 * makes it the workspace, as `:cd` does. A graph's node links to the
 * graph as a `graph`: `gf` opens it here, in the workspace that is.
 *
 * Two columns: the workspaces, sorted by their path, each on the row
 * of the first of its graphs, the one last shown first; the graphs
 * to their right. The workspace that is is coloured as the browsed
 * directory of a [FileTree] is.
 */
object Workspaces {
    private val workspaceColor = Rgb(0xDBE9F6)
    private val currentColor = Rgb(0x8FB8E0)
    private const val COLUMN_GAP = 40.0
    private const val ROW_GAP = 8.0

    /** The tab's title. */
    const val TITLE = "workspaces"

    fun build(configDir: Path = Fs.configDir): Graph {
        val g = Graph()
        val kept = try {
            Fs.list(configDir.resolve("workspaces"))
        } catch (_: Exception) {
            emptyList()
        }
        val current = Workspace.dir.toAbsolutePath().normalize()
        val workspaces = kept
            .mapNotNull { state -> Workspace.decode(state.name) }
            .filter { Fs.isDirectory(it) }
            .sortedBy { it.toString() }
        val blocks = workspaces.map { dir ->
            val node = link(dir.toString(), dir, FileLink.Type.Workspace).also { it.attributes.bg = if (dir == current) currentColor else workspaceColor }
            val snapshot = Workspace.session(dir, configDir).load()
            val shown = snapshot.activeFile?.toString()
            val graphs = snapshot.files.sortedBy { it.toString() != shown }.map { link(it.name, it, FileLink.Type.Graph) }
            (listOf(node) + graphs).forEach { g.add(it) }
            graphs.forEach { g.add(Edge(node, it)) }
            node to graphs
        }
        layout(g, blocks)
        g.lastActiveNode = blocks.firstOrNull { (n, _) -> n.attributes.bg == currentColor }?.first ?: blocks.firstOrNull()?.first
        return g
    }

    private fun link(text: String, to: Path, type: FileLink.Type): Node =
        Node(text, Vector2(0, 0)).also {
            it.attributes.other[FileLink.ATTRIBUTE] = to.toString()
            it.attributes.other[FileLink.TYPE_ATTRIBUTE] = if (type == FileLink.Type.Workspace) "workspace" else "graph"
        }

    private fun layout(g: Graph, blocks: List<Pair<Node, List<Node>>>) {
        g.ensureCachesFresh(force = true)
        val size = { n: Node -> n.cache.shapeBounds }
        val rowHeight = (g.nodes.maxOfOrNull { size(it).y } ?: 0.0) + ROW_GAP
        val left = blocks.maxOfOrNull { size(it.first).x } ?: 0.0
        val right = left + COLUMN_GAP
        blocks.fold(0) { row, (node, graphs) ->
            node.position = Vector2(left - size(node).x / 2, row * rowHeight)
            graphs.forEachIndexed { i, n -> n.position = Vector2(right + size(n).x / 2, (row + i) * rowHeight) }
            row + maxOf(1, graphs.size)
        }
        // the middle of it all where a fresh tab looks
        val middle = g.nodes.takeIf { it.isNotEmpty() }?.let { nodes ->
            (nodes.map { it.position - size(it) / 2 }.reduce(Vector2::min) + nodes.map { it.position + size(it) / 2 }.reduce(Vector2::max)) / 2
        } ?: Vector2.Zero
        g.nodes.forEach { it.position = it.position - middle }
        g.needsRecomputing(g.nodes)
    }
}
