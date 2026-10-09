package client

import utils.Constants
import utils.Rgb

data class ColorStyle(
    val background: Rgb? = null,
    val foreground: Rgb? = null,
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
            /* saturation, background value, foreground value */
            Triple(0.5, 1.0, 0.4),
            Triple(1.0, 1.0, 0.3),
            Triple(1.2, 0.9, 0.2),
            Triple(1.2, 0.8, 0.1),
        )

        /**
         * The same rows for a dark theme: the same hues, stepping
         * from black towards lighter shades where the light rows
         * step from white towards darker ones.
         */
        val darkSaturationValueMultipliers = listOf(
            /* background saturation, background value, foreground value */
            Triple(2.0, 0.20, 0.75),
            Triple(2.0, 0.28, 0.82),
            Triple(1.8, 0.36, 0.90),
            Triple(1.6, 0.44, 0.97),
        )

        /** Light text on the dark rows is this much less saturated than the hue's own saturation. */
        const val darkForegroundSaturation = 0.5

        /** What is offered, row by row, and what files hold: the palette of a light theme. */
        val colorStyles = styles(saturationValueMultipliers) { sm -> sm }

        /** [colorStyles] as a dark theme shows them, entry for entry. */
        val darkColorStyles = styles(darkSaturationValueMultipliers) { darkForegroundSaturation }

        /**
         * The colours to show a node in. Files always hold the
         * light palette: a pair of colours from it is shown as
         * its dark counterpart when [dark]; any other colours
         * are shown as they are.
         */
        fun shown(background: Rgb?, foreground: Rgb?, dark: Boolean): Pair<Rgb?, Rgb?> {
            val index = if (dark && (background != null || foreground != null)) entryOf(background, foreground) else -1
            return if (index >= 0) darkColorStyles[index].let { it.background to it.foreground } else background to foreground
        }

        /** True when the pair of colours is one of the palette's (not the entry without colours). */
        fun isOurs(background: Rgb?, foreground: Rgb?): Boolean = (background != null || foreground != null) && entryOf(background, foreground) >= 0

        private fun entryOf(background: Rgb?, foreground: Rgb?): Int = colorStyles.indexOfFirst { it.background == background && it.foreground == foreground }

        /** [foregroundSaturation]: the multiplier for the text's saturation, from the row's for the background. */
        private fun styles(multipliers: List<Triple<Double, Double, Double>>, foregroundSaturation: (Double) -> Double) = listOfNotNull(
            (0 .. (saturationHue.size - 1)).map {
                ColorStyle(
                    background = null,
                    foreground = null,
                )
            },
            multipliers.flatMap { (sm, bgv, fgv) ->
                saturationHue.map { (s, h) ->
                    ColorStyle(
                        background = HSV(h, (s * sm).coerceAtMost(1.0), bgv).toColor(),
                        foreground = HSV(h, s * foregroundSaturation(sm), fgv).toColor(),
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