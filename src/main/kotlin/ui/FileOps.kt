package ui

import java.awt.Component
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Thin wrapper around [JFileChooser] for the editor's three file
 * dialogs (open, save, save-as). Centralised so the title-bar and
 * tab-manager code don't each reimplement extension filters and
 * last-directory memory.
 *
 * All methods return a [Path] on approve and `null` on cancel — no
 * exception path for user cancellation, which the callers need to
 * treat as a normal flow.
 */
object FileOps {
    /** Filter accepting `*.dot` files and "all files" as a fallback. */
    private fun dotFilter() = FileNameExtensionFilter("Graphviz DOT (*.dot)", "dot")
    private fun svgFilter() = FileNameExtensionFilter("SVG image (*.svg)", "svg")

    fun openDialog(parent: Component): Path? {
        val chooser = JFileChooser()
        AppState.lastOpenDir?.toFile()
            ?.takeIf { it.isDirectory }
            ?.let { chooser.currentDirectory = it }
        chooser.dialogTitle = "Open graph"
        chooser.fileFilter = dotFilter()
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null
        val path = chooser.selectedFile.toPath()
        AppState.lastOpenDir = path.parent
        return path
    }

    /**
     * Show a Save-As dialog. [defaultName] is a suggested filename
     * (usually the current tab's filename, or `"untitled.dot"` for a
     * fresh tab). Appends `.dot` when the user types a name without
     * an extension.
     */
    fun saveDialog(parent: Component, defaultName: String?): Path? {
        val chooser = JFileChooser()
        AppState.lastSaveDir?.toFile()
            ?.takeIf { it.isDirectory }
            ?.let { chooser.currentDirectory = it }
        chooser.dialogTitle = "Save graph"
        chooser.fileFilter = dotFilter()
        defaultName?.let {
            chooser.selectedFile = chooser.currentDirectory.resolve(it)
        }
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null
        var path = chooser.selectedFile.toPath()
        // Matches the convention in VS Code, Inkscape, etc. — if the
        // user types "foo" with the DOT filter active, save as
        // "foo.dot" without asking.
        if (!path.fileName.toString().lowercase().endsWith(".dot")) {
            path = path.resolveSibling(path.fileName.toString() + ".dot")
        }
        AppState.lastSaveDir = path.parent
        return path
    }

    /**
     * Show an "Export SVG" save dialog. [defaultName] is typically
     * the current file's basename with `.svg` instead of `.dot`.
     * Appends `.svg` when the user types a name without an
     * extension.
     */
    fun exportSvgDialog(parent: Component, defaultName: String?): Path? {
        val chooser = JFileChooser()
        AppState.lastSaveDir?.toFile()
            ?.takeIf { it.isDirectory }
            ?.let { chooser.currentDirectory = it }
        chooser.dialogTitle = "Export SVG"
        chooser.fileFilter = svgFilter()
        defaultName?.let {
            chooser.selectedFile = chooser.currentDirectory.resolve(it)
        }
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null
        var path = chooser.selectedFile.toPath()
        if (!path.fileName.toString().lowercase().endsWith(".svg")) {
            path = path.resolveSibling(path.fileName.toString() + ".svg")
        }
        AppState.lastSaveDir = path.parent
        return path
    }
}
