package utils

import graph_tools.Plotter
import java.awt.Color
import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

/** The AWT-bound helpers: screen ↔ workspace conversion, colours. */
object AwtUtils {
    fun MouseEvent.toScreenspaceVector(): Vector2 = Vector2(this.x.toDouble(), this.y.toDouble())
    fun MouseEvent.toWorkspaceVector(): Vector2 {
        var pt = Point2D.Double(this.x.toDouble() + Constants.frameMargin, this.y.toDouble() + Constants.frameMargin)
        var res = Plotter.t.inverseTransform(pt, null)
        return Vector2(res.x.toDouble(), res.y.toDouble())
    }

    fun Vector2.toWorkspaceVector(): Vector2 {
        var pt = Point2D.Double(
            this.x.toDouble() + Constants.frameMargin,
            this.y.toDouble() + Constants.frameMargin
        )
        var res = Plotter.t.inverseTransform(pt, null)
        return Vector2(res.x.toDouble(), res.y.toDouble())
    }

    fun Vector2.toScreenVector(): Vector2 {
        var pt = Point2D.Double(this.x.toDouble(), this.y.toDouble())
        var res = Plotter.t.transform(pt, null)
        return Vector2(res.x.toDouble() - Constants.frameMargin, res.y.toDouble() - Constants.frameMargin)
    }

    fun Vector2.workspaceSizeTransform(): Vector2 {
        return this * Plotter.t.scaleX.toDouble()
    }

    fun Rectangle2D.toVector2() = Vector2(this.width.toDouble(), this.height.toDouble())

    fun Rgb.toAwt(): Color = Color(red, green, blue)
    fun Color.toRgb(): Rgb = Rgb(red, green, blue)

    val identity = AffineTransform()
    fun <R> Graphics2D.withIdentityTransform(f: () -> R): R {
        val oldTransform = this.transform
        this.transform = identity
        val res = f()
        this.transform = oldTransform
        return res
    }
}
