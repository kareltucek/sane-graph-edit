package utils

import java.awt.Color
import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D

/** The AWT-bound helpers: event positions, colours. */
object AwtUtils {
    fun MouseEvent.toScreenspaceVector(): Vector2 = Vector2(this.x.toDouble(), this.y.toDouble())

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
