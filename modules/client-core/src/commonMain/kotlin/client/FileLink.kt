package client

import graph_tools.Node
import graph_tools.NodeType

/**
 * Node links (see docs/file-format.md): `link-<letter>` attributes
 * holding `<path>`, `<path>:<nodeid>`, `<path>:<lines>` or
 * `<path>#<section>`, followed with `g<letter>`.
 * Plain `link` is an alias for `link-f` (`gf`).
 * What the target is may be given by `link-content-type-<letter>`
 * ([Type]); without it, it is told from the path.
 */
object FileLink {
    /** Attribute written by [FileTree]; the same as `link-f`. */
    const val ATTRIBUTE = "link"

    /** Attribute of the type of the link `link`; `link-content-type-<letter>` of the others. */
    const val TYPE_ATTRIBUTE = "link-content-type"

    /** What a link points at, as `link-content-type-<letter>` says. */
    sealed interface Type {
        /** Told from the path, as links always were: `file`, or no type at all. */
        data object File : Type

        /** A text file shown as text of [type] (`plain`, `markdown`, `code-<language>`): `<path>`, `<path>:<lines>`, `<path>#<section>`. */
        data class Text(val type: NodeType) : Type

        /** A graph, whatever its file is called: `<path>`, `<path>:<nodeid>`. */
        data object Graph : Type

        /** A directory, shown as a file tree. */
        data object Directory : Type

        /** A directory to make the workspace ([Shell.switchWorkspace]). */
        data object Workspace : Type

        companion object {
            /** The type [value] names; [File] for none; null for one that is not a link's (a terminal is not a place to go to). */
            fun parse(value: String?): Type? {
                val name = value?.trim()?.lowercase().orEmpty()
                return when (name) {
                    "", "file" -> File
                    "graph" -> Graph
                    "directory" -> Directory
                    "workspace" -> Workspace
                    else -> NodeType.parse(name)?.takeIf { it.source == null && it.kind != NodeType.Kind.Terminal }?.let { Text(it) }
                }
            }
        }
    }

    fun typeAttributeName(letter: Char): String = "$TYPE_ATTRIBUTE-$letter"

    /** The raw `link-content-type-<letter>` value of [node]; `link-content-type` counts as `link-content-type-f`. */
    fun typeOf(node: Node, letter: Char): String? =
        node.attributes.other[typeAttributeName(letter)]
            ?: node.attributes.other[TYPE_ATTRIBUTE]?.takeIf { letter == 'f' }

    /** Where a link points: a file or directory, and optionally a node in it, or lines of a text file. */
    data class Target(
        /** Null: the graph the link is in (`:<nodeid>`). */
        val path: Path?,
        val nodeId: String?,
        /** `<file>:<line>` / `<file>:<from>-<to>` of a non-`.dot` file: shown as a [SourceView]. */
        val lines: Lines? = null,
        /** The built-in help graph ([HELP]). */
        val help: Boolean = false,
        /** `<file>#<section>`: the section [lines] are of ([Sections]); with no [lines], the file has no such section. */
        val section: String? = null,
        /** `<file>:/<pattern>/,…`: what [lines] were found by ([Anchor]); with no [lines], they are not in the file. */
        val anchor: Anchor? = null,
        /** What the link said the target is. */
        val type: Type = Type.File,
    )

    /** Link value of the built-in help graph (`Graph.defaultGraph`). */
    const val HELP = "sge:help"

    /** Link value of the index of the examples the application carries ([HelpFiles]). */
    const val EXAMPLES = "sge:examples"

    /** Link value of what the controllers of a headset do ([HelpFiles.vrControls]). */
    const val VR_HELP = "sge:vr-help"

    /** Whether a headset is served (`--vr`): the help nodes the application makes lead to [VR_HELP] instead of [HELP]. */
    var inVr = false

    /** Where a help node the application makes leads. */
    val helpLink: String get() = if (inVr) VR_HELP else HELP

    /** Tab title of the help graph. */
    const val HELP_TITLE = "help"

    /** What a node that leads to the help says. */
    const val HELP_NODE = "help\n(press gf)"

    /** A line (`to == null`: shown with context around it) or a range, 1-based, inclusive. */
    data class Lines(val from: Int, val to: Int?) {
        override fun toString(): String = if (to == null) "$from" else "$from-$to"
    }

