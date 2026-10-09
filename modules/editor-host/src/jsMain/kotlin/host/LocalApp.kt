@file:OptIn(ExperimentalJsExport::class)

package host

import client.GridMetrics
import client.JsApp
import client.SessionSocket
import client.Workspace
import client.startApp

/**
 * A window with an editor of its own, in the page's process: no
 * other window is needed. What it shows and [page] are as for
 * `client.openApp`.
 *
 * Other processes (`ctl`, a window started with `--attach`) can
 * join its session through the socket of the directory it was
 * started in, unless another process serves that one already:
 * then this window is simply on its own.
 */
@JsExport
fun openLocalApp(path: String?, grid: GridMetrics, page: dynamic): JsApp {
    val host = EditorHost()
    // the window's own client first: the first one is the session's owner
    val app = startApp(host.connect(), path, grid, page)
    SessionServer.start(host.hub, SessionSocket.pathFor(Workspace.dir)) { }
    return app
}
