package ui

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
 */
fun registerAllCommands() {
    val r = CommandRegistry
    val impl = GraphKeyListener.impl

    // Structural edits
    r.register("edge-forward") { impl.drawEdge(it, true) }
    r.register("edge-backward") { impl.drawEdge(it, false) }
    r.register("new-node-edge-forward") { impl.drawNodeWithEdge(it, forwardEdge = true, append = false) }
    r.register("new-node-edge-backward") { impl.drawNodeWithEdge(it, forwardEdge = false, append = false) }
    r.register("append-forward") { impl.drawNodeWithEdge(it, forwardEdge = true, append = true) }
    r.register("append-backward") { impl.drawNodeWithEdge(it, forwardEdge = false, append = true) }
    r.register("clear-edges") { impl.clearEdges(it) }
    r.register("delete") { impl.deleteNode(it, false) }
    r.register("delete-reconnect") { impl.deleteNode(it, true) }
    r.register("optimize") { impl.optimize(it, false) }
    r.register("optimize-restrict") { impl.optimize(it, true) }
    // The per-second factor is LayoutOptimizer.springScaleStep
    // (default 0.8, tuneable via `:set spring-scale-step=<n>`).
    // `spring-longer` uses the inverse so held keys cancel
    // correctly across direction changes.
    r.register("spring-shorter") {
        impl.tweakSpringScale(it, graph_tools.LayoutOptimizer.springScaleStep)
    }
    r.register("spring-longer") {
        impl.tweakSpringScale(it, 1.0 / graph_tools.LayoutOptimizer.springScaleStep)
    }
    r.register("mirror-horizontal") { impl.mirror(it, horizontal = true) }
    r.register("mirror-vertical") { impl.mirror(it, horizontal = false) }
    r.register("mirror") { impl.mirror(it, horizontal = true) }  // alias for mirror-horizontal
    r.register("rotate") { impl.rotate(it) }
    r.register("scale") { impl.scale(it) }
    r.register("constrain-axis-x") { impl.constrainAxisX(it) }
    r.register("constrain-axis-y") { impl.constrainAxisY(it) }

    // Selection & navigation
    r.register("invert-selection") { impl.invertSelection(it) }
    r.register("select-closure-forward") { impl.selectClosure(it, true) }
    r.register("select-closure-backward") { impl.selectClosure(it, false) }
    r.register("select-linked-forward") { impl.selectLinked(it, true) }
    r.register("select-linked-backward") { impl.selectLinked(it, false) }
    r.register("unselect-oldest-forward") { impl.unselectOldestGen(it, true) }
    r.register("unselect-oldest-backward") { impl.unselectOldestGen(it, false) }
    r.register("select-all") { impl.selectAll(it) }
    r.register("deselect") { impl.unselectAll(it) }
    r.register("bound-screen") { impl.boundScreen(it) }
    r.register("center-screen") { impl.centerScreen(it) }
    r.register("edit-node") { impl.editNode(it, false) }
    r.register("select-and-edit-node") { impl.editNode(it, true) }

    // Filtering
    r.register("hide") { impl.hideSelected(it) }
    r.register("unhide") { impl.unhideOneLevel(it) }

    // Style
    r.register("paste-format") { impl.pasteFormat(it) }
    r.register("copy-format") { impl.copyFormat(it) }

    // History
    r.register("undo") { impl.undo(it) }
    r.register("redo") { impl.redo(it) }

    // Movement
    r.register("grab") { impl.grab(it) }

    // Composite built-in (G = tw0 = closure → unselect-oldest → fit)
    r.register("subgraph-focus") { gv ->
        impl.selectClosure(gv, true)
        impl.unselectOldestGen(gv, true)
        impl.boundScreen(gv)
    }

    // Clipboard
    r.register("copy") { impl.copySelection(it) }
    r.register("cut") { impl.cutSelection(it) }
    r.register("paste") { impl.pasteClipboard(it) }

    // Files
    r.register("save") { it.saveFile() }
    r.register("save-as") { it.saveFileAs() }
    r.register("open") { it.openFile() }
    r.register("export-svg") { it.exportSvg() }
    r.register("export-selection") { it.exportSelection() }

    // Tabs
    r.register("tabnew") { it.tabManager?.newTab() }
    r.register("close-tab") { it.tabManager?.closeCurrent() }
    r.register("next-tab") { it.tabManager?.selectNext() }
    r.register("prev-tab") { it.tabManager?.selectPrevious() }

    // Search navigation
    r.register("search-next") { gv ->
        val ss = gv.searchState
        val dir = if (ss.forward) 1 else -1
        val node = if (dir > 0) ss.next() else ss.prev()
        if (node != null) {
            gv.g.cleanSelect(setOf(node))
            gv.centerScreen()
        }
    }
    r.register("search-prev") { gv ->
        val ss = gv.searchState
        val dir = if (ss.forward) 1 else -1
        val node = if (dir > 0) ss.prev() else ss.next()
        if (node != null) {
            gv.g.cleanSelect(setOf(node))
            gv.centerScreen()
        }
    }
}
