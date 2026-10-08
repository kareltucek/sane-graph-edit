package ui

import Graph
import graph_tools.Edge
import graph_tools.Node
import utils.Vector2
import java.awt.Color
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
 *
 * A directory's files are one column to its right, its
 * subdirectories two, so both start on the same row. The subtree
 * is cut at the deepest level with which the graph still fits the
 * screen (at least the browsed directory's own files and
 * subdirectories); the directories of the last level are `name/ …`.
 */
object FileTree {
    const val MAX_ENTRIES = 2000

    /** The window's initial size, for when there is no view to measure yet. */
    val DEFAULT_SCREEN = Vector2(800.0, 600.0)

    /** Part of the screen the graph may take (the rest is margin and the status bar). */
    private const val SCREEN_FILL = 0.9

    private val dirColor = Color(0xDBE9F6)
    private val currentDirColor = Color(0x8FB8E0)
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

        /** Levels of entries to show below this directory when nothing is cut. */
        val levels: Int get() = if (children.none { it.shown }) 0 else 1 + children.filter { it.shown }.maxOf { it.levels }
    }

    /** The tree of [dir], cut to fit a view of [screen] pixels. */
    fun build(dir: Path, screen: Vector2 = DEFAULT_SCREEN): Graph {
        val root = dir.toAbsolutePath().normalize()
        val top = scan(root)
        val available = screen * SCREEN_FILL
        // deeper trees are only larger: take the last one that fits
        return (2..top.levels).asSequence()
            .map { render(root, top, it, available) }
            .takeWhile { it.fits }
            .lastOrNull()?.graph
            ?: render(root, top, 1, available).graph
    }

    private class Rendered(val graph: Graph, val fits: Boolean)

    /** The graph with [maxLevels] levels of entries below [root]. */
    private fun render(root: Path, top: Entry, maxLevels: Int, available: Vector2): Rendered {
        val g = Graph()
        val ancestors = generateSequence(root) { it.parent }.toList().reversed()
        val chain = ancestors.map { p ->
            dirNode(p, if (p == root) currentDirColor else dirColor).also { g.add(it) }
        }
        chain.zipWithNext().forEach { (a, b) -> g.add(Edge(a, b)) }
        chain.last().attributes.nodeScale = 1.0

        val files = mutableMapOf<Node, List<Node>>()
        val dirs = mutableMapOf<Node, List<Node>>()
        fun addSubtree(parent: Node, entries: List<Entry>, level: Int) {
            val (dirEntries, fileEntries) = entries.filter { it.shown }.partition { it.isDir }
            val expanded = dirEntries.filter { it.scanned && level < maxLevels }
            val dirNodes = dirEntries.associateWith { e -> dirNode(e.path, dirColor, unexplored = e !in expanded) }
            files[parent] = fileEntries.map { e -> fileNode(e.path) }
            dirs[parent] = dirNodes.values.toList()
            (files.getValue(parent) + dirs.getValue(parent)).forEach { n ->
                g.add(n)
                g.add(Edge(parent, n))
            }
            expanded.forEach { e -> addSubtree(dirNodes.getValue(e), e.children, level + 1) }
        }
        addSubtree(chain.last(), top.children, 1)

        val fits = layout(g, chain, files, dirs, available)
        g.lastActiveNode = chain.last()
        return Rendered(g, fits)
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

    private fun dirNode(p: Path, color: Color, unexplored: Boolean = false): Node {
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
     * Left-aligned columns (width = widest node in the column): one
     * per ancestor, then a directory's [files] in the next column and
     * its [dirs] in the one after. Files take consecutive rows from
     * the directory's first row, and so do the subdirectories'
     * blocks; a directory is centred on its subdirectories (or its
     * files); the ancestor chain is on the browsed directory's row.
     *
     * True if the graph fits [available]: it is then centred on the
     * origin, otherwise the browsed directory is — a fresh tab's
     * first paint centres the origin.
     */
    private fun layout(
        g: Graph,
        chain: List<Node>,
        files: Map<Node, List<Node>>,
        dirs: Map<Node, List<Node>>,
        available: Vector2,
    ): Boolean {
        g.ensureCachesFresh(force = true)
        val size = { n: Node -> n.cache.shapeBounds }
        val column = mutableMapOf<Node, Int>()
        chain.forEachIndexed { i, n -> column[n] = i }
        fun assignColumns(n: Node) {
            files[n].orEmpty().forEach { f -> column[f] = column.getValue(n) + 1 }
            dirs[n].orEmpty().forEach { d -> column[d] = column.getValue(n) + 2; assignColumns(d) }
        }
        assignColumns(chain.last())

        // a column nothing landed in (files, when there are only subdirectories) takes no space
        val columnWidth = (0..column.values.max()).map { c -> column.filterValues { it == c }.keys.maxOfOrNull { size(it).x } }
        val columnLeft = columnWidth.runningFold(0.0) { left, w -> if (w == null) left else left + w + COLUMN_GAP }
        val rowHeight = g.nodes.maxOf { size(it).y } + ROW_GAP

        val y = mutableMapOf<Node, Double>()

        /** Places [n]'s block from row [top] down; the number of rows it took. */
        fun placeRows(n: Node, top: Int): Int {
            val fs = files[n].orEmpty()
            val ds = dirs[n].orEmpty()
            fs.forEachIndexed { i, f -> y[f] = rowHeight * (top + i) }
            val dirRows = ds.fold(0) { rows, d -> rows + placeRows(d, top + rows) }
            val centredOn = ds.ifEmpty { fs }
            y[n] = if (centredOn.isEmpty()) rowHeight * top else (y.getValue(centredOn.first()) + y.getValue(centredOn.last())) / 2
            return maxOf(fs.size, dirRows, 1)
        }
        placeRows(chain.last(), 0)
        chain.dropLast(1).forEach { y[it] = y.getValue(chain.last()) }

        val centre = { n: Node -> Vector2(columnLeft[column.getValue(n)] + size(n).x / 2, y.getValue(n)) }
        val topLeft = g.nodes.map { centre(it) - size(it) / 2 }.reduce(Vector2::min)
        val bottomRight = g.nodes.map { centre(it) + size(it) / 2 }.reduce(Vector2::max)
        val extent = bottomRight - topLeft
        val fits = extent.x <= available.x && extent.y <= available.y
        val origin = if (fits) (topLeft + bottomRight) / 2 else centre(chain.last())
        g.nodes.forEach { n -> n.position = centre(n) - origin }
        g.needsRecomputing(g.nodes)
        return fits
    }
}
