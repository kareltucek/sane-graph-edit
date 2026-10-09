@file:OptIn(ExperimentalJsExport::class)

package host

import client.GridMetrics
import client.JsApp
import client.startApp

/**
 * A window with an editor of its own, in the page's process: no
 * other window is needed. What it shows and [page] are as for
 * `client.openApp`.
 */
@JsExport
fun openLocalApp(path: String?, grid: GridMetrics, page: dynamic): JsApp = startApp(EditorHost().connect(), path, grid, page)
