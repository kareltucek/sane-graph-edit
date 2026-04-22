package ui

import Graph
import graph_tools.Node
import graph_tools.Plotter
import utils.Utils.orElse
import utils.Vector2
import java.awt.*


object Clicker {
    fun Vector2.isInSquare(lt: Vector2, br: Vector2, tolerance: Double): Boolean {
        val xMatches = lt.x - tolerance < this.x && this.x < br.x + tolerance
        val yMatches = lt.y - tolerance < this.y && this.y < br.y + tolerance
        return xMatches && yMatches
    }

    fun selectClickedNode(g: Graph, clickCoordinates: Vector2): MutableSet<Node> {
        return g.nodes
            .filter { it.isVisible }
            .filter {
                it.cache?.shapeBounds?.let { bounds ->
                    clickCoordinates.isInSquare(
                        it.position - bounds / 2,
                        it.position + bounds / 2,
                        10.0 / Plotter.t.scaleX.toDouble()
                    )
                }.orElse(false)
            }
            .minByOrNull { (it.position - clickCoordinates).lengthSquared() }
            ?.let { mutableSetOf(it) }
            ?: mutableSetOf()
    }
}

fun createAndShowGUI(initialFile: String? = null) {
    val frame = Window("Sane Graph Edit", initialFile)
    frame.isVisible = true
}

/**
 * Parsed command-line arguments.
 */
data class CliArgs(
    val file: String? = null,
    val execs: List<String> = emptyList(),
    val stayInteractive: Boolean = false,
    val help: Boolean = false,
)

/** Expand a leading `~/` to the user's home directory. */
fun expandHome(path: String): String = when {
    path == "~" -> System.getProperty("user.home")
    path.startsWith("~/") -> System.getProperty("user.home") + path.substring(1)
    else -> path
}

/**
 * Parse argv into a [CliArgs]. Hand-rolled, no dependencies.
 * Recognises:
 *   -e <keys>    execute a key sequence (repeatable)
 *   -u           stay open after -e
 *   -h, --help   print usage and exit
 *   [file]       positional file to load
 */
fun parseArgs(args: Array<String>): CliArgs {
    var file: String? = null
    val execs = mutableListOf<String>()
    var stay = false
    var help = false

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-e" -> {
                i++
                if (i >= args.size) {
                    System.err.println("sane-graph-edit: -e requires an argument")
                    help = true
                } else {
                    execs.add(args[i])
                }
            }
            "-u" -> stay = true
            "-h", "--help" -> help = true
            else -> {
                if (a.startsWith("-")) {
                    System.err.println("sane-graph-edit: unknown option '$a'")
                    help = true
                } else if (file == null) {
                    file = a
                } else {
                    System.err.println("sane-graph-edit: extra positional argument '$a'")
                    help = true
                }
            }
        }
        i++
    }
    return CliArgs(file, execs, stay, help)
}

private const val USAGE = """Usage: sane-graph-edit [options] [file]

Options:
  -e <keys>    Execute a key sequence (e.g. ':export foo.svg<Enter>'),
               then exit (unless -u). Repeatable.
  -u           Stay open with a window after executing -e commands.
  -h, --help   Print this help and exit.

File:
  Positional argument. If omitted, the editor starts with an
  empty graph (interactive) or does nothing (headless)."""

/**
 * Run the editor in headless mode: no window, no session, no
 * autosave. Loads the file, executes each key sequence in order,
 * then returns. On any error prints to stderr and returns a
 * non-zero exit code.
 */
fun runHeadless(file: String?, execs: List<String>): Int {
    System.setProperty("java.awt.headless", "true")

    val resolvedFile = file?.let(::expandHome)
    val graph = if (resolvedFile != null) {
        try {
            DotGraphLoader.loadFromFile(resolvedFile)
        } catch (t: Throwable) {
            System.err.println("sane-graph-edit: could not open '$resolvedFile': ${t.message}")
            return 1
        }
    } else {
        Graph()
    }

    // Register commands + build the mapper, same as Window does.
    registerAllCommands()
    val mapper = KeyMapper(KeyMapper.defaultBindings())
    mapper.commandExecutor = { cmd, gv -> CommandRegistry.execute(cmd, gv) }
    mapper.markSetter = { gv, letter -> GraphKeyListener.impl.setMark(gv, letter) }
    mapper.markRecaller = { gv, letter -> GraphKeyListener.impl.recallMark(gv, letter) }
    mapper.loadInitFile(XdgPaths.appConfigDir.resolve("init"))

    // Construct a minimal headless GraphView (no Window, no
    // TabManager, no AutosaveManager). The view still allocates
    // Swing components under the hood; with java.awt.headless=true
    // those don't try to open a display.
    val view = GraphView(initialGraph = graph, initialFile = resolvedFile?.let { java.nio.file.Paths.get(it) })
    view.keyMapper = mapper
    mapper.activeView = view

    // Interactive mode flushes node/edge cache recomputation every
    // paint frame (GraphCanvas.doDrawing → Graph.recompute). In
    // headless there's no paint loop, so we must flush manually —
    // otherwise commands that read shapeBounds (notably the layout
    // optimiser) see zero-sized nodes and produce a different
    // layout than an interactive run would.
    view.g.ensureCachesFresh()

    // Feed each -e argument through the mapper.
    for (exec in execs) {
        val tokens = KeyNotation.tokenize(exec)
        for (tok in tokens) {
            mapper.feedKey(tok)
            view.g.ensureCachesFresh()
        }
        // Reset pending state between -e args — each -e should
        // start with a clean slate.
        mapper.reset()
    }

    return 0
}

fun main(args: Array<String>) {
    val cli = parseArgs(args)

    if (cli.help) {
        println(USAGE)
        return
    }

    if (cli.execs.isNotEmpty() && !cli.stayInteractive) {
        // Headless: run and exit.
        val code = runHeadless(cli.file, cli.execs)
        kotlin.system.exitProcess(code)
    }

    // Interactive path (possibly with initial -e commands to
    // replay after the window is up).
    EventQueue.invokeLater { createAndShowGUI(cli.file?.let(::expandHome)) }
    // TODO: honour -e -u combination (replay keys after window opens).
}