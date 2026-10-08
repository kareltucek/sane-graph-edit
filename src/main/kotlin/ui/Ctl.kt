package ui

import DotGraphLoader
import client.EditorException
import client.SessionSocket
import client.SocketConnection
import sync.Attached
import sync.Event
import sync.Failed
import sync.ListDocuments
import sync.OpenFile
import sync.Redo
import sync.Rejected
import sync.Reload
import sync.Request
import sync.Save
import sync.Submit
import sync.Undo
import sync.Wire
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `sane-graph-edit ctl …`: one request to a running session, for
 * scripts and agents. Talks to the session's socket and prints the
 * editor's answer as JSON (DOT for `dot`).
 */
object Ctl {
    const val USAGE = """Usage: sane-graph-edit ctl [--workspace <dir> | --socket <path>] <command>

Talks to the editor session of a running sane-graph-edit window.
The session is found by its workspace (the directory the window was
started in; default: the current directory).

Commands:
  socket             Print the path of the session's socket.
  documents          List the session's open documents.
  state <file>       The document's content: fields per node / edge id, and its revision.
  dot <file>         The document's content as DOT, unsaved changes included.
  patch <file> [--save]
                     Apply the patch (JSON) read from standard input. Refused when the
                     file is not open in a window, unless --save writes it right away.
  undo <file>        Undo / redo the document's last change (anybody's).
  redo <file>
  save <file>        Write the document to its file.
  reload <file>      Replace the document's content with the file's.

A patch:
  {"addNodes":    {"<new id>": {"text": "Label", "pos": "10.0,20.0"}},
   "setNodes":    {"<id>": {"text": "New label", "bg": "#ffeeaa", "attr:shape": "oval"}},
   "removeNodes": ["<id>"],
   "addEdges":    {"<new id>": {"src": "<id>", "dst": "<id>"}},
   "removeEdges": ["<id>"]}
Node fields: pos, text, bg, fg, scale, name (the DOT id), marks, hide, attr:<DOT attribute>.
A field set to null is removed. Ids of new things are yours to choose; make them unique.

Exit status: 0 done, 1 refused or failed, 2 no session or bad usage."""

    fun run(args: List<String>): Int {
        val socketAt = args.indexOf("--socket")
        val workspaceAt = args.indexOf("--workspace")
        val options = listOf(socketAt, workspaceAt).filter { it >= 0 }.flatMap { listOf(it, it + 1) }.toSet()
        val rest = args.filterIndexed { i, _ -> i !in options }
        val socket = args.getOrNull(socketAt + 1)?.takeIf { socketAt >= 0 }?.let { Paths.get(it) }
            ?: SessionSocket.pathFor(args.getOrNull(workspaceAt + 1)?.takeIf { workspaceAt >= 0 }?.let { Paths.get(it) } ?: Paths.get(""))
        val command = rest.firstOrNull()
        val file = rest.getOrNull(1)?.let { Paths.get(expandHome(it)).toAbsolutePath().normalize().toString() }
        val needsFile = command in setOf("state", "dot", "patch", "undo", "redo", "save", "reload")
        return when {
            command == null || command == "help" || (needsFile && file == null) -> usage()
            command == "socket" -> 0.also { println(socket) }
            command !in setOf("documents") && !needsFile -> usage()
            else -> talk(socket, command, file, save = "--save" in rest)
        }
    }

    private fun usage(): Int {
        System.err.println(USAGE)
        return 2
    }

    private fun talk(socket: Path, command: String, file: String?, save: Boolean): Int {
        val connection = try {
            SocketConnection(socket) { it() }
        } catch (e: Exception) {
            System.err.println("sane-graph-edit: no session at $socket (${e.message})")
            return 2
        }
        return try {
            if (command == "documents") report(connection.call(ListDocuments)) else onDocument(connection, command, file!!, save)
        } catch (e: EditorException) {
            System.err.println("sane-graph-edit: ${e.message}")
            1
        } finally {
            connection.close()
        }
    }

    private fun onDocument(connection: SocketConnection, command: String, file: String, save: Boolean): Int {
        val answer = connection.call(OpenFile(file))
        val attached = answer as? Attached ?: return report(answer).coerceAtLeast(1)
        val doc = attached.info.document
        // we are one of the clients; with nobody else the document is dropped as we leave
        val unwatched = attached.info.clients <= 1
        return when (command) {
            "state" -> report(attached)
            "dot" -> 0.also { println(DotGraphLoader.serialize(attached.state.toGraph())) }
            "patch" -> if (unwatched && !save) {
                System.err.println("sane-graph-edit: $file is not open in the session; open it there, or use --save")
                1
            } else {
                val patch = Wire.decodePatch(generateSequence(::readLine).joinToString("\n"))
                val result = report(connection.call(Submit(doc, "ctl", attached.revision, patch)))
                if (result == 0 && save) request(connection, Save(doc)) else result
            }
            "undo" -> request(connection, Undo(doc))
            "redo" -> request(connection, Redo(doc))
            "save" -> request(connection, Save(doc))
            else -> request(connection, Reload(doc))
        }
    }

    private fun request(connection: SocketConnection, request: Request): Int = report(connection.call(request))

    /** Print the editor's answer; nothing to print is fine too (e.g. nothing to undo). */
    private fun report(event: Event?): Int {
        event?.let { println(Wire.encode(it)) }
        return if (event is Failed || event is Rejected) 1 else 0
    }
}
