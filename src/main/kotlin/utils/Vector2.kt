package utils

import ui.Utils.fold
import ui.Utils.letIf
import java.awt.geom.Rectangle2D
import kotlin.math.sign

data class Vector2(val x: Double, val y: Double) {
    constructor(x: Int, y: Int) : this(x.toDouble(), y.toDouble())

    operator fun unaryMinus() = Vector2(-x, -y)
    operator fun unaryPlus() = this
    operator fun plus(b: Vector2) = Vector2(x + b.x, y + b.y)
    operator fun minus(b: Vector2) = Vector2(x - b.x, y - b.y)
    operator fun times(b: Vector2) = Vector2(x * b.x, y * b.y)
    operator fun div(b: Vector2) = Vector2(x / b.x, y / b.y)
    operator fun times(b: Double) = Vector2(x * b, y * b)
    operator fun div(b: Double) = Vector2(x / b, y / b)
    operator fun times(b: Int) = Vector2(x * b, y * b)
    operator fun div(b: Int) = Vector2(x / b, y / b)

    fun lengthSquared() = x * x + y * y

    fun length() = Math.sqrt((x * x + y * y).toDouble()).toDouble()

    fun lt(b: Vector2) = x < b.x && y < b.y
    fun gt(b: Vector2) = x > b.x && y > b.y

    fun toUnit(): Vector2 {
        val len = Math.sqrt((x * x + y * y).toDouble()).toDouble()
        return this.letIf(len != 0.0) { it / len }
    }

    fun toAbsolute() = Vector2(Math.abs(x), Math.abs(y))

    fun toScale(d: Double) = (this * (d / this.length()))
    fun dot(b: Vector2) = x * b.x + y * b.y
    fun directBy(dir: Vector2): Vector2 {
        return Vector2(
            (x.sign == dir.x.sign).fold(-x, x),
            (y.sign == dir.y.sign).fold(-y, y),
        )
    }

    fun coerceLength(minOptimizeMove: Double, maxOptimizerMove: Double): Vector2 {
        val len = this.length()
        return when {
            minOptimizeMove <= len && len <= maxOptimizerMove -> this
            else -> this * len.coerceIn(minOptimizeMove, maxOptimizerMove) / len
        }
    }

    companion object {
        fun Rectangle2D.toVector2() = Vector2(this.width.toDouble(), this.height.toDouble())

        val Zero = Vector2(0.0, 0.0)

        fun computeCorners(a: Vector2, b: Vector2): Pair<Vector2, Vector2> {
            val ul = Vector2(
                Math.min(a.x, b.x),
                Math.min(a.y, b.y)
            )
            val br = Vector2(
                Math.max(a.x, b.x),
                Math.max(a.y, b.y)
            )

            return ul to br
        }
    }
}