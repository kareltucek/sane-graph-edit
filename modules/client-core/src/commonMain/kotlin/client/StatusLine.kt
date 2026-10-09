package client

import kotlin.math.absoluteValue
import kotlin.math.roundToLong

/**
 * The text of a view's status line: the view modes that are on,
 * the transform gesture in progress with its axis lock, the node
 * scale while the style picker is open, macro recording, and the
 * links of the selected node.
 */
object StatusLine {
    fun text(view: EditorView, keys: KeyMapper?, stylePickerOpen: Boolean): String {
        val c = view.gestures
        val gestureName = when (c.state) {
            GestureController.States.Scaling -> "SCALE"
            GestureController.States.MovingNodes -> "GRAB"
            GestureController.States.Rotating -> "ROTATE"
            else -> null
        }
        // the axis the gesture is kept to, as the key that locked it: `x` → "(X)"
        val lockSuffix = when {
            gestureName == null -> ""
            c.lockY && !c.lockX -> " (X)"
            c.lockX && !c.lockY -> " (Y)"
            else -> ""
        }
        val gesture = gestureName?.let { "-- TRANSFORM: $it$lockSuffix --" } ?: ""
        val modes = listOfNotNull(
            "[unhide]".takeIf { graph_tools.ViewModes.unhide },
            "[show-parents]".takeIf { graph_tools.ViewModes.showParents },
            "[show-children]".takeIf { graph_tools.ViewModes.showChildren },
            "[dynamic]".takeIf { graph_tools.ViewModes.dynamic },
            "[discard]".takeIf { graph_tools.ViewModes.discard },
            "[3d]".takeIf { graph_tools.ViewModes.threeD },
            // somebody else is attached to this document too
            "[shared]".takeIf { (view.content.document?.info?.clients ?: 0) > 1 },
        ).joinToString(" ")
        val scale = if (stylePickerOpen) scaleLabel(view) else ""
        val recording = if (keys?.isRecording == true) "recording" else ""
        return listOf(modes, gesture, scale, recording, links(view, keys)).filter { it.isNotEmpty() }.joinToString("    ")
    }

    /**
     * Links of the single selected node as the keys that follow
     * them (`links: gd gf`); a link whose `g<letter>` is mapped to
     * something else is shown by name (`link-t (gt taken)`).
     */
    private fun links(view: EditorView, keys: KeyMapper?): String {
        val letters = view.g.selectedNodes.singleOrNull()?.let { FileLink.letters(it) }.orEmpty()
        val shown = letters.map { l -> if (keys?.followsLink(l) != false) "g$l" else "${FileLink.attributeName(l)} (g$l taken)" }
        return if (shown.isEmpty()) "" else "links: ${shown.joinToString(" ")}"
    }

    /** The selection's node scale (logarithmic, none is 0): one value, or the range over several nodes. */
    private fun scaleLabel(view: EditorView): String {
        val scales = view.g.selectedNodes.map { it.attributes.nodeScale ?: 0.0 }
        val range = when {
            scales.isEmpty() -> "(no selection)"
            scales.min() == scales.max() -> twoDecimals(scales.min())
            else -> "${twoDecimals(scales.min())}–${twoDecimals(scales.max())}"
        }
        val count = if (scales.size > 1) " (${scales.size} nodes)" else ""
        return "node scale: $range$count"
    }

    internal fun twoDecimals(value: Double): String {
        val hundredths = (value * 100).roundToLong()
        val sign = if (hundredths < 0) "-" else ""
        val abs = hundredths.absoluteValue
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}