    private val lineSpec = Regex("([0-9]+)(?:-([0-9]+))?")

    fun attributeName(letter: Char): String = "link-$letter"

    /** The letter of the link attribute [name] (`link` is `f`); null for an attribute that is not a link. */
    private fun letterOf(name: String): Char? =
        when {
            name == ATTRIBUTE -> 'f'
            name.length == ATTRIBUTE.length + 2 && name.startsWith("$ATTRIBUTE-") && name.last().isLetter() -> name.last()
            else -> null
        }

    /** Letters of [node]'s links, sorted; `link` counts as `f`. */
    fun letters(node: Node): List<Char> = node.attributes.other.keys.mapNotNull { letterOf(it) }.distinct().sorted()

    /**
     * [node], copied from a graph whose file is [from] into one whose
     * file is [to] (null for an untitled one), leads to where it led:
     * its links and the file its text is kept in are written for [to].
     */
    fun moveTo(node: Node, from: Path?, to: Path?) {
        val other = node.attributes.other
        other.keys.filter { letterOf(it) != null }.forEach { name -> other[name]?.let { other[name] = moved(it, from, to) } }
        NodeType.of(node).takeIf { it.source != null }?.let { type ->
            other[NodeType.ATTRIBUTE] = type.copy(source = moved(type.source!!, from, to)).attribute!!
        }
    }

    /**
     * [link], written in a graph whose file is [from], as it is written
     * in one whose file is [to] to lead to the same place: a relative
     * path relative to the directory of [to], or absolute when [to] is
     * null; `:<nodeid>`, the graph the link is in, names [from].
     * What follows the path (`:<lines>`, `#<section>`) stays.
     */
    fun moved(link: String, from: Path?, to: Path?): String {
        val after = { at: Int -> at >= 0 && link.substring(at + 1).let { it.isNotEmpty() && '/' !in it } }
        val cut = anchorOf(link)?.first ?: listOf(link.lastIndexOf(':'), link.lastIndexOf('#')).filter(after).minOrNull() ?: link.length
        val path = link.substring(0, cut)
        val dir = { file: Path? -> file?.toAbsolutePath()?.getParent() ?: Workspace.dir }
        val fixed = path.startsWith("/") || path == "~" || path.startsWith("~/") || link == HELP || link == EXAMPLES || link == VR_HELP
        val target = when {
            fixed -> null
            path.isEmpty() -> from?.takeIf { cut < link.length && link[cut] == ':' }
            else -> dir(from).resolve(path)
        }?.toAbsolutePath()?.normalize()
        val same = from?.toAbsolutePath()?.normalize() == to?.toAbsolutePath()?.normalize()
        return if (target == null || same) link else (if (to == null) "$target" else relative(target, dir(to))) + link.substring(cut)
    }

    /** [target] as a path relative to the directory [dir]. */
    private fun relative(target: Path, dir: Path): String {
        val parts = { p: Path -> "${p.toAbsolutePath().normalize()}".split('/').filter { it.isNotEmpty() } }
        val to = parts(target)
        val from = parts(dir)
        val common = to.zip(from).takeWhile { (a, b) -> a == b }.size
        return (List(from.size - common) { ".." } + to.drop(common)).joinToString("/")
    }

    /** The raw `link-<letter>` value of [node]; `link` counts as `link-f`. */
    fun of(node: Node, letter: Char): String? =
        node.attributes.other[attributeName(letter)]
            ?: node.attributes.other[ATTRIBUTE]?.takeIf { letter == 'f' }

    /**
     * Resolve a link value. `<path>:<nodeid>` names a node when the
     * part after the last `:` is non-empty and has no `/`; likewise
     * `<path>#<section>`. So a file whose name has a `:` or a `#` in
     * it cannot be linked to, and nothing is asked of the file system
     * to tell. An empty `<path>` means the graph the link is in.
     *
     * A link of a [type] other than [Type.File] is read by it: a
     * graph's suffix is a node, a text's lines or a section, and a
     * directory or workspace has none.
     */
    fun resolve(link: String, containingFile: Path?, type: Type = Type.File): Target =
        resolveIn(link, containingFile?.toAbsolutePath()?.getParent() ?: Workspace.dir, type)

