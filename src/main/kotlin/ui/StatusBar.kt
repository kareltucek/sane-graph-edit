package ui

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.SpringLayout
import javax.swing.SwingConstants

/**
 * One-line status strip docked to the bottom edge of a
 * [GraphView]. Surfaces the active modal-transform mode
 * (`-- TRANSFORM: SCALE (X) --`, `-- TRANSFORM: GRAB --`, …) and
 * macro-recording state.
 *
 * The bar is always present — visibility doesn't shift between
 * Normal and Transform, which keeps the layout stable. In Normal
 * mode it shows a blank line (the width / height stay the same);
 * entering Transform mode writes the label.
 *
 * State changes reach us through two paths: [KeyMapper.modeListener]
 * fires on Normal ↔ Transform transitions, and the axis-lock
 * command wrappers call [GraphView.refreshStatus] directly. The
 * bar itself is passive — it renders whatever string it was last
 * handed by [setLabel].
 */
class StatusBar(
    private val graphView: GraphView,
) : JLabel(" ", SwingConstants.LEFT) {

    init {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        background = Color(0xEFEFEF)
        foreground = Color(0x1A1A1A)
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
            BorderFactory.createEmptyBorder(2, 6, 2, 6),
        )
        preferredSize = Dimension(100, HEIGHT_PX)
        isVisible = true
    }

    /**
     * Pin the bar to the bottom edge of [graphView] via the
     * view's [SpringLayout], so it auto-tracks window resizes.
     * Call once, after the bar has been added as a child of the
     * view (SpringLayout constraints require the components to
     * be in the same container).
     */
    fun installConstraints() {
        val sl = graphView.springLayout
        sl.putConstraint(SpringLayout.WEST, this, 0, SpringLayout.WEST, graphView)
        sl.putConstraint(SpringLayout.EAST, this, 0, SpringLayout.EAST, graphView)
        sl.putConstraint(SpringLayout.SOUTH, this, 0, SpringLayout.SOUTH, graphView)
        sl.putConstraint(SpringLayout.NORTH, this, -HEIGHT_PX, SpringLayout.SOUTH, graphView)
    }

    /**
     * Update the visible text. Empty string falls back to a
     * single space so the label still paints its background and
     * keeps its height.
     */
    fun setLabel(text: String) {
        this.text = text.ifEmpty { " " }
        repaint()
    }

    companion object {
        /** Visible height of the bar in pixels. */
        const val HEIGHT_PX: Int = 22
    }
}
