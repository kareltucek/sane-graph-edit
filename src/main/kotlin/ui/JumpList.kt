package ui

import java.nio.file.Path

/**
 * Vim-style jump list of one tab: `Ctrl+O` goes back to where a jump
 * (following a link, opening a file into the tab) started, `Ctrl+I`
 * forward again.
 *
 * Like vim: a jump appends the location it leaves, dropping an older
 * entry for the same spot; going back from the newest entry first
 * appends the current location, so `Ctrl+I` can return to it; a jump
 * made after going back keeps the entries ahead (it doesn't truncate).
 */
class JumpList(
    private val entries: MutableList<Location> = mutableListOf(),
    /** Index of the entry we're at; `entries.size` = past the newest one. */
    private var index: Int = entries.size,
) {
    /**
     * Where the tab was: its file (or browsed directory; both null for
     * an untitled graph), the selected nodes (by DOT id, or label when
     * the node has none) and the view transform.
     */
    data class Location(
        val file: Path?,
        val browseDir: Path?,
        val selection: Set<String>,
        val transform: List<Double>,
        /** The lines shown when the tab is a [SourceView]. */
        val sourceView: FileLink.Target? = null,
    ) {
        fun sameSpot(other: Location): Boolean = sameGraph(other) && selection == other.selection

        fun sameGraph(other: Location): Boolean =
            file == other.file && browseDir == other.browseDir && sourceView == other.sourceView
    }

    val size: Int get() = entries.size

    /** Remember [from] as the start of a jump. */
    fun record(from: Location) {
        entries.removeAll { it.sameSpot(from) }
        entries.add(from)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        index = entries.size
    }

    /** The entry to go back to from [current], or null at the oldest one. */
    fun back(current: Location): Location? {
        if (index >= entries.size) {
            record(current)
            index = entries.size - 1
        }
        return if (index <= 0) null else entries[--index]
    }

    /** The entry to go forward to, or null at the newest one. */
    fun forward(): Location? = if (index >= entries.size - 1) null else entries[++index]

    /** For undoing a [back] / [forward] whose target couldn't be opened. */
    fun position(): Int = index

    fun restorePosition(p: Int) {
        index = p
    }

    /** An independent copy (a tab opened with `gF` inherits the list, like a vim window). */
    fun copy(): JumpList = JumpList(entries.toMutableList(), index)

    companion object {
        const val MAX_ENTRIES = 100
    }
}
