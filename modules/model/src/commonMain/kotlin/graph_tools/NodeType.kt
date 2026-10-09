package graph_tools

import Graph

/**
 * What a node's text is, which decides how a renderer shows it
 * while it is not being edited and how its editor treats it:
 *
 *  - plain text, shown as it is;
 *  - code in some [language] (or in none that is named), shown
 *    highlighted;
 *  - markdown, shown formatted; diagrams are fenced blocks in it
 *    (` ```mermaid `, ` ```dot `).
 *
 * In a file it is the node's `content-type` attribute: `code-scala`,
 * `code`, `markdown`; a plain node has none. A value that is none
 * of these is kept as it is and the node is shown as plain text.
 */
data class NodeType(val kind: Kind, val language: String? = null) {
    enum class Kind(val id: String) {
        Plain("plain"),
        Code("code"),
        Markdown("markdown"),
    }

    /** As written in a file; null for a plain node, which has no attribute. */
    val attribute: String?
        get() = when (kind) {
            Kind.Plain -> null
            Kind.Code -> language?.let { "${Kind.Code.id}-$it" } ?: Kind.Code.id
            Kind.Markdown -> Kind.Markdown.id
        }

    companion object {
        /** The DOT attribute. */
        const val ATTRIBUTE = "content-type"

        val Plain = NodeType(Kind.Plain)
        val Markdown = NodeType(Kind.Markdown)

        /** The type [text] names, as the attribute or as a user types it (`plain` too); null if it names none. */
        fun parse(text: String?): NodeType? {
            val value = text?.trim()?.lowercase().orEmpty()
            val language = value.removePrefix("${Kind.Code.id}-")
            return when {
                value == Kind.Plain.id -> Plain
                value == Kind.Markdown.id -> Markdown
                value == Kind.Code.id -> NodeType(Kind.Code)
                value.startsWith("${Kind.Code.id}-") && language.isNotEmpty() && language.all { it.isLetterOrDigit() || it in "_+#.-" } -> NodeType(Kind.Code, language)
                else -> null
            }
        }

        fun of(node: Node): NodeType = parse(node.attributes.other[ATTRIBUTE]) ?: Plain
    }
}

/**
 * Give the nodes of [before] the type [type]; [before] is what their
 * `content-type` attribute held, for undo. The text is not touched.
 */
class SetNodeTypeCommand(
    private val graph: Graph,
    private val before: Map<Node, String?>,
    private val type: NodeType,
) : Command {
    override fun redo() = before.keys.forEach { set(it, type.attribute) }

    override fun undo() = before.forEach { (node, value) -> set(node, value) }

    private fun set(node: Node, value: String?) {
        if (value == null) node.attributes.other.remove(NodeType.ATTRIBUTE) else node.attributes.other[NodeType.ATTRIBUTE] = value
        graph.needsRecomputing(node)
    }
}
