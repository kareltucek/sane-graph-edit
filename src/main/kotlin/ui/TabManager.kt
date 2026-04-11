package ui

import Graph
import graph_tools.Plotter
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.JTabbedPane
import javax.swing.event.ChangeListener

/**
 * Owns the [JTabbedPane] and the list of [GraphView]s behind it.
 *
 * Every mutable concern that was previously "window-global" is now
 * "current-tab-global": the current graph, the current file path,
 * the undo stack, the view transform. Switching tabs rewires
 * [Plotter.t] to the active view's [GraphView.viewTransform] so
 * existing rendering and input code that reads `Plotter.t` keeps
 * working without having to thread a transform parameter through.
 *
 * The manager also handles the "last tab" edge case: closing the
 * only remaining tab clears it to an empty untitled document rather
 * than removing it, so the window always has something to show and
 * the user never ends up staring at a blank frame.
 */
class TabManager(
    /**
     * Public so callers that need a reach through to the
     * containing window — the window's `JOptionPane` parenting
     * for prompts, the `refreshTitle` hook — have somewhere to
     * reach to. Nothing else in this class relies on the window.
     */
    val window: Window,
) {

    val tabbedPane: JTabbedPane = JTabbedPane()

    private val views: MutableList<GraphView> = mutableListOf()

    /**
     * Iterate every open view. Exposed for [AutosaveManager]'s
     * periodic flush — other callers should prefer [current] or
     * the tabbed pane's APIs.
     */
    fun forEachView(action: (GraphView) -> Unit) {
        views.forEach(action)
    }

    /**
     * The currently active view, or `null` if there are no tabs
     * (only possible transiently during construction). Most callers
     * want [current], which throws — the `OrNull` variant exists
     * only for code paths that run during startup.
     */
    val currentOrNull: GraphView?
        get() = tabbedPane.selectedIndex.takeIf { it in views.indices }?.let { views[it] }

    /** Same as [currentOrNull] but throws if there are no tabs. */
    val current: GraphView
        get() = views[tabbedPane.selectedIndex]

    init {
        // Open with exactly one empty tab. The window will point
        // Plotter.t at this view's transform before the first paint.
        newTab()
        tabbedPane.addChangeListener(ChangeListener { onChanged() })
    }

    /**
     * Add a new tab holding [graph] (defaults to empty) associated
     * with [path] (nullable for an untitled document) and switch to
     * it. Returns the created view.
     */
    fun newTab(graph: Graph = Graph(), path: Path? = null): GraphView {
        val gv = GraphView(initialGraph = graph, initialFile = path)
        gv.tabManager = this
        gv.onStateChange = { refreshTab(gv) }
        views.add(gv)
        tabbedPane.addTab(gv.title, gv)
        tabbedPane.selectedIndex = views.size - 1
        return gv
    }

    /**
     * Close the tab at [idx] after prompting if it has unsaved
     * changes. Returns true if the tab was actually closed (or
     * cleared, on the last-tab path) and false if the user
     * cancelled.
     */
    fun closeTab(idx: Int): Boolean {
        val gv = views.getOrNull(idx) ?: return false
        if (gv.isDirty && !promptSaveDiscardCancel(gv)) return false

        if (views.size == 1) {
            // Never leave the window with zero tabs. Reset the
            // remaining slot to a fresh empty document instead.
            // Deliberately *not* deleting backup files here —
            // autosave snapshots accumulate as a manual safety
            // net; pruning is the user's job.
            gv.clearToEmpty()
            return true
        }

        views.removeAt(idx)
        tabbedPane.remove(idx)
        return true
    }

    /**
     * Close the currently active tab. Convenience for the Ctrl+W
     * binding — pure delegation to [closeTab].
     */
    fun closeCurrent(): Boolean = closeTab(tabbedPane.selectedIndex)

    /** Switch to the next tab, wrapping around at the end. */
    fun selectNext() {
        if (views.size < 2) return
        tabbedPane.selectedIndex = (tabbedPane.selectedIndex + 1) % views.size
    }

    /** Switch to the previous tab, wrapping around at the start. */
    fun selectPrevious() {
        if (views.size < 2) return
        tabbedPane.selectedIndex =
            (tabbedPane.selectedIndex - 1 + views.size) % views.size
    }

    /**
     * Walk every tab, prompting Save / Discard / Cancel for each
     * dirty one. Returns true if every prompt was answered
     * non-cancel (or the user had nothing dirty to prompt about).
     * Used by the window's close handler.
     */
    fun confirmCloseAll(): Boolean {
        // Iterate over a snapshot because saveFile could in principle
        // mutate the list (it doesn't today, but future me might add
        // "save to a new tab" and this keeps that option open).
        for (gv in views.toList()) {
            if (gv.isDirty && !promptSaveDiscardCancel(gv)) return false
        }
        return true
    }

    /**
     * Invoked by each [GraphView] whenever its title-affecting state
     * changes (dirty flag, file path, graph swap). Updates both the
     * tab-bar label and, if this was the current tab, the window
     * title.
     */
    private fun refreshTab(gv: GraphView) {
        val idx = views.indexOf(gv)
        if (idx >= 0) {
            tabbedPane.setTitleAt(idx, gv.title)
        }
        if (gv === currentOrNull) {
            window.refreshTitle()
        }
    }

    /**
     * Handler for `JTabbedPane` selection changes. Points the global
     * [Plotter.t] at the freshly-active view's transform so
     * subsequent pans/zooms hit that tab's stored state, refreshes
     * the window title, and drops focus onto the new canvas so
     * keystrokes go to the right listener.
     */
    private fun onChanged() {
        val gv = currentOrNull ?: return
        Plotter.t = gv.viewTransform
        window.refreshTitle()
        gv.graphCanvas.requestFocusInWindow()
        gv.repaint()
    }

    /**
     * Three-choice modal prompt for one dirty tab. Returns true iff
     * the caller is clear to proceed with closing (either because
     * the user saved, or because they explicitly discarded). False
     * on cancel, or on a failed save (which JOptionPane handled via
     * its own error dialog).
     */
    private fun promptSaveDiscardCancel(gv: GraphView): Boolean {
        val result = JOptionPane.showConfirmDialog(
            window,
            "Save changes to ${gv.currentFile?.fileName ?: "(untitled)"}?",
            "Unsaved changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        return when (result) {
            JOptionPane.YES_OPTION -> gv.saveFile()  // false if Save-As was cancelled
            JOptionPane.NO_OPTION -> true
            else -> false
        }
    }
}