    /** Like [resolve], with relative paths relative to [baseDir] (e.g. a picker's output). */
    fun resolveIn(link: String, baseDir: Path, type: Type = Type.File): Target =
        when (type) {
            Type.File -> told(link, baseDir)
            Type.Directory, Type.Workspace -> Target(resolvePath(link, baseDir), null, type = type)
            Type.Graph -> {
                val colon = link.lastIndexOf(':')
                val nodeId = link.substring(colon + 1).takeIf { colon >= 0 && it.isNotEmpty() && '/' !in it }
                val pathPart = if (nodeId != null) link.substring(0, colon) else link
                Target(pathPart.takeIf { it.isNotEmpty() }?.let { resolvePath(it, baseDir) }, nodeId, type = type)
            }
            is Type.Text -> anchored(link, baseDir, type) ?: run {
                val hash = link.lastIndexOf('#')
                val section = link.substring(hash + 1).takeIf { hash > 0 && it.isNotEmpty() && '/' !in it }
                val colon = link.lastIndexOf(':')
                val lines = link.substring(colon + 1).takeIf { section == null && colon > 0 }?.let { lineSpec.matchEntire(it) }
                    ?.let { m -> Lines(m.groupValues[1].toInt(), m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()) }
                when {
                    section != null -> resolvePath(link.substring(0, hash), baseDir).let { Target(it, null, Sections.find(it, section), section = section, type = type) }
                    lines != null -> Target(resolvePath(link.substring(0, colon), baseDir), null, lines, type = type)
                    else -> Target(resolvePath(link, baseDir), null, type = type)
                }
            }
        }

    /** Where the path of [link] ends when what follows is an [Anchor] (a file name has no `:`), and the anchor. */
    private fun anchorOf(link: String): Pair<Int, Anchor>? =
        link.indexOf(':').takeIf { it > 0 }?.let { colon -> Anchor.parse(link.substring(colon + 1))?.let { colon to it } }

    /** [link] as lines of a text file named by an [Anchor]; null when it has none. A `.dot` file is a graph unless the link is typed. */
    private fun anchored(link: String, baseDir: Path, type: Type): Target? =
        anchorOf(link)?.takeIf { (colon, _) -> type != Type.File || !link.substring(0, colon).endsWith(".dot") }?.let { (colon, anchor) ->
            resolvePath(link.substring(0, colon), baseDir).let { Target(it, null, anchor.find(it), anchor = anchor, type = type) }
        }

    /** A link of no type: what it points at told from the path. */
    private fun told(link: String, baseDir: Path): Target = anchored(link, baseDir, Type.File) ?: toldOtherwise(link, baseDir)

    private fun toldOtherwise(link: String, baseDir: Path): Target {
        val whole = resolvePath(link, baseDir)
        val colon = link.lastIndexOf(':')
        val nodeId = link.substring(colon + 1).takeIf { colon >= 0 && it.isNotEmpty() && '/' !in it }
        val split = nodeId != null
        val hash = link.lastIndexOf('#')
        val section = link.substring(hash + 1).takeIf { hash > 0 && it.isNotEmpty() && '/' !in it }
        val pathPart = link.substring(0, colon.coerceAtLeast(0))
        // in a DOT file a number is a node id; in any other file it's a line (range)
        val lines = nodeId
            ?.takeIf { pathPart.isNotEmpty() && !pathPart.endsWith(".dot") }
            ?.let { lineSpec.matchEntire(it) }
            ?.let { m -> Lines(m.groupValues[1].toInt(), m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()) }
        return when {
            link == HELP -> Target(null, null, help = true)
            link == EXAMPLES -> Target(HelpFiles.index, null)
            link == VR_HELP -> Target(HelpFiles.vrControls, null)
            section != null -> resolvePath(link.substring(0, hash), baseDir).let { Target(it, null, Sections.find(it, section), section = section) }
            !split -> Target(whole, null)
            pathPart.isEmpty() -> Target(null, nodeId)
            lines != null -> Target(resolvePath(pathPart, baseDir), null, lines)
            else -> Target(resolvePath(pathPart, baseDir), nodeId)
        }
    }

    /** `~/` is the home directory; a relative path is relative to [baseDir]. */
    private fun resolvePath(path: String, baseDir: Path): Path {
        val expanded = if (path == "~" || path.startsWith("~/")) {
            Fs.home + path.removePrefix("~")
        } else {
            path
        }
        return baseDir.resolve(expanded).normalize()
    }
}
