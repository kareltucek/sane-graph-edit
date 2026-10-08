package client

import Graph
import graph_tools.Edge
import graph_tools.Node
import utils.Rgb
import utils.Vector2
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The `:browse` graph: a file tree used as a file picker. Laid out
 * left to right — the filesystem root on the left, the chain of
 * parents down to [build]'s directory, then its subtree fanning out
 * to the right. Every node carries a `link` ([FileLink]) to its
 * path, so `gf` opens a `.dot` file or browses a directory.
 *
 * Only directories and `.dot` files are shown, hidden entries
 * (`.git`, …) are skipped. The scan is breadth-first and stops after
 * [MAX_ENTRIES] directories + `.dot` files, so browsing a huge
 * directory (`$HOME`) stays quick and shows its top levels.
 * Directories scanned completely without a `.dot` file below them
 * are left out; directories the scan didn't reach are shown as
 * `name/ …` (`gf` browses into them).
 */
object FileTree {
    const val MAX_ENTRIES = 2000

    private val dirColor = Rgb(0xDBE9F6)
    private val currentDirColor = Rgb(0x8FB8E0)
    private const val COLUMN_GAP = 40.0
    private const val ROW_GAP = 8.0

    /** A directory or `.dot` file below the browsed directory. */
    private class Entry(val path: Path, val isDir: Boolean) {
        val children: MutableList<Entry> = mutableListOf()
        var scanned: Boolean = false

        /** Scanned, and so is everything below. */
        val complete: Boolean get() = !isDir || (scanned && children.all { it.complete })

        /** Worth a node: a graph, a directory with one below, or one we can't tell about. */
        val shown: Boolean get() = !isDir || !complete || children.any { it.shown }
    }

    fun build(dir: Path): Graph {
        val root = dir.toAbsolutePath().normalize()
        val top = scan(root)

        val g = Graph()
        val ancestors = generateSequence(root) { it.parent }.toList().reversed()
        val chain = ancestors.map { p ->
            dirNode(p, if (p == root) currentDirColor else dirColor).also { g.add(it) }
        }
        chain.zipWithNext().forEach { (a, b) -> g.add(Edge(a, b)) }
        chain.last().attributes.nodeScale = 1.0

        val childNodes = mutableMapOf<Node, List<Node>>()
        fun addSubtree(parent: Node, entries: List<Entry>) {
            childNodes[parent] = entries.filter { it.shown }.map { e ->
                val n = if (e.isDir) dirNode(e.path, dirColor, unexplored = !e.scanned) else fileNode(e.path)
                g.add(n)
                g.add(Edge(parent, n))
                addSubtree(n, e.children)
                n
            }
        }
        addSubtree(chain.last(), top.children)

        layout(g, chain, childNodes)
        g.lastActiveNode = chain.last()
        return g
    }

    /**
     * Breadth-first scan below [root]: directories and `.dot` files,
     * sorted directories first. Directories still queued when
     * [MAX_ENTRIES] is reached stay unscanned.
     */
    private fun scan(root: Path): Entry {
        val top = Entry(root, isDir = true)
        val queue = ArrayDeque(listOf(top))
        var found = 0
        while (queue.isNotEmpty() && found < MAX_ENTRIES) {
            val dir = queue.removeFirst()
            val listed = try {
                Files.list(dir.path).use { s -> s.toList() }
            } catch (_: Exception) {
                emptyList()
            }
            val entries = listed
                .filter { !it.fileName.toString().startsWith(".") }
                .filter { isDir(it) || it.fileName.toString().endsWith(".dot") }
                .sortedWith(compareBy({ !isDir(it) }, { it.fileName.toString().lowercase() }))
                .map { Entry(it, isDir(it)) }
            dir.children.addAll(entries)
            dir.scanned = true
            found += entries.size
            queue.addAll(entries.filter { it.isDir })
        }
        return top
    }

    /** Symlinked directories are not followed (they can loop). */
    private fun isDir(p: Path): Boolean = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)

    private fun dirNode(p: Path, color: Rgb, unexplored: Boolean = false): Node {
        val name = p.fileName?.toString()?.plus("/") ?: p.toString()
        return Node(if (unexplored) "$name …" else name, Vector2(0, 0)).also {
            it.attributes.bg = color
            it.attributes.other[FileLink.ATTRIBUTE] = p.toString()
        }
    }

    private fun fileNode(p: Path): Node =
        Node(p.fileName.toString(), Vector2(0, 0)).also {
            it.attributes.other[FileLink.ATTRIBUTE] = p.toString()
        }

    /**
     * Left-aligned columns by depth (width = widest node in the
     * column); leaves on consecutive rows, a parent centred on its
     * children; the ancestor chain on the browsed directory's row.
     */
    private fun layout(g: Graph, chain: List<Node>, childNodes: Map<Node, List<Node>>) {
        g.ensureCachesFresh(force = true)
        val size = { n: Node -> n.cache.shapeBounds }
        val depth = mutableMapOf<Node, Int>()
        chain.forEachIndexed { i, n -> depth[n] = i }
        fun assignDepth(n: Node) {
            childNodes[n].orEmpty().forEach { c -> depth[c] = depth.getValue(n) + 1; assignDepth(c) }
        }
        assignDepth(chain.last())

        val maxDepth = depth.values.max()
        val columnWidth = (0..maxDepth).map { d -> depth.filterValues { it == d }.keys.maxOf { size(it).x } }
        val columnLeft = columnWidth.runningFold(0.0) { left, w -> left + w + COLUMN_GAP }
        val rowHeight = g.nodes.maxOf { size(it).y } + ROW_GAP

        var nextRow = 0
        val y = mutableMapOf<Node, Double>()
        fun placeRows(n: Node) {
            val cs = childNodes[n].orEmpty()
            cs.forEach { placeRows(it) }
            y[n] = if (cs.isEmpty()) rowHeight * nextRow++ else (y.getValue(cs.first()) + y.getValue(cs.last())) / 2
        }
        placeRows(chain.last())
        chain.dropLast(1).forEach { y[it] = y.getValue(chain.last()) }

        // origin on the browsed directory: a fresh tab's first paint centres the origin
        val current = chain.last()
        val origin = Vector2(columnLeft[depth.getValue(current)] + size(current).x / 2, y.getValue(current))
        g.nodes.forEach { n ->
            val d = depth.getValue(n)
            n.position = Vector2(columnLeft[d] + size(n).x / 2, y.getValue(n)) - origin
        }
        g.needsRecomputing(g.nodes)
    }
}
