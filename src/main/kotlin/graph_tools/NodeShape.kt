package graph_tools

import Node
import ui.Utils.orElse
import utils.Vector2
import java.awt.Color
import java.awt.Graphics2D
import kotlin.math.absoluteValue

enum class NodeShape(val id: String, val impl: NodeShapeImpl) {
    Oval("oval", OvalShape.singleton),
    Rectangle("rectangle", RectangleShape.singleton);
}

interface NodeShapeImpl {
    fun connectionPoint(dir: Vector2, n: Node, offsetBy: Double = 0.0): Vector2
    fun computeShapeBounds(textBounds: Vector2): Vector2
    fun paint(g2d: Graphics2D, n: Node)
}

class OvalShape : NodeShapeImpl {
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

        //round
        val shapeSizeCf = 1.3
        val shapeSizeMargin = 20.0
        return textBounds * shapeSizeCf + Vector2(shapeSizeMargin, shapeSizeMargin) * 2
    }

    override fun paint(g2d: Graphics2D, n: Node) {
        val bounds = n.cache
        val shapePos = n.position - bounds.shapeBounds / 2;

        n.attributes.bg?.let { c ->
            g2d.paint = c
            g2d.fillOval(
                shapePos.x.toInt(),
                shapePos.y.toInt(),
                bounds.shapeBounds.x.toInt(),
                bounds.shapeBounds.y.toInt()
            )
        }

        g2d.paint = n.attributes.fg.orElse(Color.BLACK)
        g2d.drawOval(
            shapePos.x.toInt(),
            shapePos.y.toInt(),
            bounds.shapeBounds.x.toInt(),
            bounds.shapeBounds.y.toInt()
        )
    }

    companion object {
        val singleton = RectangleShape()
    }
}

class RectangleShape : NodeShapeImpl {
    override fun connectionPoint(dir: Vector2, n: Node, offsetBy: Double): Vector2 {
        val bounds = n.cache.shapeBounds / 2
        val unitDir = dir.toUnit()
        val candidate1 = ((bounds.x + offsetBy) / unitDir.dot(Vector2(1, 0))).absoluteValue
        val candidate2 = ((bounds.y + offsetBy) / unitDir.dot(Vector2(0, 1))).absoluteValue
        val candidate3 = bounds.length()
        val len = Math.min(candidate1, candidate2)

        return dir.toScale(len)
    }

    override fun computeShapeBounds(textBounds: Vector2): Vector2 {
        //rect
        val shapeSizeCf = 1.2
        val shapeSizeMargin = 5.0
        return textBounds * shapeSizeCf + Vector2(shapeSizeMargin, shapeSizeMargin) * 2
    }

    override fun paint(g2d: Graphics2D, n: Node) {
        val bounds = n.cache
        val shapePos = n.position - bounds.shapeBounds / 2;

        g2d.paint = n.attributes.bg.orElse(Color.WHITE)
        g2d.fillRoundRect(
            shapePos.x.toInt(),
            shapePos.y.toInt(),
            bounds.shapeBounds.x.toInt(),
            bounds.shapeBounds.y.toInt(),
            5,
            5,
        )

        g2d.paint = n.attributes.fg.orElse(Color.BLACK)
        g2d.drawRoundRect(
            shapePos.x.toInt(),
            shapePos.y.toInt(),
            bounds.shapeBounds.x.toInt(),
            bounds.shapeBounds.y.toInt(),
            5,
            5,
        )
    }

    companion object {
        val singleton = RectangleShape()
    }
}