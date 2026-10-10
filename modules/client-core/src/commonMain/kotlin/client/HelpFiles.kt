package client

/**
 * The examples and the documentation they lead to, on disk: the
 * application carries them and writes them out when it starts, so
 * that the built-in help can lead to them wherever it was installed.
 * They are in the user's runtime directory, which does not outlive
 * the login; what is changed there is written over at the next start.
 */
object HelpFiles {
    val dir: Path get() = Fs.runtimeDir.resolve("sane-graph-edit").resolve("help")

    /** The graph that leads to all the others ([FileLink.EXAMPLES]). */
    val index: Path get() = dir.resolve("examples").resolve("index.dot")

    /** Write the files out; one that is there as it should be is left alone. Failing is not worth stopping for. */
    fun install() {
        try {
            HelpFilesContent.files.forEach { (name, text) ->
                val file = dir.resolve(name)
                if (!Fs.exists(file) || Fs.readText(file) != text) {
                    file.getParent()?.let { Fs.createDirectories(it) }
                    Fs.writeText(file, text)
                }
            }
        } catch (t: Throwable) {
            utils.Platform.warn("sane-graph-edit: the examples could not be written to $dir: ${t.message}")
        }
    }
}
