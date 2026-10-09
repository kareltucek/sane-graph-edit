package graph_tools

/**
 * Global display / traversal modes, toggled with `:set` (vim-style
 * boolean options) and shown in the status bar while on. Global
 * rather than per tab, like vim options. Session-only.
 */
object ViewModes {
    /**
     * `:set unhide` — traversal commands (select-linked-*,
     * select-closure-*, and composites built on them) step into
     * hidden nodes too, and unhide every hidden node they select.
     */
    var unhide: Boolean = false

    /**
     * `:set show-parents` — besides the non-hidden and the selected
     * nodes, also show every parent (edge source) of those. See
     * [Graph.isVisible]. Display rule only; hide levels are not
     * touched.
     */
    var showParents: Boolean = false

    /** `:set show-children` — like [showParents], for children (edge targets). */
    var showChildren: Boolean = false

    /** `:set dynamic` — nodes that become visible are moved next to the visible ones ([DynamicPlacement]). */
    var dynamic: Boolean = false

    /**
     * `:set discard` — never ask about unsaved changes: opening,
     * following links, reloading, jumping, closing tabs and quitting
     * just discard them.
     */
    var discard: Boolean = false
}
