import java.awt.geom.Rectangle2D

data class Vector2(val x: Float, val y: Float) {
    constructor(x: Int, y: Int) : this(x.toFloat(), y.toFloat())

    operator fun plus(b: Vector2) = Vector2(x + b.x, y + b.y)
    operator fun minus(b: Vector2) = Vector2(x - b.x, y - b.y)
    operator fun times(b: Vector2) = Vector2(x * b.x, y * b.y)
    operator fun times(b: Float) = Vector2(x * b, y * b)
    operator fun div(b: Float) = Vector2(x / b, y / b)
    operator fun times(b: Int) = Vector2(x * b, y * b)
    operator fun div(b: Int) = Vector2(x / b, y / b)

    fun distanceSquare() = x * x + y * y
    fun lt(b: Vector2) = x < b.x && y < b.y

    fun toUnit(): Vector2 {
        val len = Math.sqrt((x * x + y * y).toDouble()).toFloat()
        return this / len
    }

    companion object {
        fun Rectangle2D.toVector2() = Vector2(this.width.toFloat(), this.height.toFloat())

        val Zero = Vector2(0.0f, 0.0f)

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