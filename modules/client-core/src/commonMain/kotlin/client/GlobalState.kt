package client

/**
 * What is kept for the user whatever the workspace, in a Java
 * properties file ([PropertiesText]) that every client reads and
 * writes. Best-effort as [Session] is: a file that cannot be read
 * is taken to be empty, and one that cannot be written is left.
 */
class GlobalState(private val file: Path = Fs.configDir.resolve("global.properties")) {

    /** The zoom a maximized node (a terminal or an editor) is shown at; null before one was kept. */
    var maximizedZoom: Double?
        get() = read()[MAXIMIZED_ZOOM]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        set(value) = write(MAXIMIZED_ZOOM, value?.toString())

    private fun read(): Map<String, String> =
        try {
            if (Fs.exists(file)) PropertiesText.decode(Fs.readText(file)) else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

    /** Set [key] to [value], or remove it for null; the rest of the file stays as it is. */
    private fun write(key: String, value: String?) {
        try {
            file.getParent()?.let { Fs.createDirectories(it) }
            val props = LinkedHashMap(read())
            if (value == null) props.remove(key) else props[key] = value
            Fs.writeText(file, PropertiesText.encode(props, "sane-graph-edit"))
        } catch (_: Exception) {
            // best-effort
        }
    }

    companion object {
        private const val MAXIMIZED_ZOOM = "maximizedZoom"
    }
}
