package utils

/** A colour, channels 0..255. The model's own type so that it needs no GUI toolkit. */
data class Rgb(val red: Int, val green: Int, val blue: Int) {
    /** From `0xRRGGBB`. */
    constructor(rgb: Int) : this((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    companion object {
        val White = Rgb(255, 255, 255)
        val Black = Rgb(0, 0, 0)
    }
}
