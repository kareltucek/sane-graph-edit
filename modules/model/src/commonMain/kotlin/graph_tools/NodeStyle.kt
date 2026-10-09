package graph_tools

import utils.Rgb

data class NodeStyle(
    val background: Rgb? = null,
    val foreground: Rgb? = null,
    val nodeScale: Double? = null,
) {
    companion object {
        fun fromNode(n: Node): NodeStyle {
            return NodeStyle(
                background = n.attributes.bg,
                foreground = n.attributes.fg,
                nodeScale = n.attributes.nodeScale,
            )
        }
    }
}
