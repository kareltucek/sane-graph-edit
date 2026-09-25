package ui

import graph_tools.Node
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Node links (see docs/file-format.md): `link-<letter>` attributes
 * holding `<path>` or `<path>:<nodeid>`, followed with `g<letter>`.
 * Plain `link` is an alias for `link-f` (`gf`).
 */
object FileLink {
    /** Attribute written by [FileTree]; the same as `link-f`. */
    const val ATTRIBUTE = "link"

    /** Where a link points: a file or directory, and optionally a node in it. */
    data class Target(
        /** Null: the graph the link is in (`:<nodeid>`). */
        val path: Path?,
        val nodeId: String?,
    )

    fun attributeName(letter: Char): String = "link-$letter"

    /** Letters of [node]'s links, sorted; `link` counts as `f`. */
    fun letters(node: Node): List<Char> =
        node.attributes.other.keys
            .mapNotNull { k ->
                when {
                    k == ATTRIBUTE -> 'f'
                    k.length == ATTRIBUTE.length + 2 && k.startsWith("$ATTRIBUTE-") && k.last().isLetter() -> k.last()
                    else -> null
                }
            }
            .distinct()
            .sorted()

    /** The raw `link-<letter>` value of [node]; `link` counts as `link-f`. */
    fun of(node: Node, letter: Char): String? =
        node.attributes.other[attributeName(letter)]
            ?: node.attributes.other[ATTRIBUTE]?.takeIf { letter == 'f' }

    /**
     * Resolve a link value. `<path>:<nodeid>` names a node when the
     * part after the last `:` is non-empty and has no `/`, and the
     * whole value isn't itself an existing path. An empty `<path>`
     * means the graph the link is in.
     */
    fun resolve(link: String, containingFile: Path?): Target {
        val whole = resolvePath(link, containingFile)
        val colon = link.lastIndexOf(':')
        val nodeId = link.substring(colon + 1).takeIf { colon >= 0 && it.isNotEmpty() && '/' !in it }
        val split = nodeId != null && !Files.exists(whole)
        val pathPart = link.substring(0, colon.coerceAtLeast(0))
        return when {
            !split -> Target(whole, null)
            pathPart.isEmpty() -> Target(null, nodeId)
            else -> Target(resolvePath(pathPart, containingFile), nodeId)
        }
    }

    /**
     * `~/` is the home directory; a relative path is relative to the
     * directory of [containingFile] (the file the node lives in), or
     * to the working directory when the tab has no file.
     */
    private fun resolvePath(path: String, containingFile: Path?): Path {
        val expanded = if (path == "~" || path.startsWith("~/")) {
            System.getProperty("user.home") + path.removePrefix("~")
        } else {
            path
        }
        val base = containingFile?.toAbsolutePath()?.parent ?: Paths.get("").toAbsolutePath()
        return base.resolve(expanded).normalize()
    }
}
