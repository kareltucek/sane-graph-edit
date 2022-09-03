package ui

import graph_tools.Node
import utils.Constants
import java.awt.Color

data class NodeStyle(
    val background: Color? = null,
    val foreground: Color? = null,
    val nodeScale: Double? = null,
) {
    companion object {
        fun fromNode(n: Node): NodeStyle {
            return NodeStyle(
                background = n.attributes.bg,
                foreground = n.attributes.fg,
                nodeScale = n.attributes.nodeScale
            )
        }
    }
}

data class ColorStyle(
    val background: Color? = null,
    val foreground: Color? = null,
    val label: String = "😊",
) {

    companion object {
        fun fromHue(h: Double, s: Double = 0.2, v: Double = 1.0, label: String = "😊") = ColorStyle(
            background = HSV(h, s, v).toColor(),
            foreground = HSV(h, s * 2, ((1 - v) * 2).coerceIn(0.0, 0.5)).toColor(),
            label = label
        )

    }

    object PickerSet {
        val hues = listOf(
            0.0, //red
            40, //orange
            60, //yellow
            100, //green
            190, //blue
            230, //dark blue
            280, //purple
            320, //pink
        ).map { it.toDouble() / 360.0 * 3.0 }

        val valuesAndSaturations = listOf(
            (0.1 to 1.00) to (0.3 to 0.5),
            (0.2 to 1.00) to (0.2 to 0.5),
            (0.25 to 1.00) to (0.2 to 0.5),
            (0.3 to 1.00) to (0.3 to 0.5),
            (0.4 to 0.95) to (0.4 to 0.5),
            (0.45 to 0.90) to (0.45 to 0.5),
        )

        val saturationHue = listOf(
            0.20 to 0.0, //red
            0.35 to 40.0, //orange
            0.30 to 60.0, //yellow
            0.25 to 100.0, //green
            0.20 to 190.0, //blue
            0.15 to 230.0, //dark blue
            0.20 to 280.0, //purple
            0.20 to 320.0, //pink
        ).map { it.first to it.second/360.0*3.0}

        val saturationValueMultipliers = listOf(
            Triple(0.5, 1.0, 0.4),
            Triple(1.0, 1.0, 0.3),
            Triple(1.2, 0.9, 0.2)
        )

        val colorStyles = listOfNotNull(
            (0 .. (saturationHue.size - 1)).map {
                ColorStyle(
                    background = null,
                    foreground = null,
                )
            },
            saturationValueMultipliers.flatMap { (sm, bgv, fgv) ->
                saturationHue.map { (s, h) ->
                    ColorStyle(
                        background = HSV(h, s * sm, bgv).toColor(),
                        foreground = HSV(h, s * sm, fgv).toColor(),
                    )
                }
            },
            valuesAndSaturations.flatMap { (bg, fg) ->
                hues.map { h ->
                    ColorStyle(
                        background = HSV(h, bg.first, bg.second).toColor(),
                        foreground = HSV(h, fg.first, fg.second).toColor(),
                    )
                }
            }.takeIf { Constants.wantColorSampler },
        ).flatten()
    }
}