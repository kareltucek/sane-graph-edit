package ui

import client.*

/**
 * Maps canonical command names (like `"undo"`, `"save"`,
 * `"edge-forward"`) to executable actions. The single source of
 * truth for "what commands exist in this editor".
 *
 * Used by:
 *  - [KeyMapper]'s `defaults` table (key → command name → here)
 *  - The `:` command bar (user types `:undo<CR>` → looked up here)
 *  - Init file `map` RHS when it contains `:command<CR>` patterns
 *
 * Commands are registered once at startup by [registerAllCommands].
 * Each command is a `(GraphView) -> Unit` lambda that receives the
 * currently active view — the same view the key event landed on.
 */
object CommandRegistry {
    private val commands = LinkedHashMap<String, (GraphView) -> Unit>()

    fun register(name: String, action: (GraphView) -> Unit) {
        commands[name] = action
    }

    /**
     * Execute a command by name against [graphView]. Returns true
     * if the command was found and executed, false if unknown.
     */
    fun execute(name: String, graphView: GraphView): Boolean {
        val action = commands[name] ?: return false
        action(graphView)
        return true
    }

    fun has(name: String): Boolean = name in commands

    /** Sorted list of all registered command names — for tab completion in the `:` bar. */
    fun list(): List<String> = commands.keys.sorted()
}

/**
 * Populate [CommandRegistry] with every editor action. Called once
 * at startup. The lambdas capture no state — they receive the
 * active [GraphView] at execution time, so they work correctly
 * across tab switches.
 *
 * Naming: commands that mirror a vim ex command use vim's exact
 * spelling (`tabnew`, `tabclose`, `saveas`, …), while commands
 * specific to this editor are kebab-case (`select-all`,
 * `edge-forward`, …). The inconsistency is intentional — it tells
 * vim-compatible commands apart from our own at a glance.
 */
fun registerAllCommands() {
    val r = CommandRegistry
    val impl = Actions

    // Structural edits
    r.register("edge-forward") { impl.drawEdge(it.model, true) }
    r.register("edge-backward") { impl.drawEdge(it.model, false) }
    r.register("new-node-edge-forward") { impl.drawNodeWithEdge(it.model, forwardEdge = true, append = false) }
    r.register("new-node-edge-backward") { impl.drawNodeWithEdge(it.model, forwardEdge = false, append = false) }
    r.register("append-forward") { impl.drawNodeWithEdge(it.model, forwardEdge = true, append = true) }
    r.register("append-backward") { impl.drawNodeWithEdge(it.model, forwardEdge = false, append = true) }
    r.register("clear-edges") { impl.clearEdges(it.model) }
    r.register("delete") { impl.deleteNode(it.model, false) }
    r.register("delete-reconnect") { impl.deleteNode(it.model, true) }
    r.register("optimize") { impl.optimize(it.model, false) }
    r.register("optimize-restrict") { impl.optimize(it.model, true) }
    // The per-second factor is LayoutOptimizer.springScaleStep
    // (default 0.8, tuneable via `:set spring-scale-step=<n>`).
    // `spring-longer` uses the inverse so held keys cancel
    // correctly across direction changes.
    r.register("spring-shorter") {
        impl.tweakSpringScale(it.model, graph_tools.LayoutOptimizer.springScaleStep)
    }
    r.register("spring-longer") {
        impl.tweakSpringScale(it.model, 1.0 / graph_tools.LayoutOptimizer.springScaleStep)
    }
    r.register("mirror-horizontal") { impl.mirror(it.model, horizontal = true) }
    r.register("mirror-vertical") { impl.mirror(it.model, horizontal = false) }
    r.register("mirror") { impl.mirror(it.model, horizontal = true) }  // alias for mirror-horizontal
    r.register("rotate") { impl.rotate(it.model) }
    r.register("scale") { impl.scale(it.model) }
    r.register("constrain-axis-x") { impl.constrainAxisX(it.model) }
    r.register("constrain-axis-y") { impl.constrainAxisY(it.model) }

    // Selection & navigation
    r.register("invert-selection") { impl.invertSelection(it.model) }
    r.register("select-closure-forward") { impl.selectClosure(it.model, true) }
    r.register("select-closure-backward") { impl.selectClosure(it.model, false) }
    r.register("select-linked-forward") { impl.selectLinked(it.model, true) }
    r.register("select-linked-backward") { impl.selectLinked(it.model, false) }
    r.register("unselect-oldest-forward") { impl.unselectOldestGen(it.model, true) }
    r.register("unselect-oldest-backward") { impl.unselectOldestGen(it.model, false) }
    r.register("select-all") { impl.selectAll(it.model) }
    r.register("deselect") { impl.unselectAll(it.model) }
    r.register("bound-screen") { impl.boundScreen(it.model) }
    r.register("center-screen") { impl.centerScreen(it.model) }
    r.register("edit-node") { impl.editNode(it.model, false) }
    r.register("select-and-edit-node") { impl.editNode(it.model, true) }

    // Filtering
    r.register("hide") { impl.hideSelected(it.model) }
    r.register("unhide") { impl.unhideOneLevel(it.model) }

    // Style
    r.register("paste-format") { impl.pasteFormat(it.model) }
    r.register("copy-format") { impl.copyFormat(it.model) }

    // History
    r.register("undo") { impl.undo(it.model) }
    r.register("redo") { impl.redo(it.model) }

    // Movement
    r.register("grab") { impl.grab(it.model) }

    // Composite built-in (G = tw0 = closure → unselect-oldest → fit)
    r.register("subgraph-focus") { gv ->
        impl.selectClosure(gv.model, true)
        impl.unselectOldestGen(gv.model, true)
        impl.boundScreen(gv.model)
    }

    // Clipboard
    r.register("copy") { impl.copySelection(it.model) }
    r.register("cut") { impl.cutSelection(it.model) }
    r.register("paste") { impl.pasteClipboard(it.model) }

    // Files
    r.register("save") { it.content.save() }
    r.register("saveas") { it.content.saveAs() }
    r.register("open") { it.content.open() }
    r.register("reload") { it.content.reload() }
    r.register("file") { it.content.showFilePath() }
    r.register("follow-file-link") { it.content.followFileLink() }
    r.register("follow-file-link-in-tab") { it.content.followFileLinkInTab() }
    r.register("pick-file") { FilePicker.run(it) }
    r.register("jump-back") { it.content.jumpBack() }
    r.register("jump-forward") { it.content.jumpForward() }
    r.register("browse") { it.tabManager?.browse(Workspace.dir) }
    r.register("export-svg") { it.content.exportSvg() }
    r.register("export-selection") { it.content.exportSelection() }

    // Tabs
    r.register("tabnew") { it.tabManager?.browse(Workspace.dir) }
    r.register("tabedit") { it.tabManager?.openInNewTab() }
    r.register("tabclose") { it.tabManager?.closeCurrent() }
    r.register("tabnext") { it.tabManager?.selectNext() }
    r.register("tabprevious") { it.tabManager?.selectPrevious() }

    // Search navigation
    r.register("search-next") { gv ->
        val ss = gv.searchState
        val dir = if (ss.forward) 1 else -1
        val node = if (dir > 0) ss.next() else ss.prev()
        if (node != null) {
            gv.g.cleanSelect(setOf(node))
            gv.model.centerScreen()
        }
    }
    r.register("search-prev") { gv ->
        val ss = gv.searchState
        val dir = if (ss.forward) 1 else -1
        val node = if (dir > 0) ss.prev() else ss.next()
        if (node != null) {
            gv.g.cleanSelect(setOf(node))
            gv.model.centerScreen()
        }
    }
}
