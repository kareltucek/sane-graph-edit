package ui

import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.SwingUtilities

/**
 * `pick-file` (`<C-p>`): delegate choosing a file to a shell command,
 * `:set picker=<command>` (see `:help init`).
 *
 * The command runs with `sh -c` in the background, with `SGE_DIR` (the
 * current file's directory, the browsed directory, or the working
 * directory) and `SGE_FILE` (the current file, or empty) in its
 * environment. It prints what to open on stdout, one `path[:nodeid]`
 * per line, relative paths relative to `SGE_DIR`; nothing = cancelled.
 * The first line opens in the tab (like `gf`, so `Ctrl+O` goes back),
 * further ones (`fzf -m`) in new tabs. Opening a terminal is the
 * command's job; the default does it with xterm + find + fzf.
 */
object FilePicker {
    /**
     * xterm with fzf over the `.dot` files below `$SGE_DIR`. A terminal's
     * stdout isn't fzf's, so the choice travels through a temp file;
     * `xterm -e` blocks until fzf exits. The window class `sge-picker`
     * is for window-manager rules (i3: `for_window [class="sge-picker"]
     * floating enable`).
     */
    const val DEFAULT_COMMAND =
        "out=\$(mktemp); " +
            "xterm -class sge-picker -e sh -c 'cd \"\$SGE_DIR\" && find . -name \"*.dot\" | fzf > \"\$1\"' sh \"\$out\"; " +
            "cat \"\$out\"; rm -f \"\$out\""

    var command: String = DEFAULT_COMMAND

    /** One picker at a time; a second `<C-p>` while it's open does nothing. */
    @Volatile
    private var running = false

    fun run(gv: GraphView) {
        val dir = gv.browseDir
            ?: gv.currentFile?.toAbsolutePath()?.parent
            ?: Paths.get("").toAbsolutePath()
        val file = gv.currentFile?.toAbsolutePath()?.toString().orEmpty()
        val cmd = command
        if (!running) {
            running = true
            Thread({
                val result = runCommand(cmd, dir, file)
                SwingUtilities.invokeLater {
                    running = false
                    open(gv, dir, result)
                }
            }, "file-picker").apply { isDaemon = true }.start()
        }
    }

    /** Picked lines, or the reason there are none. */
    private class Result(val lines: List<String>, val error: String?)

    private fun runCommand(cmd: String, dir: Path, file: String): Result =
        try {
            val p = ProcessBuilder("sh", "-c", cmd)
                .directory(dir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .also { it.environment()["SGE_DIR"] = dir.toString() }
                .also { it.environment()["SGE_FILE"] = file }
                .start()
            val lines = p.inputStream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val code = p.waitFor()
            Result(lines, if (code != 0 && lines.isEmpty()) "picker failed (exit $code; its stderr went to the terminal)" else null)
        } catch (e: Exception) {
            Result(emptyList(), "picker failed: ${e.message}")
        }

    private fun open(gv: GraphView, dir: Path, result: Result) {
        val targets = result.lines.map { FileLink.resolveIn(it, dir) }
        result.error?.let { gv.messageArea?.show(it) }
        targets.firstOrNull()?.let { gv.openTarget(it) }
        targets.drop(1).forEach { gv.openTargetInTab(it) }
    }
}
