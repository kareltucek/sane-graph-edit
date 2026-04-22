package ui

import Graph
import export.SvgWriter
import graph_tools.GraphTools
import graph_tools.Node
import graph_tools.Plotter
import utils.Utils.orElse
import utils.Utils.toWorkspaceVector
import utils.Constants
import utils.Constants.stylePickerDimensions
import utils.Vector2
import java.awt.Graphics
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.AffineTransform
import java.nio.file.Path
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLayeredPane
import javax.swing.JOptionPane
import javax.swing.SpringLayout

/**
 * The editor's main document view. Holds one [Graph] plus the three
 * stacked widgets that make up the editing surface:
 *
 *   - [graphCanvas] — the rendered graph (layer 0)
 *   - [nodeEditor] — in-place text editor that pops up over a node (layer 1)
 *   - [stylePicker] — colour/shape/size popup (layer 1)
 *
 * Input handling is delegated to [mouseListener] and [keyListener]. The
 * listeners mutate [g] and then call `repaint()`; the actual drawing
 * happens in [graphCanvas]'s paint path via [graph_tools.Plotter].
 *
 * One `GraphView` is "one open document" in the tab model: it owns a
 * [Graph] (and therefore a per-document undo history), a [currentFile]
 * pointer, an [isDirty] flag, and a [viewTransform] that pans/zooms
 * independently of other tabs.
 *
 * On tab switch, [ui.TabManager] rewires [Plotter.t] to point at the
 * active view's [viewTransform]. That's the single piece of global
 * state that still makes rendering work with multiple tabs without
 * having to thread a transform through every draw call.
 */
