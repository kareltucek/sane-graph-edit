package graph_tools

import utils.Vector2

class Edge(
    val src: Node,
    val dst: Node,
    val cache: EdgeCache = EdgeCache(),
    val attributes: EdgeAttributes = EdgeAttributes(),
) {
    override fun toString(): String {
        return "Edge(...${src.attributes.text.take(4)} -> ${dst.attributes.text.take(4)}...)"
    }

    class EdgeCache(
        var srcPt: Vector2 = Vector2.Zero,
        var dstPt: Vector2 = Vector2.Zero,
    )

    class EdgeAttributes(
        var name: String = "",
        var other: MutableMap<String, String> = mutableMapOf(),
    )

    constructor(src: Node, dst: Node, name: String) : this(
        src = src,
        dst = dst,
        attributes = EdgeAttributes(name = name)
    )

    /*
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Edge
        return src == other.src && dst == other.dst && id == other.id
    }

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + id.hashCode()
        result = 31 * result + src.hashCode()
        result = 31 * result + dst.hashCode()
        return result
    }
     */

    fun applyAttribute(l: String, r: String) {
        when (l) {
            else -> attributes.other[l] = r
        }
    }

    fun retrieveAttributes(): Map<String, String> {
        return attributes.other
    }

    companion object {
    }
}