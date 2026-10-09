package client

private external fun require(module: String): dynamic

private val nodePath: dynamic by lazy { require("path") }
private val nodeFs: dynamic by lazy { require("fs") }
private val nodeOs: dynamic by lazy { require("os") }
private external val process: dynamic

actual interface Path {
    actual fun toAbsolutePath(): Path
    actual fun normalize(): Path
    actual fun getParent(): Path?
    actual fun getFileName(): Path?
    actual fun resolve(other: Path): Path
}

/** A path as Node.js understands it. */
private data class NodePath(val text: String) : Path {
    override fun toAbsolutePath(): Path = NodePath(nodePath.resolve(text) as String)
    override fun normalize(): Path = NodePath(nodePath.normalize(text) as String)
    override fun getParent(): Path? = (nodePath.dirname(text) as String).takeIf { it != text && !(it == "." && !text.contains("/")) }?.let { NodePath(it) }
    override fun getFileName(): Path? = (nodePath.basename(text) as String).takeIf { it.isNotEmpty() }?.let { NodePath(it) }
    override fun resolve(other: Path): Path = other.toString().let { NodePath(if (nodePath.isAbsolute(it) as Boolean) it else nodePath.join(text, it) as String) }
    override fun toString(): String = text
}

actual object Fs {
    actual fun path(text: String): Path = NodePath(text)

    actual val home: String get() = nodeOs.homedir() as String

    actual val configDir: Path
        get() {
            val xdg = (process.env.XDG_CONFIG_HOME as String?)?.takeIf { it.isNotBlank() }
            return path(xdg ?: "$home/.config").resolve("sane-graph-edit")
        }

    // as the JVM's: the same session socket whichever of them looks for it
    actual val runtimeDir: Path
        get() {
            val xdg = (process.env.XDG_RUNTIME_DIR as String?)?.takeIf { it.isNotBlank() }
            return path(xdg ?: "${nodeOs.tmpdir()}/runtime-${nodeOs.userInfo().username}")
        }

    actual fun exists(path: Path): Boolean = nodeFs.existsSync(path.toString()) as Boolean

    actual fun isDirectory(path: Path, followLinks: Boolean): Boolean =
        try {
            val stat = if (followLinks) nodeFs.statSync(path.toString()) else nodeFs.lstatSync(path.toString())
            stat.isDirectory() as Boolean
        } catch (_: Throwable) {
            false
        }

    /**
     * What Node throws when a file cannot be read or written is not
     * an [Exception] to Kotlin, so a `catch (e: Exception)` around a
     * file operation would let it through: here it becomes one, as
     * the same failure is on the JVM.
     */
    private fun <T> io(operation: () -> T): T =
        try {
            operation()
        } catch (e: Exception) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(e.message ?: e.toString())
        }

    actual fun list(dir: Path): List<Path> = io { (nodeFs.readdirSync(dir.toString()) as Array<String>).map { dir.resolve(it) } }

    actual fun readText(path: Path): String = io { nodeFs.readFileSync(path.toString(), "utf8") as String }

    // Node replaces bytes that are not UTF-8 by itself
    actual fun readLines(path: Path, upTo: Int): List<String> = readText(path).lineSequence().take(upTo).toList()

    actual fun writeText(path: Path, text: String) {
        io { nodeFs.writeFileSync(path.toString(), text, "utf8") }
    }

    actual fun createDirectories(dir: Path) {
        val options: dynamic = js("({})")
        options.recursive = true
        io { nodeFs.mkdirSync(dir.toString(), options) }
    }
}

/** Not kept between runs here. */
actual object AppState {
    actual var lastOpenDir: Path? = null
    actual var lastSaveDir: Path? = null
}

actual fun sha1Hex(text: String): String = require("crypto").createHash("sha1").update(text, "utf8").digest("hex") as String
