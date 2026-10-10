package client

import graph_tools.ViewModes
import utils.Constants
import utils.Vector2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A point in space: of the graph (a position and a depth), or as a view sees it (across, down, away from the viewer). */
data class Point3(val x: Double, val y: Double, val z: Double) {
    val xy: Vector2 get() = Vector2(x, y)

    constructor(xy: Vector2, z: Double) : this(xy.x, xy.y, z)
}

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

    /**
     * How many times its size in the plane something at depth [z]
     * appears; null for what is behind the viewer or too near to
     * show. In 2D mode there is no perspective: everything is shown
     * as if in the plane, whatever its depth.
     */
    fun perspective(z: Double): Double? {
        // depth grows away from the viewer
        val away = distance + (z - depth)
        return when {
            !ViewModes.threeD || z == depth -> 1.0
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
        // in 2D mode there are no planes to speak of
        if (p != null && ViewModes.threeD) {
            offset = middle + (offset - middle) * p
            scale *= p
            depth = z
        }
        return p != null
    }

    /**
     * How the view is turned, in radians: [yaw] around the vertical
     * axis, then [pitch] around the horizontal one. Everything else
     * here speaks of the graph as seen so turned ([toView]): the
     * plane the view is of is the one at [depth] from that side.
     * Only in 3D mode ([ViewModes.threeD]); in 2D the view is from
     * the front whatever these say.
     */
    var yaw: Double = 0.0
        private set
    var pitch: Double = 0.0
        private set

    val isTurned: Boolean get() = ViewModes.threeD && (yaw != 0.0 || pitch != 0.0)

    /** A point of the graph (position and depth) as the view sees it: across, down and away from the viewer. */
    fun toView(p: Point3): Point3 =
        if (!isTurned) p
        else {
            val x = p.x * cos(yaw) + p.z * sin(yaw)
            val z = -p.x * sin(yaw) + p.z * cos(yaw)
            Point3(x, p.y * cos(pitch) - z * sin(pitch), p.y * sin(pitch) + z * cos(pitch))
        }

    /** The point of the graph that the view sees at [p]: [toView] backwards. */
    fun toWorld(p: Point3): Point3 =
        if (!isTurned) p
        else {
            val y = p.y * cos(pitch) + p.z * sin(pitch)
            val z = -p.y * sin(pitch) + p.z * cos(pitch)
            Point3(p.x * cos(yaw) - z * sin(yaw), y, p.x * sin(yaw) + z * cos(yaw))
        }

    /** Turn the view by [byYaw] and [byPitch] around what is in the [middle] of the screen, in the plane the view is of: that stays where it is. */
    fun orbit(byYaw: Double, byPitch: Double, middle: Vector2) =
        turnTo(yaw + byYaw, (pitch + byPitch).coerceIn(-PI / 2, PI / 2), middle)

    /** Turn the view to [yaw] and [pitch], around the same point as [orbit]. */
    fun turnTo(yaw: Double, pitch: Double, middle: Vector2) {
        val at = toWorkspace(middle)
        val pivot = toWorld(Point3(at.x, at.y, depth))
        this.yaw = yaw
        this.pitch = pitch
        val seen = toView(pivot)
        depth = seen.z
        offset = middle - seen.xy * scale
    }

    /**
     * As the six numbers of an affine matrix (`m00 m10 m01 m11 m02
     * m12`): the form kept in session files. After them the [depth],
     * when the view is not of the plane at 0 from the front, and
     * then [yaw] and [pitch], when it is turned.
     */
    var matrix: List<Double>
        get() {
            val turned = yaw != 0.0 || pitch != 0.0
            return listOf(scale, 0.0, 0.0, scale, offset.x, offset.y) +
                (if (depth != 0.0 || turned) listOf(depth) else emptyList()) +
                (if (turned) listOf(yaw, pitch) else emptyList())
        }
        set(m) {
            scale = m[0]
            offset = Vector2(m[4], m[5])
            depth = m.getOrNull(6) ?: 0.0
            yaw = m.getOrNull(7) ?: 0.0
            pitch = m.getOrNull(8) ?: 0.0
        }
}
