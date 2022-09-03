package ui

import java.awt.Color

/**
 *
 * @author me
 */

data class HSV(
    val h: Double,
    val s: Double,
    val v: Double
) {
    fun toColor(): Color {
        fun ramp(a: Double, b: Double, c: Double, d: Double): Double {
            val h = this.h
            val res = when {
                a <= h && h <= b -> (h - a) / (b - a)
                b <= h && h <= c -> 1.0
                c <= h && h <= d -> 1.0 - (h - c) / (d - c)
                else -> 0.0
            }
            return res
        }

        val maxV = v
        val minV = v - v * s
        val rng = maxV - minV

        val pureValues = listOf(
            ramp(-1.0, -0.5, 0.5, 1.0) + ramp(2.0, 2.5, 3.5, 4.0),
            ramp(0.0, 0.5, 1.5, 2.0),
            ramp(1.0, 1.5, 2.5, 3.0),
        )
        val res = pureValues
            .map { minV + rng * it }
            .map { (it * 255).toInt().coerceIn(0, 255) }
            .let {
                Color(it[0], it[1], it[2])
            }

        return res
    }
}