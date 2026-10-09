package ui

import client.*
import Graph
import graph_tools.AwtTextMeasurer
import utils.Utils.orElse
import utils.Constants
import utils.Constants.stylePickerDimensions
import utils.Vector2
import java.awt.Graphics
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
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
 * One `GraphView` is one tab. What it shows and how it is edited
 * is in its [model]; this class shows it and asks the user.
 */
class GraphView(
    initialGraph: Graph = Graph.defaultGraph(),
    initialFile: Path? = null,
) : JLayeredPane(), Renderer, Shell {
    val graphCanvas = GraphCanvas(this)
    val nodeEditor = NodeEditor(this)
    val stylePicker = StylePicker(this)
    val springLayout = SpringLayout()
    val mouseListener = GraphMouseListener(this)
    val keyListener = GraphKeyListener(this)

    // What is per user but not Swing lives in [model]; these are shorthands into it.
    var lastCursorPosition: Vector2
        get() = model.lastCursorPosition
        set(value) {
            model.lastCursorPosition = value
        }
    var lastScreenCursorPosition: Vector2
        get() = model.lastScreenCursorPosition
        set(value) {
            model.lastScreenCursorPosition = value
        }
    /**
     * The toolkit-free half of this view: where it looks, the
     * cursor, the gesture in progress, what file it shows, and the
     * editing done with them. This class is its [Renderer] and its
     * [Shell]: the Swing half.
     */
    val model: EditorView = EditorView(initialGraph, this, this, Editor.client, initialFile)

    val g: Graph get() = model.g
    val content: ViewContent get() = model.content
    val searchState: SearchState get() = model.searchState
    val currentFile: Path? get() = content.currentFile
    val isDirty: Boolean get() = content.isDirty
    val title: String get() = content.title

    // --- what the client asks of the application (Shell) ---

    /** In the message area; printed when there is none (headless runs, tests). */
    override fun message(text: String) {
        val area = messageArea.takeIf { !java.awt.GraphicsEnvironment.isHeadless() }
        area?.show(text) ?: print(text)
    }

    override fun alert(title: String, text: String, error: Boolean) {
        JOptionPane.showMessageDialog(this, text, title, if (error) JOptionPane.ERROR_MESSAGE else JOptionPane.INFORMATION_MESSAGE)
    }

    override fun confirm(title: String, question: String): Boolean =
        JOptionPane.showConfirmDialog(this, question, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION

    override fun chooseOpen(): Path? = FileOps.openDialog(this)

    override fun chooseSave(defaultName: String): Path? = FileOps.saveDialog(this, defaultName)

    override fun chooseExport(defaultName: String): Path? = FileOps.exportSvgDialog(this, defaultName)

    override fun stateChanged() = onStateChange()

    override fun sessionChanged() {
        tabManager?.saveSession()
    }

    override fun newTab(): EditorView? = tabManager?.newTab()?.model

    override fun edit(path: Path): EditorView? = tabManager?.edit(path)?.model

    override fun browse(dir: Path): EditorView? = tabManager?.browse(dir)?.model

    override fun switchWorkspace(dir: Path) {
        // headless: no tabs, just the directory
        tabManager?.switchWorkspace(dir) ?: run { Workspace.dir = dir }
    }

    override fun quit(force: Boolean, all: Boolean) {
        if (all) tabManager?.window?.quit(force) else tabManager?.quitCurrent(force)
    }

    override fun modesChanged() {
        refreshStatus()
        tabManager?.forEachView { it.repaint() } ?: repaint()
    }

    /**
     * This view's pan and zoom. Starts at identity; the first paint
     * in [GraphCanvas.doDrawing] translates to the canvas centre.
     */
    val viewTransform: ViewTransform get() = model.viewTransform

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
        AwtTextMeasurer.install()
        // during a transform gesture the keys are its controls (axis locks, confirm, cancel)
        model.onTransformMode = { on -> keyMapper?.mode = if (on) KeyMapper.Mode.Transform else KeyMapper.Mode.Normal }
    }

    /**
     * Recompute and push the status-bar text from current state:
     * active gesture (from mouse controller state), axis-lock
     * flags, and macro-recording flag. Safe to call when
     * [statusBar] is null (headless, tests).
     */
    override fun refreshStatus() {
        statusBar?.setLabel(StatusLine.text(model, keyMapper, stylePicker.isVisible))
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

    override fun startNodeEdit(nodeId: String, screenClick: Vector2?) {
        endNodeEdit()
        nodeEditor.startNodeEdit(nodeId, screenClick)
        // nothing to show when the node is gone
        if (nodeEditor.editedNode != null) {
            nodeEditor.isVisible = true
            nodeEditor.requestFocus()
            parent.repaint()
        }
    }

    override fun endNodeEdit() {
        nodeEditor.endNodeEdit()
        nodeEditor.isVisible = false
        graphCanvas.requestFocus()
        parent.repaint()
    }

    override fun startStylePicker(screen: Vector2?) {
        val screenCoordinatesPosition = screen
        val center = screenCoordinatesPosition.orElse(viewTransform.toWorkspace(lastCursorPosition))
        val ul = center - stylePickerDimensions / 2
        val br = center + stylePickerDimensions / 2
        placeMeAt(stylePicker, ul, br)
        stylePicker.isVisible = true
        stylePicker.requestFocus()
        refreshStatus()
        parent.repaint()
    }

    override fun endStylePicker() {
        stylePicker.isVisible = false
        graphCanvas.requestFocus()
        refreshStatus()
        parent.repaint()
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

    /** Tell the model how big the view is; it positions the view by it. */
    fun syncSize() {
        model.size = Vector2(this.width, this.height)
    }

    // every resize comes through here
    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        syncSize()
    }

    fun boundScreen() = model.boundScreen()

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
 * Startup restores the [Workspace]'s previous session from
 * `$XDG_CONFIG_HOME/sane-graph-edit/workspaces/<dir>/session.properties` (if any):
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
    // Registers all commands before anything else — the mapper
    // and the : bar both need the registry populated.
    val keyMapper: KeyMapper = registerAllCommands().let { applicationKeyMapper() }
    val tabManager: TabManager = TabManager(this)
    val autosave: AutosaveManager = AutosaveManager(tabManager)

    init {
        // Mode changes (Normal ↔ Transform) → refresh the active
        // view's status bar. We can't know which tab the change
        // applied to, so refresh the currently-focused one —
        // modal gestures are scoped to one view anyway.
        keyMapper.modeListener = { tabManager.currentOrNull?.refreshStatus() }

        // Load the user's init file (key mappings, settings).
        keyMapper.loadInitFile(XdgPaths.appConfigDir.resolve("init"))

        // Populate the tab bar from the workspace's persisted
        // session — or, if none exists, browse the workspace so
        // the window has something to show.
        tabManager.bootstrap()

        // If the user passed a file argument, open it in a new tab
        // on top of whatever the session restored.
        initialFile?.let { path ->
            try {
                tabManager.edit(java.nio.file.Paths.get(path))
            } catch (t: Throwable) {
                System.err.println("sane-graph-edit: could not open '$path': ${t.message}")
            }
        }
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
                quit()
            }
        })

        setSize(800, 600)
        setLocationRelativeTo(null)
        refreshTitle()

        validate()
        repaint()
    }

    /**
     * Exit the application: window close button, `:qa`, and `:q` on
     * the last tab. Prompts Save / Discard / Cancel for each dirty
     * tab unless [force] is set (`:qa!`, `:q!`). The session keeps
     * every open tab either way, so the next start restores them.
     */
    fun quit(force: Boolean = false) {
        tabManager.tabs.quitAll(force)
    }

    /** The end, once the tabs have agreed to it and the session is written. */
    fun exit() {
        autosave.stop()
        dispose()
        System.exit(0)
    }
}
