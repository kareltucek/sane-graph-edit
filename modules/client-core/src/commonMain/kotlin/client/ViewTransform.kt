package client

import utils.Vector2

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

    /** As the six numbers of an affine matrix (`m00 m10 m01 m11 m02 m12`): the form kept in session files. */
    var matrix: List<Double>
        get() = listOf(scale, 0.0, 0.0, scale, offset.x, offset.y)
        set(m) {
            scale = m[0]
            offset = Vector2(m[4], m[5])
        }
}
