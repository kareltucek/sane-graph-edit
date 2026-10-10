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
 * The text may be kept in a file instead of in the node ([source]).
 */
data class NodeType(
    val kind: Kind,
    val language: String? = null,
    /**
     * Where the text is kept, when not in the node: a file, lines of
     * one or a section of one, written as a link is (`src/A.kt:10-20`,
     * `README.md#Depth`). The node's own text is then a copy of it.
     * In the attribute it follows the type: `code-kotlin:src/A.kt:10-20`.
     */
    val source: String? = null,
) {
    enum class Kind(val id: String) {
        Plain("plain"),
        Code("code"),
        Markdown("markdown"),
    }

    /** As written in a file; null for a plain node that keeps its own text, which has no attribute. */
    val attribute: String?
        get() {
            val type = when (kind) {
                Kind.Plain -> Kind.Plain.id
                Kind.Code -> language?.let { "${Kind.Code.id}-$it" } ?: Kind.Code.id
                Kind.Markdown -> Kind.Markdown.id
            }
            return if (source != null) "$type:$source" else type.takeIf { kind != Kind.Plain }
        }

    companion object {
        /** The DOT attribute. */
        const val ATTRIBUTE = "content-type"

        val Plain = NodeType(Kind.Plain)
        val Markdown = NodeType(Kind.Markdown)

        /** The type [text] names, as the attribute or as a user types it (`plain` too); null if it names none. */
        fun parse(text: String?): NodeType? {
            val whole = text?.trim().orEmpty()
            val value = whole.substringBefore(':').trim().lowercase()
            val source = whole.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
            val language = value.removePrefix("${Kind.Code.id}-")
            return when {
                value == Kind.Plain.id -> NodeType(Kind.Plain, null, source)
                value == Kind.Markdown.id -> NodeType(Kind.Markdown, null, source)
                value == Kind.Code.id -> NodeType(Kind.Code, null, source)
                value.startsWith("${Kind.Code.id}-") && language.isNotEmpty() && language.all { it.isLetterOrDigit() || it in "_+#.-" } ->
                    NodeType(Kind.Code, language, source)
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
