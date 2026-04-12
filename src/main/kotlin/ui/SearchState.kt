package ui

import graph_tools.Node

/**
 * Holds the result of the last confirmed search (`/query<CR>`)
 * so that `n`/`N` can cycle through matches independently of
 * the current selection.
 */
class SearchState {
    /** The confirmed query string (without the `/` or `?` prefix). */
    var query: String = ""

    /** Matching nodes sorted spatially (top-to-bottom, left-to-right). */
    var results: List<Node> = emptyList()

    /** Current `n`/`N` cursor index into [results]. */
    var cursor: Int = 0

    /** True if the search was opened with `/` (forward). False for `?`. */
    var forward: Boolean = true

    /** The selection before the search bar opened — restored on Escape. */
    var selectionBeforeSearch: Set<Node> = emptySet()

    /** Advance the cursor and return the focused node, or null if no results. */
    fun next(): Node? {
        if (results.isEmpty()) return null
        cursor = (cursor + 1) % results.size
        return results[cursor]
    }

    /** Move the cursor back and return the focused node, or null if no results. */
    fun prev(): Node? {
        if (results.isEmpty()) return null
        cursor = (cursor - 1 + results.size) % results.size
        return results[cursor]
    }

    companion object {
        /** Smartcase substring match: case-insensitive if query is all lowercase. */
        fun matches(text: String, query: String): Boolean {
            if (query.isEmpty()) return false
            val caseSensitive = query.any { it.isUpperCase() }
            return if (caseSensitive) {
                text.contains(query)
            } else {
                text.contains(query, ignoreCase = true)
            }
        }
    }
}
