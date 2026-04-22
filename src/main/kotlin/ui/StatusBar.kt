package ui

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * One-line status strip at the bottom of a [GraphView]. Shows the
 * active modal-transform mode (`-- SCALE --`, `-- GRAB --`, …)
 * with any axis-lock suffix, and mirrors macro-recording state
 * from [KeyMapper.isRecording].
 *
 * The bar is always visible so layout stays stable across mode
 * changes — it's a one-character-tall slate in Normal mode, and
 * gains text when a gesture or recording is live.
 *
 * State changes reach us through two paths: [KeyMapper.modeListener]
 * fires on Normal ↔ Transform transitions, and the axis-lock
 * command wrappers call [GraphView.refreshStatus] directly. The
 * bar itself is passive — it just renders whatever string it was
 * last handed by [setText].
 */
class StatusBar(
    private val graphView: GraphView,
) : JLabel(" ", SwingConstants.LEFT) {

    init {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        background = Color(0xEFEFEF)
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
            BorderFactory.createEmptyBorder(2, 6, 2, 6),
        )
        preferredSize = Dimension(100, HEIGHT_PX)
        // Dock to the bottom edge of the GraphView. Width tracks
        // the containing view; see relayout() below.
        isVisible = true
    }

    /**
     * Refresh the bar's text and re-anchor it to the bottom of
     * the parent [GraphView]. Called when the view is resized
     * and when mode/lock/recording state changes.
     */
    fun relayout(text: String) {
        this.text = text.ifEmpty { " " }  // preserve height when empty
        val h = HEIGHT_PX
        val w = graphView.width
        graphView.placeMeAt(
            this,
            utils.Vector2(0.0, (graphView.height - h).toDouble()),
            utils.Vector2(w.toDouble(), graphView.height.toDouble()),
        )
        graphView.revalidate()
        graphView.repaint()
    }

    companion object {
        /**
         * Visible height of the bar in pixels. Fixed so the
         * layout doesn't jump as the text changes.
         */
        const val HEIGHT_PX: Int = 20
    }
}
