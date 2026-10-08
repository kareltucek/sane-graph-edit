package ui

import client.*
import Graph
import graph_tools.Node
import graph_tools.Plotter
import utils.Utils.orElse
import utils.Vector2
import java.awt.*



fun createAndShowGUI(initialFile: String? = null, attach: Boolean = false) {
    graph_tools.AwtTextMeasurer.install()
    val socket = client.SessionSocket.pathFor(Workspace.dir)
    if (!attach) {
        Editor.start(socket)
    } else if (!Editor.attach(socket)) {
        System.err.println("sane-graph-edit: --attach: no window is running in ${Workspace.dir}")
        kotlin.system.exitProcess(2)
    }
    val frame = Window("Sane Graph Edit", initialFile)
    // later: opening asks the editor, which can't be done from inside one of its events
    Editor.client?.onShow = { path, here -> EventQueue.invokeLater { frame.tabManager.present(java.nio.file.Paths.get(path), here) } }
    Editor.onOwnerGone = {
        frame.tabManager.currentOrNull?.messageArea?.show(
            "The window that owned this session was closed. This one now works on its own; saving rewrites files without their comments."
        )
    }
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
    val attach: Boolean = false,
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
    var attach = false

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
            "--attach" -> attach = true
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
    return CliArgs(file, execs, stay, help, attach)
}

private const val USAGE = """Usage: sane-graph-edit [options] [file]
       sane-graph-edit ctl <command>   (talk to a running window; see `ctl help`)

Options:
  -e <keys>    Execute a key sequence (e.g. ':export svg foo.svg<Enter>'),
               then exit (unless -u). Repeatable.
  -u           Stay open with a window after executing -e commands.
  --attach     Share the documents of the window already running in this
               directory instead of working independently of it.
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
    graph_tools.AwtTextMeasurer.install()
    Editor.start()

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
    val mapper = applicationKeyMapper()
    mapper.loadInitFile(XdgPaths.appConfigDir.resolve("init"))

    // Construct a minimal headless GraphView (no Window, no
    // TabManager, no AutosaveManager). The view still allocates
    // Swing components under the hood; with java.awt.headless=true
    // those don't try to open a display.
    val view = GraphView(initialGraph = graph, initialFile = resolvedFile?.let { java.nio.file.Paths.get(it) })
    view.keyMapper = mapper
    mapper.activeView = view.model

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
    if (args.firstOrNull() == "ctl") {
        kotlin.system.exitProcess(Ctl.run(args.drop(1)))
    }
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
    EventQueue.invokeLater { createAndShowGUI(cli.file?.let(::expandHome), cli.attach) }
    // TODO: honour -e -u combination (replay keys after window opens).
}