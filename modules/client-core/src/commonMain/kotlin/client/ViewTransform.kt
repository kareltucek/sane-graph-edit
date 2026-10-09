package client

import utils.Constants
import utils.Vector2

/** How a node appears in a view: where its middle seems to be, in workspace units of the plane the view speaks of, and how many times its size. */
data class Apparent(val position: Vector2, val scale: Double)

/**
 * Where a view looks: maps workspace coordinates (node positions)
 * to the view's screen coordinates by a zoom and a pan,
 * `screen = workspace * scale + offset`. Per view, never shared.
 */
class ViewTransform {
    var scale: Double = 1.0
        private set
    var offset: Vector2 = Vector2.Zero
        private set

    val isIdentity: Boolean get() = scale == 1.0 && offset == Vector2.Zero

    fun toScreen(workspace: Vector2): Vector2 = workspace * scale + offset

    fun toWorkspace(screen: Vector2): Vector2 = (screen - offset) / scale

    /** Pan by [by], given in workspace units. */
    fun translate(by: Vector2) {
        offset += by * scale
    }

    /** Zoom by [factor] around the workspace origin. */
    fun zoom(factor: Double) {
        scale *= factor
    }

    /** Zoom to [scale] with the workspace origin in the screen's corner. */
    fun setToScale(scale: Double) {
        this.scale = scale
        offset = Vector2.Zero
    }

    /** Look at the plane at depth [z], whatever was shown before. */
    fun setDepth(z: Double) {
        depth = z
    }

    /**
     * The depth ([graph_tools.Depth]) of the plane that [scale] and
     * [offset] speak of. The viewer looks straight at it from
     * [distance] away, from in front of the middle of the screen;
     * what is nearer appears bigger and further from the middle,
     * what is further away smaller and closer to it.
     */
    var depth: Double = 0.0
        private set

    /** How far the viewer is from the plane, in workspace units: zooming in is coming closer. */
    val distance: Double get() = Constants.focalLength / scale

    /** How many times its size in the plane something at depth [z] appears; null for what is behind the viewer or too near to show. */
    fun perspective(z: Double): Double? {
        // depth grows away from the viewer
        val away = distance + (z - depth)
        return when {
            z == depth -> 1.0
            away <= 0.0 || distance / away > Constants.maxPerspective -> null
            else -> distance / away
        }
    }

    /**
     * Where in the plane the point [workspace] at depth [z] appears
     * to be, in workspace units; [middle] is the middle of the
     * view on screen. Null for a point that is not shown.
     */
    fun apparent(workspace: Vector2, z: Double, middle: Vector2): Vector2? =
        perspective(z)?.let { p -> toWorkspace(middle).let { m -> m + (workspace - m) * p } }

    /** Speak of the plane at depth [z] from now on; what the view shows stays as it is. False if that plane is not shown. */
    fun refocus(z: Double, middle: Vector2): Boolean {
        val p = perspective(z)
        if (p != null) {
            offset = middle + (offset - middle) * p
            scale *= p
            depth = z
        }
        return p != null
    }

    /**
     * As the six numbers of an affine matrix (`m00 m10 m01 m11 m02
     * m12`): the form kept in session files. A seventh is the
     * [depth], when the view is not of the plane at 0.
     */
    var matrix: List<Double>
        get() = listOf(scale, 0.0, 0.0, scale, offset.x, offset.y) + listOfNotNull(depth.takeIf { it != 0.0 })
        set(m) {
            scale = m[0]
            offset = Vector2(m[4], m[5])
            depth = m.getOrNull(6) ?: 0.0
        }
}
