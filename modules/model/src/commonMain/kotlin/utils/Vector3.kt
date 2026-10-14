package utils

import kotlin.math.sqrt

/**
 * A point or a way in space: across, down, and away from the viewer
 * (a node's position and its depth; [xy] is the plane the graph is
 * drawn in). A node is drawn flat, facing the viewer, whatever its
 * depth: only where nodes are, and the layout that puts them there,
 * is of three dimensions.
 */
data class Vector3(val x: Double, val y: Double, val z: Double) {
    constructor(xy: Vector2, z: Double) : this(xy.x, xy.y, z)

    val xy: Vector2 get() = Vector2(x, y)

    operator fun unaryMinus() = Vector3(-x, -y, -z)
    operator fun plus(b: Vector3) = Vector3(x + b.x, y + b.y, z + b.z)
    operator fun minus(b: Vector3) = Vector3(x - b.x, y - b.y, z - b.z)
    operator fun times(b: Double) = Vector3(x * b, y * b, z * b)
    operator fun div(b: Double) = Vector3(x / b, y / b, z / b)
    operator fun div(b: Int) = Vector3(x / b, y / b, z / b)

    fun lengthSquared() = x * x + y * y + z * z

    fun length() = sqrt(lengthSquared())

    fun dot(b: Vector3) = x * b.x + y * b.y + z * b.z

    /** Of length 1 the same way; the zero vector stays itself. */
    fun toUnit(): Vector3 = length().let { if (it == 0.0) this else this / it }

    fun toScale(d: Double) = toUnit() * d

    fun min(b: Vector3) = Vector3(kotlin.math.min(x, b.x), kotlin.math.min(y, b.y), kotlin.math.min(z, b.z))
    fun max(b: Vector3) = Vector3(kotlin.math.max(x, b.x), kotlin.math.max(y, b.y), kotlin.math.max(z, b.z))

    companion object {
        val Zero = Vector3(0.0, 0.0, 0.0)
    }
}
