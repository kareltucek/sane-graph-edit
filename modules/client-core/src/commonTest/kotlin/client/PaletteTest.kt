@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import client.ColorStyle.PickerSet
import utils.Rgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The picker's palette in a light and in a dark theme. Files hold the light one. */
class PaletteTest {
    /** Brightness as the eye has it, 0 to 255. */
    private fun luma(c: Rgb) = 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue

    private val coloured = PickerSet.colorStyles.indices.filter { PickerSet.colorStyles[it].background != null }

    @Test
    fun `the light palette is what files have always held`() {
        // first and last coloured entry: the palest red and the darkest pink
        assertEquals(Rgb(255, 229, 229), PickerSet.colorStyles[8].background)
        assertEquals(Rgb(102, 91, 91), PickerSet.colorStyles[8].foreground)
        assertEquals(Rgb(204, 155, 187), PickerSet.colorStyles.last().background)
        assertEquals(Rgb(25, 19, 23), PickerSet.colorStyles.last().foreground)
    }

    @Test
    fun `every entry has a dark counterpart, dark with light text`() {
        assertEquals(PickerSet.colorStyles.size, PickerSet.darkColorStyles.size)
        coloured.forEach { i ->
            val dark = PickerSet.darkColorStyles[i]
            assertTrue(luma(dark.background!!) < 128, "background of $i")
            assertTrue(luma(dark.foreground!!) > 128, "text of $i")
        }
    }

    @Test
    fun `the dark rows step from black towards lighter, where the light ones step from white towards darker`() {
        val columns = PickerSet.saturationHue.size
        coloured.filter { it + columns in coloured }.forEach { i ->
            assertTrue(luma(PickerSet.darkColorStyles[i + columns].background!!) > luma(PickerSet.darkColorStyles[i].background!!), "dark $i")
            assertTrue(luma(PickerSet.colorStyles[i + columns].background!!) < luma(PickerSet.colorStyles[i].background!!), "light $i")
        }
    }

    @Test
    fun `a pair from the palette is shown as its dark counterpart in a dark theme only`() {
        val light = PickerSet.colorStyles[coloured.first()]
        val dark = PickerSet.darkColorStyles[coloured.first()]
        assertEquals(dark.background to dark.foreground, PickerSet.shown(light.background, light.foreground, dark = true))
        assertEquals(light.background to light.foreground, PickerSet.shown(light.background, light.foreground, dark = false))
        assertTrue(PickerSet.isOurs(light.background, light.foreground))
    }

    @Test
    fun `other colours, and none, are shown as they are`() {
        val red = Rgb(255, 0, 0)
        assertEquals(red to Rgb.Black, PickerSet.shown(red, Rgb.Black, dark = true))
        assertEquals(null to null, PickerSet.shown(null, null, dark = true))
        // the background alone of a palette entry is not the entry
        val light = PickerSet.colorStyles[coloured.first()]
        assertEquals(light.background to Rgb.Black, PickerSet.shown(light.background, Rgb.Black, dark = true))
        assertFalse(PickerSet.isOurs(red, Rgb.Black))
        assertFalse(PickerSet.isOurs(null, null))
    }
}
