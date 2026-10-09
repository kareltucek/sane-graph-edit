@file:OptIn(ExperimentalJsExport::class)

package host

import client.GridMetrics
import client.JsApp
import client.startApp

/**
 * A window with an editor of its own, in the page's process: no
 * other window is needed. It shows [path] in a first tab, or
 * without one the directory it was started in. [page] is as for
 * `client.openApp`.
 */
@JsExport
fun openLocalApp(path: String?, grid: GridMetrics, page: dynamic): JsApp = startApp(EditorHost().connect(), path, grid, page)