class GraphView(
    initialGraph: Graph = Graph.defaultGraph(),
    initialFile: Path? = null,
) : JLayeredPane() {
    val graphCanvas = GraphCanvas(this)
    val nodeEditor = NodeEditor(this)
    val stylePicker = StylePicker(this)
    val springLayout = SpringLayout()
    val mouseListener = GraphMouseListener(this)
    val keyListener = GraphKeyListener(this)
    var lastCursorPosition: Vector2 = Vector2.Zero
    /**
     * Last cursor position in *canvas-local screen* coordinates
     * (pre-pan/zoom). Updated in lockstep with [lastCursorPosition]
     * by [GraphMouseListener]. The scale gesture reads this so
     * its factor stays stable against pan/zoom.
     */
    var lastScreenCursorPosition: Vector2 = Vector2.Zero
    var optimizeOnDrag: Boolean = false
    var defaultNodeStyle: NodeStyle = NodeStyle()

    /**
     * The graph this view is currently editing. Swap it out via
     * [replaceGraph] — direct assignment would leave the previous
     * graph's history listener dangling.
     */
    var g: Graph = initialGraph
        private set

    /**
     * On-disk location the current graph was loaded from or last
     * saved to. `null` means "untitled" — saving hits the Save-As
     * dialog flow.
     */
    var currentFile: Path? = initialFile
        private set

    /**
     * True if the graph has been mutated since the last save or
     * load. Flipping this runs [onStateChange] so the tab title and
     * window title can refresh.
     */
    var isDirty: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                onStateChange()
            }
        }

    /**
     * Per-view affine transform. [ui.TabManager] points [Plotter.t]
     * at this object whenever this view becomes the active tab, so
     * existing input code (pan/zoom/centering) that mutates
     * `Plotter.t` ends up mutating the right tab's transform.
     *
     * Starts at identity; the first paint in [GraphCanvas.doDrawing]
     * translates to the canvas centre.
     */
    val viewTransform: AffineTransform = AffineTransform()

    /**
     * Notified whenever anything that affects the tab title or the
     * window title changes (dirty flag, file path, graph swap). The
     * parent [ui.TabManager] sets this; the default no-ops so the
     * view is usable outside a tab manager (e.g. in tests).
     */
    var onStateChange: () -> Unit = {}

    /**
     * Backref to the [ui.TabManager] that owns this view, if any.
     * Key bindings that need to affect other tabs (Ctrl+T new,
     * Ctrl+W close, Ctrl+Tab next, Ctrl+Shift+Tab prev) reach up
     * through this field. Null when the view is used standalone,
     * e.g. in unit tests.
     */
    var tabManager: TabManager? = null

    /**
     * Shared key mapper. Set by [Window] at startup after
     * [registerAllCommands] populates the [CommandRegistry].
     * All tabs share the same mapper (same bindings), but the
     * mapper's [KeyMapper.activeView] is set to the current
     * GraphView before each key feed.
     */
    var keyMapper: KeyMapper? = null

    /**
     * The command bar (`:` / `/` / `?`). Set after construction
     * because it needs a reference back to this view. Null in
     * unit tests.
     */
    var commandBar: CommandBar? = null

    /**
     * The message area that docks at the bottom for multi-line
     * output from `:help`, `:map`, etc. Null in unit tests and in
     * headless mode (those paths print to stdout instead).
     */
    var messageArea: MessageArea? = null

    /**
     * Bottom-docked one-line status indicator. Surfaces the
     * active Transform gesture + axis-lock, plus macro-recording
     * state. Null outside interactive mode.
     */
    var statusBar: StatusBar? = null

    /** Search state: last confirmed query, results, n/N cursor. */
    val searchState: SearchState = SearchState()

    /**
     * Opaque per-tab identifier used as the autosave-backup
     * filename for untitled tabs (tabs with a file hash their
     * path instead — see [BackupPaths]). Stable for the tab's
     * entire lifetime, even if it gets saved and so starts using
     * the file-hash key; holding on to the UUID means
     * `deleteBackup` can clean up a pre-save untitled backup
     * during a save-as.
     */
    val autosaveId: String = UUID.randomUUID().toString()

    init {
        attachHistoryListener()
    }

    /**
     * Hook the current graph's [graph_tools.History] to flip
     * [isDirty] on any mutation. Called at construction and again
     * whenever the graph is replaced (open/new), so listeners never
     * accumulate against a graph that's no longer displayed.
     */
    private fun attachHistoryListener() {
        g.history.addListener {
            // Any mutation makes the document "not the same as the
            // saved file any more". We don't try to detect the case
            // where the user undoes back to the saved state — that's
            // the "clean index" problem from text editors and isn't
            // worth the complexity for this tool.
            isDirty = true
        }
    }

    /**
     * Label the tab bar and window title should use for this view:
     * the file's basename if there is one, `(untitled)` otherwise,
     * prefixed with `*` while the graph is dirty.
     */
    val title: String
        get() {
            val base = currentFile?.fileName?.toString() ?: "(untitled)"
            return if (isDirty) "*$base" else base
        }

    /**
     * Recompute and push the status-bar text from current state:
     * active gesture (from mouse controller state), axis-lock
     * flags, and macro-recording flag. Safe to call when
     * [statusBar] is null (headless, tests).
     */
    fun refreshStatus() {
        val bar = statusBar ?: return
        val c = mouseListener.controller
        val gestureName = when (c.state) {
            GraphMouseListener.GraphMouseController.States.Scaling -> "SCALE"
            GraphMouseListener.GraphMouseController.States.MovingNodes -> "GRAB"
            GraphMouseListener.GraphMouseController.States.Rotating -> "ROTATE"
            else -> null
        }
        val lockSuffix = when {
            gestureName == null -> ""
            c.lockX && !c.lockY -> " (Y)"  // X frozen → scaling/moving along Y
            c.lockY && !c.lockX -> " (X)"  // Y frozen → scaling/moving along X
            else -> ""
        }
        val gesture = gestureName?.let { "-- $it$lockSuffix --" } ?: ""
        val recording = keyMapper?.isRecording
        val recText = if (recording == true) "recording" else ""
        val parts = listOf(gesture, recText).filter { it.isNotEmpty() }
        bar.relayout(parts.joinToString("    "))
    }

    fun placeMeAt(me: JComponent, ul: Vector2, br: Vector2) {
        springLayout.putConstraint(
            SpringLayout.WEST,
            me,
            ul.x.toInt() + Constants.frameMargin,
            SpringLayout.WEST,
            this
        );
        springLayout.putConstraint(
            SpringLayout.NORTH,
            me,
            ul.y.toInt() + Constants.frameMargin,
            SpringLayout.NORTH,
            this
        );
        springLayout.putConstraint(
            SpringLayout.EAST,
            me,
            br.x.toInt() + Constants.frameMargin,
            SpringLayout.WEST,
            this
        );
        springLayout.putConstraint(
            SpringLayout.SOUTH,
            me,
            br.y.toInt() + Constants.frameMargin,
            SpringLayout.NORTH,
            this
        );
    }

    fun setTextFieldVisible(visible: Boolean) {
        nodeEditor.isVisible = visible
    }

    fun startNodeEdit(n: Node, screenClickCoordinates: Vector2?) {
        endNodeEdit()
        nodeEditor.startNodeEdit(n, screenClickCoordinates)
        nodeEditor.isVisible = true
        nodeEditor.requestFocus()
        parent.repaint()
    }

    fun endNodeEdit() {
        nodeEditor.endNodeEdit()
        nodeEditor.isVisible = false
        graphCanvas.requestFocus()
        parent.repaint()
    }

    fun startStylePicker(screenCoordinatesPosition: Vector2? = null) {
        val center = screenCoordinatesPosition.orElse(lastCursorPosition.toWorkspaceVector())
        val ul = center - stylePickerDimensions / 2
        val br = center + stylePickerDimensions / 2
        placeMeAt(stylePicker, ul, br)
        stylePicker.isVisible = true
        stylePicker.requestFocus()
        parent.repaint()
    }

    fun endStylePicker() {
        stylePicker.isVisible = false
        graphCanvas.requestFocus()
        parent.repaint()
    }

    /**
     * Swap [g] for [newGraph], re-wire the history listener, and
     * reset the dirty flag. Used by open, new, and clear.
     */
    private fun replaceGraph(newGraph: Graph) {
        g = newGraph
        attachHistoryListener()
        isDirty = false
        onStateChange()
    }

    /**
     * Save to [currentFile], or fall through to [saveFileAs] if the
     * graph has never been saved. Returns true on success, false if
     * the user cancelled a Save-As dialog.
     */
    fun saveFile(): Boolean {
        val path = currentFile ?: return saveFileAs()
        return writeTo(path)
    }

    /**
     * Unconditionally show a Save-As dialog and write to the chosen
     * path. Returns false on cancellation or write failure.
     */
    fun saveFileAs(): Boolean {
        val defaultName = currentFile?.fileName?.toString() ?: "untitled.dot"
        val path = FileOps.saveDialog(this, defaultName) ?: return false
        return writeTo(path)
    }

    private fun writeTo(path: Path): Boolean {
        return try {
            DotGraphLoader.saveToFile(g, path.toString())
            currentFile = path
            isDirty = false
            onStateChange()
            // Persist the new file path in case this was the
            // first save of an untitled tab (or a save-as
            // redirecting an existing tab at a new file).
            tabManager?.saveSession()
            true
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(
                this,
                "Could not save to $path:\n${t.message}",
                "Save error",
                JOptionPane.ERROR_MESSAGE,
            )
            false
        }
    }

    /**
     * Prompt for a file, load it into this view, and fit the view
     * to the new graph. Reports parse errors via a dialog; leaves
     * the current graph untouched on failure.
     */
    fun openFile() {
        val path = FileOps.openDialog(this) ?: return
        try {
            val loaded = DotGraphLoader.loadFromFile(path.toString())
            replaceGraph(loaded)
            currentFile = path
            onStateChange()
            boundScreen()
            repaint()
            tabManager?.saveSession()
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(
                this,
                "Could not open $path:\n${t.message}",
                "Open error",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    /**
     * Export the full graph as SVG. Prompts for a file path via
     * the save dialog; default filename matches the current DOT
     * file with a `.svg` extension.
     */
    fun exportSvg() {
        val baseName = currentFile?.fileName?.toString()
            ?.removeSuffix(".dot")?.plus(".svg")
            ?: "untitled.svg"
        val path = FileOps.exportSvgDialog(this, baseName) ?: return
        // Export only visible nodes so hidden nodes stay hidden in the SVG.
        val visible = g.nodes.filter { it.isVisible }.toSet()
        writeExport(path, SvgWriter.write(g, visible))
    }

    /**
     * Export only the selected nodes (and edges between them) as
     * SVG. Falls back to full-graph export if nothing is selected.
     */
    fun exportSelection() {
        val sel = g.selectedNodes.takeIf { it.isNotEmpty() }
        val baseName = currentFile?.fileName?.toString()
            ?.removeSuffix(".dot")?.plus("-selection.svg")
            ?: "selection.svg"
        val path = FileOps.exportSvgDialog(this, baseName) ?: return
        writeExport(path, SvgWriter.write(g, sel))
    }

    private fun writeExport(path: java.nio.file.Path, content: String) {
        try {
            java.nio.file.Files.writeString(path, content)
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(
                this,
                "Could not export to $path:\n${t.message}",
                "Export error",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    /**
     * Replace the current graph with an empty one. Use case: the
     * user hits Ctrl+N or closes the last remaining tab.
     */
    fun clearToEmpty() {
        replaceGraph(Graph())
        currentFile = null
        onStateChange()
        repaint()
    }

    init {
        nodeEditor.text = ""

        this.layout = springLayout
        this.add(graphCanvas)
        this.add(nodeEditor)
        this.add(stylePicker)

        springLayout.putConstraint(SpringLayout.WEST, graphCanvas, Constants.frameMargin, SpringLayout.WEST, this);
        springLayout.putConstraint(SpringLayout.NORTH, graphCanvas, Constants.frameMargin, SpringLayout.NORTH, this);
        springLayout.putConstraint(SpringLayout.EAST, graphCanvas, -Constants.frameMargin, SpringLayout.EAST, this);
        springLayout.putConstraint(SpringLayout.SOUTH, graphCanvas, -Constants.frameMargin, SpringLayout.SOUTH, this);

        this.setLayer(graphCanvas, 0)
        this.setLayer(nodeEditor, 1)
        this.setLayer(stylePicker, 1)

        graphCanvas.addMouseListener(mouseListener)
        graphCanvas.addMouseMotionListener(mouseListener)
        graphCanvas.addMouseWheelListener(mouseListener)
        graphCanvas.addKeyListener(keyListener)
        nodeEditor.addMouseWheelListener(mouseListener)

        stylePicker.isVisible = false
        nodeEditor.isVisible = false
        graphCanvas.isVisible = true
        this.isVisible = true

        validate()
        repaint()
        graphCanvas.repaint()
    }

    fun setViewTo(pos: Vector2, scale: Double = 1.0) {
        Plotter.t.setToScale(scale, scale)
        val viewCenter = Vector2(this.width, this.height).let { it / 2 }.toWorkspaceVector()
        val diff = viewCenter - pos
        Plotter.t.translate(diff.x.toDouble(), diff.y.toDouble())
        repaint()
    }

    fun centerScreen() {
        val visible = g.nodes.filter { it.isVisible }
        val center =
            g.selectedNodes
                .takeIf { it.isNotEmpty() }
                .orElse(visible)
                .let { GraphTools.computeCenterOfMass(it) }

        setViewTo(center, 1.0)
    }

    fun boundScreen() {
        val visible = g.nodes.filter { it.isVisible }
        val box =
            g.selectedNodes
                .takeIf { it.isNotEmpty() }
                .orElse(visible)
                .let { GraphTools.computeBoundingBox(it) }

        box?.let { r ->
            val ul = r.ul
            val br = r.br

            val screenSize = Vector2(this.width, this.height)
            val size = br - ul
            val scale = screenSize / size
            val targetScale = Math.min(scale.x, scale.y)
                .let { it / 1.1 }
                .coerceIn(0.0001, 1.0)

            setViewTo((box.ul + box.br)/2, targetScale)
        }
    }

    public override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        graphCanvas.doDrawing(g)
    }

    public override fun repaint() {
        super.repaint()
    }
}


/**
 * Top-level application window. Holds a [TabManager] that swaps
 * between [GraphView]s and an [AutosaveManager] that writes
 * crash-safety backups of every dirty tab to
 * `$XDG_CACHE_HOME/sane-graph-edit/backups`.
 *
 * Startup restores the previous session from
 * `$XDG_CONFIG_HOME/sane-graph-edit/session.properties` (if any):
 * every file that was open last time is reopened in a tab, and
 * the tab that was active last time becomes active again.
 * Session restore only reopens files from disk — it does not
 * recover unsaved edits. That's what the autosave backup
 * directory is for, and it is left strictly alone by this
 * class; recovery from a backup is a manual operation.
 *
 * Close-on-dirty: overrides the default `EXIT_ON_CLOSE` so that a
 * [java.awt.event.WindowAdapter] can intercept the close event and
 * prompt Save / Discard / Cancel for every dirty tab before
 * letting the process exit. On confirmed close the session is
 * flushed one last time and the autosave timer is cancelled.
 */
class Window(
    title: String,
    private val initialFile: String? = null,
) : JFrame() {
    val keyMapper: KeyMapper = KeyMapper(KeyMapper.defaultBindings())
    val tabManager: TabManager = TabManager(this)
    val autosave: AutosaveManager = AutosaveManager(tabManager)

    init {
        // Register all commands before anything else — the mapper
        // and the : bar both need the registry populated.
        registerAllCommands()
        keyMapper.commandExecutor = { cmd, gv -> CommandRegistry.execute(cmd, gv) }
        keyMapper.markSetter = { gv, letter -> GraphKeyListener.impl.setMark(gv, letter) }
        keyMapper.markRecaller = { gv, letter -> GraphKeyListener.impl.recallMark(gv, letter) }
        keyMapper.installDefaultTransformBindings()
        // Mode changes (Normal ↔ Transform) → refresh the active
        // view's status bar. We can't know which tab the change
        // applied to, so refresh the currently-focused one —
        // modal gestures are scoped to one view anyway.
        keyMapper.modeListener = { tabManager.currentOrNull?.refreshStatus() }

        // Load the user's init file (key mappings, settings).
        keyMapper.loadInitFile(XdgPaths.appConfigDir.resolve("init"))

        // Populate the tab bar from the persisted session — or,
        // if none exists, create a single empty tab so the
        // window has something to show.
        tabManager.bootstrap()

        // If the user passed a file argument, open it in a new tab
        // on top of whatever the session restored.
        initialFile?.let { path ->
            try {
                val loaded = DotGraphLoader.loadFromFile(path)
                tabManager.newTab(graph = loaded, path = java.nio.file.Paths.get(path))
            } catch (t: Throwable) {
                System.err.println("sane-graph-edit: could not open '$path': ${t.message}")
            }
        }
        // Point the renderer's transform at the active tab so
        // the canvas's initial paint lands on the right view
        // transform. Tab switches rewire this in
        // TabManager.onChanged.
        Plotter.t = tabManager.current.viewTransform
        createUI(title)
        autosave.start()
    }

    /** Update the frame title to reflect the active tab's document state. */
    fun refreshTitle(baseAppName: String = "sane-graph-edit") {
        val tab = tabManager.currentOrNull
        setTitle(if (tab != null) "${tab.title} — $baseAppName" else baseAppName)
    }

    fun createUI(title: String) {
        setTitle(title)
        add(tabManager.tabbedPane)

        // Intercept close so we can prompt for each dirty tab,
        // flush the session, and stop the autosave timer before
        // exiting.
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if (tabManager.confirmCloseAll()) {
                    tabManager.saveSession()
                    autosave.stop()
                    dispose()
                    System.exit(0)
                }
            }
        })

        setSize(800, 600)
        setLocationRelativeTo(null)
        refreshTitle()

        validate()
        repaint()
    }
}
