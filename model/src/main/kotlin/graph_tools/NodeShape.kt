package graph_tools

import utils.Vector2
import kotlin.math.absoluteValue

enum class NodeShape(val id: String, val impl: NodeShapeImpl) {
    Oval("oval", OvalShape.singleton),
    Rectangle("box", RectangleShape.singleton);

    companion object {
        val defaultShape = Rectangle

        fun fromString(s: String?): NodeShape? = NodeShape.values().find { it.id == s }
    }
}

/** Geometry of a node shape. Painting it is the renderer's job. */
interface NodeShapeImpl {
    fun connectionPoint(dir: Vector2, n: Node, offsetBy: Double = 0.0): Vector2
    fun computeShapeBounds(textBounds: Vector2): Vector2
}

class OvalShape : NodeShapeImpl {
    //round
    val shapeSizeCf = 1.3
    val shapeSizeMargin = 20.0
    override fun connectionPoint(dir: Vector2, n: Node, offsetBy: Double): Vector2 {
        val bounds = n.cache.shapeBounds / 2
        val flippedBounds = Vector2(bounds.y, bounds.x)
        val dir2 = (dir * flippedBounds).toUnit()
        val max = Math.max(bounds.x, bounds.y)
        val sideProportions = Vector2(bounds.x / max, bounds.y / max)
        val connectionPoint = dir2 * sideProportions * max

        val offsetDir = (dir2 * flippedBounds * flippedBounds).toUnit() * offsetBy

        return connectionPoint + offsetDir
    }

    override fun computeShapeBounds(textBounds: Vector2): Vector2 {

        return textBounds * shapeSizeCf + Vector2(shapeSizeMargin, shapeSizeMargin) * 2
    }

    companion object {
        val singleton = OvalShape()
    }
}

class RectangleShape : NodeShapeImpl {
    //rect
    val shapeSizeCf = 1.2
    val shapeSizeMargin = 5.0
    val cornerRadius = 5
    override fun connectionPoint(dir: Vector2, n: Node, offsetBy: Double): Vector2 {
        val bounds = n.cache.shapeBounds / 2
        val unitDir = dir.toUnit()
        val candidate1 = ((bounds.x + offsetBy) / unitDir.dot(Vector2(1, 0))).absoluteValue
        val candidate2 = ((bounds.y + offsetBy) / unitDir.dot(Vector2(0, 1))).absoluteValue
        val len = listOf(candidate1, candidate2).min()

        val candidate3 = dir.toScale(len)

        val cornerPos = (bounds - Vector2(cornerRadius, cornerRadius)).directBy(dir)

        return if (candidate3.toAbsolute().gt(cornerPos.toAbsolute())) {
            // offsetBy actually stands for arrowhead radius
            val desiredCornerLength = cornerRadius + offsetBy
            val cornerVector = candidate3 - cornerPos
            val correctionLen = cornerVector.length() - desiredCornerLength
            candidate3 - cornerVector.toScale(correctionLen)
        } else {
            candidate3
        }
    }

    override fun computeShapeBounds(textBounds: Vector2): Vector2 {
        return textBounds * shapeSizeCf + Vector2(shapeSizeMargin, shapeSizeMargin) * 2
    }

    companion object {
        val singleton = RectangleShape()
    }
}
