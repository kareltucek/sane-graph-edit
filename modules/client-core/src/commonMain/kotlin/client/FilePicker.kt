package client

/**
 * `pick-file` (`<C-p>`): delegate choosing a file to a shell command,
 * `:set picker=<command>` (see `:help init`).
 *
 * The command runs with `sh -c` in the background, with `SGE_DIR` (the
 * [Workspace] directory) and `SGE_FILE` (the current file, or empty) in its
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

    /** What a shell command printed, line by line, or the reason there is nothing. */
    class Output(val lines: List<String>, val error: String?)

    /**
     * Runs `sh -c <command>` in the directory [dir] with [environment]
     * added to its own, without input, and without holding the client
     * up; [then] gets its output on the client's thread. The
     * application provides it: starting a process is not the same
     * everywhere.
     */
    fun interface ShellRunner {
        fun run(command: String, dir: Path, environment: Map<String, String>, then: (Output) -> Unit)
    }

    var runner: ShellRunner? = null

    /** One picker at a time; a second `<C-p>` while it's open does nothing. */
    private var running = false

    fun run(view: EditorView) {
        val dir = Workspace.dir
        val file = view.content.currentFile?.toAbsolutePath()?.toString().orEmpty()
        val shell = runner
        when {
            shell == null -> view.shell.message("pick-file: this application cannot run a picker")
            !running -> {
                running = true
                shell.run(command, dir, mapOf("SGE_DIR" to dir.toString(), "SGE_FILE" to file)) { output ->
                    running = false
                    open(view, dir, output)
                }
            }
        }
    }

    /** The lines of what a picker printed: trimmed, the empty ones left out. */
    fun lines(printed: String): List<String> = printed.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun open(view: EditorView, dir: Path, output: Output) {
        val targets = output.lines.map { FileLink.resolveIn(it, dir) }
        output.error?.let { view.shell.message(it) }
        targets.firstOrNull()?.let { view.content.openTarget(it) }
        targets.drop(1).forEach { view.content.openTargetInTab(it) }
    }
}
