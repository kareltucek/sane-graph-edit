package ui

import client.CommandRegistry
import client.EditorView
import client.Workspace
import client.registerEditingCommands

private val EditorView.graphView: GraphView get() = shell as GraphView

/** Run the command [name] on [graphView]; false when there is none. */
fun CommandRegistry.execute(name: String, graphView: GraphView): Boolean = execute(name, graphView.model)

/**
 * Populate [CommandRegistry]: the client's commands and those that
 * only this application has — its tabs and its file picker. The
 * shell of every view they are run on is a [GraphView].
 */
fun registerAllCommands() {
    registerEditingCommands()
    val r = CommandRegistry

    r.register("pick-file") { FilePicker.run(it.graphView) }
    r.register("browse") { it.graphView.tabManager?.browse(Workspace.dir) }

    // Tabs
    r.register("tabnew") { it.graphView.tabManager?.browse(Workspace.dir) }
    r.register("tabedit") { it.graphView.tabManager?.openInNewTab() }
    r.register("tabclose") { it.graphView.tabManager?.closeCurrent() }
    r.register("tabnext") { it.graphView.tabManager?.selectNext() }
    r.register("tabprevious") { it.graphView.tabManager?.selectPrevious() }
}
