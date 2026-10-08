package ui

import client.KeyNotation
import client.Scheduler
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import javax.swing.Timer

/** AWT key events in the client's [KeyNotation]. */
object AwtKeys {
    /**
     * Convert a `keyPressed` event to notation. Returns null for
     * events that should be handled by [KeyNotation.fromKeyTyped]
     * instead (plain characters without Ctrl).
     */
    fun fromKeyPressed(e: KeyEvent): String? {
        val character = when (e.keyCode) {
            in VK_A..VK_Z -> 'a' + (e.keyCode - VK_A)
            in VK_0..VK_9 -> '0' + (e.keyCode - VK_0)
            else -> null
        }
        return KeyNotation.fromKeyPressed(specialKeyName(e.keyCode), character, e.isControlDown, e.isShiftDown)
    }

    private fun specialKeyName(keyCode: Int): String? = when (keyCode) {
        VK_ESCAPE -> "Esc"
        VK_ENTER -> "CR"
        VK_SPACE -> "Space"
        VK_TAB -> "Tab"
        VK_PAGE_DOWN -> "PgDn"
        VK_PAGE_UP -> "PgUp"
        VK_UP -> "Up"
        VK_DOWN -> "Down"
        VK_LEFT -> "Left"
        VK_RIGHT -> "Right"
        VK_BACK_SPACE -> "BS"
        VK_DELETE -> "Del"
        in VK_F1..VK_F12 -> "F${keyCode - VK_F1 + 1}"
        else -> null
    }
}

/** Runs delayed actions on the Swing event thread. */
object SwingScheduler : Scheduler {
    override fun after(ms: Int, action: () -> Unit): () -> Unit {
        val timer = Timer(ms) { action() }
        timer.isRepeats = false
        timer.start()
        return { timer.stop() }
    }
}
