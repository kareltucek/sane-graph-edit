package ui

import client.FilePicker
import client.Path
import javax.swing.SwingUtilities

/**
 * How this application runs the shell command of [client.FilePicker]:
 * in a thread of its own, with the result handed over on the event
 * thread. The command's stderr goes to the terminal the application
 * was started from.
 */
object SwingShellRunner : FilePicker.ShellRunner {
    override fun run(command: String, dir: Path, environment: Map<String, String>, then: (FilePicker.Output) -> Unit) {
        Thread({
            val output = runCommand(command, dir, environment)
            SwingUtilities.invokeLater { then(output) }
        }, "file-picker").apply { isDaemon = true }.start()
    }

    private fun runCommand(command: String, dir: Path, environment: Map<String, String>): FilePicker.Output =
        try {
            val p = ProcessBuilder("sh", "-c", command)
                .directory(dir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .also { it.environment().putAll(environment) }
                .start()
            val lines = FilePicker.lines(p.inputStream.bufferedReader().readText())
            val code = p.waitFor()
            FilePicker.Output(lines, if (code != 0 && lines.isEmpty()) "picker failed (exit $code; its stderr went to the terminal)" else null)
        } catch (e: Exception) {
            FilePicker.Output(emptyList(), "picker failed: ${e.message}")
        }
}
