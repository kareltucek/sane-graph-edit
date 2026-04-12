package ui

import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*

/**
 * Bidirectional conversion between AWT [KeyEvent]s and the
 * vim-style notation strings used in the mapping tables
 * (`"a"`, `"<C-s>"`, `"<CR>"`, `"<Space>"`, etc.).
 */
object KeyNotation {

    /**
     * Convert a `keyTyped` event's character to notation.
     * Returns null for characters that should be ignored (e.g.,
     * control chars produced by Ctrl combos that are handled in
     * keyPressed instead).
     */
    fun fromKeyTyped(c: Char): String? = when {
        c == ' ' -> "<Space>"
        c == '\t' -> "<Tab>"
        c == '\n' || c == '\r' -> "<CR>"
        c == '\u001B' -> "<Esc>"
        c.isISOControl() -> null  // Ctrl combos produce control chars; skip
        else -> c.toString()
    }

    /**
     * Convert a `keyPressed` event to notation. Returns null for
     * events that should be handled by [fromKeyTyped] instead
     * (plain characters without Ctrl).
     */
    fun fromKeyPressed(e: KeyEvent): String? {
        val hasCtrl = e.isControlDown
        val hasShift = e.isShiftDown

        // Special keys handled here regardless of modifiers
        val specialName = specialKeyName(e.keyCode)
        if (specialName != null) {
            return buildNotation(hasCtrl, hasShift, specialName)
        }

        // Plain chars without Ctrl → let keyTyped handle them
        if (!hasCtrl) return null

        // Ctrl+letter / Ctrl+digit
        val keyName = when (e.keyCode) {
            in VK_A..VK_Z -> ('a' + (e.keyCode - VK_A)).toString()
            in VK_0..VK_9 -> ('0' + (e.keyCode - VK_0)).toString()
            else -> return null
        }

        return buildNotation(ctrl = true, shift = hasShift, keyName)
    }

    /**
     * Parse a notation string like `"<C-S-s>"` or `"a"` into its
     * components. Returns null if the string is malformed.
     */
    fun parse(notation: String): ParsedKey? {
        if (!notation.startsWith("<") || !notation.endsWith(">")) {
            // Plain character
            return if (notation.length == 1) {
                ParsedKey(notation, ctrl = false, shift = false)
            } else null
        }

        val inner = notation.removeSurrounding("<", ">")
        val parts = inner.split("-")
        var ctrl = false
        var shift = false
        for (i in 0 until parts.size - 1) {
            when (parts[i]) {
                "C" -> ctrl = true
                "S" -> shift = true
            }
        }
        val keyName = parts.last()
        return ParsedKey(keyName, ctrl, shift)
    }

    data class ParsedKey(val keyName: String, val ctrl: Boolean, val shift: Boolean)

    /**
     * Break a key-sequence string (like `":next-tab<CR>"` or
     * `"tw0"`) into individual notation tokens.
     *
     * Rules:
     * - `<...>` is a single token (e.g., `<C-s>`, `<CR>`)
     * - Everything else is one token per character
     *
     * Angle-bracket tokens are normalized to canonical casing so
     * that `<ESC>`, `<esc>`, `<Escape>` all produce `<Esc>`, and
     * modifiers are always uppercase (`<c-s>` → `<C-s>`).
     */
    fun tokenize(sequence: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < sequence.length) {
            if (sequence[i] == '<') {
                val end = sequence.indexOf('>', i)
                if (end >= 0) {
                    tokens.add(normalizeAngleBracket(sequence.substring(i, end + 1)))
                    i = end + 1
                } else {
                    // Malformed <, treat as literal
                    tokens.add(sequence[i].toString())
                    i++
                }
            } else {
                tokens.add(sequence[i].toString())
                i++
            }
        }
        return tokens
    }

    /**
     * Normalize a `<...>` token to canonical casing.
     * Modifiers (`C`, `S`) become uppercase; the key name is
     * mapped through [canonicalKeyName] so `<ESC>`, `<esc>`,
     * `<Escape>` all become `<Esc>`.
     */
    fun normalizeAngleBracket(token: String): String {
        if (!token.startsWith("<") || !token.endsWith(">")) return token
        val inner = token.removeSurrounding("<", ">")
        val parts = inner.split("-").toMutableList()
        // Modifiers to uppercase
        for (j in 0 until parts.size - 1) {
            parts[j] = parts[j].uppercase()
        }
        // Key name to canonical form
        parts[parts.size - 1] = canonicalKeyName(parts.last())
        return "<${parts.joinToString("-")}>"
    }

    private fun canonicalKeyName(name: String): String = when (name.lowercase()) {
        "esc", "escape" -> "Esc"
        "cr", "enter", "return" -> "CR"
        "space" -> "Space"
        "tab" -> "Tab"
        "pgdn", "pagedown" -> "PgDn"
        "pgup", "pageup" -> "PgUp"
        "bs", "backspace" -> "BS"
        "del", "delete" -> "Del"
        "up" -> "Up"
        "down" -> "Down"
        "left" -> "Left"
        "right" -> "Right"
        else -> name  // preserve case for letters (C-s vs C-S)
    }

    // --- internal helpers ---

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
        else -> null
    }

    private fun buildNotation(ctrl: Boolean, shift: Boolean, keyName: String): String {
        val parts = mutableListOf<String>()
        if (ctrl) parts.add("C")
        if (shift) parts.add("S")
        parts.add(keyName)
        return if (parts.size == 1 && keyName.length == 1) {
            // Single char with no modifiers — plain notation
            // (but this case is handled by fromKeyTyped, not here)
            keyName
        } else {
            "<${parts.joinToString("-")}>"
        }
    }
}
