package client

import Graph
import graph_tools.Node
import graph_tools.NodeType
import utils.Vector2

/**
 * Graph for a link to a text file — all of it, `<file>:<line>`,
 * `<file>:<from>-<to>` or `<file>#<section>`: one node whose text is
 * kept in that file ([FileContent]), so that it is edited there. The
 * view opens with the node being edited. The node sits at the origin.
 */
object SourceView {
    /** DOT id of the node. */
    const val NODE_ID = "source"

    /** What neovim calls the kind of file, by the end of its name; the others are told by the name itself or are plain text. */
    private val languages = mapOf(
        "kt" to "kotlin", "kts" to "kotlin", "py" to "python", "js" to "javascript", "ts" to "typescript", "rs" to "rust", "rb" to "ruby",
        "sh" to "sh", "bash" to "sh", "h" to "c", "hpp" to "cpp", "cc" to "cpp", "yml" to "yaml", "gv" to "dot", "tex" to "tex", "pl" to "perl",
    )
    private val asNamed = setOf("scala", "java", "c", "cpp", "go", "json", "yaml", "xml", "html", "css", "sql", "lua", "vim", "toml", "dot", "sbt")

    /** After the path, what of the file is meant, as a link says it. */
    private fun part(target: FileLink.Target): String =
        target.section?.let { "#$it" } ?: target.lines?.let { ":$it" }.orEmpty()

    fun title(target: FileLink.Target): String = "${target.path?.name}${part(target)}"

    /** The type of a node that holds [target]: what the link says it is, else what the file's name says, kept where [target] says. */
    fun typeOf(target: FileLink.Target): NodeType {
        val path = target.path!!.toAbsolutePath().normalize()
        val extension = path.name.substringAfterLast('.', "").lowercase()
        val source = "$path${part(target)}"
        val said = (target.type as? FileLink.Type.Text)?.type
        return when {
            said != null -> said.copy(source = source)
            extension == "md" || extension == "markdown" -> NodeType(NodeType.Kind.Markdown, null, source)
            extension in asNamed -> NodeType(NodeType.Kind.Code, extension, source)
            extension in languages -> NodeType(NodeType.Kind.Code, languages[extension], source)
            else -> NodeType(NodeType.Kind.Plain, null, source)
        }
    }

    /** The graph; throws when the file cannot be read. */
    fun build(target: FileLink.Target): Graph {
        val type = typeOf(target)
        val text = FileContent.read(FileLink.Target(target.path, null, target.lines)) ?: throw IllegalStateException("it cannot be read")
        return Graph().also { g ->
            g.add(
                Node(name = NODE_ID).also {
                    it.attributes.text = text
                    it.attributes.other[NodeType.ATTRIBUTE] = type.attribute!!
                    it.attributes.other["fontname"] = "monospace"
                    it.position = Vector2(0, 0)
                },
            )
        }
    }
}
