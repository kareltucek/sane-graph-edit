package client

/**
 * A file-system path. On the JVM this is `Path`
 * itself, so paths pass between the client and JVM code as they
 * are; the members are the ones the client uses.
 */
expect interface Path {
    fun toAbsolutePath(): Path
    fun normalize(): Path
    fun getParent(): Path?
    fun getFileName(): Path?
    fun resolve(other: Path): Path
}

/** [other] below this path, or [other] itself when it is absolute. */
fun Path.resolve(other: String): Path = resolve(Fs.path(other))

/** The last element of the path as text; empty for a root. */
val Path.name: String get() = getFileName()?.toString().orEmpty()

/** The file system and the user's environment, as the client needs them. */
expect object Fs {
    fun path(text: String): Path

    /** The user's home directory. */
    val home: String

    /** Where the application keeps its configuration (the `init` file). */
    val configDir: Path

    fun exists(path: Path): Boolean

    /** With [followLinks] false a link to a directory is not one. */
    fun isDirectory(path: Path, followLinks: Boolean = true): Boolean

    /** What is in [dir]; fails when it can't be read. */
    fun list(dir: Path): List<Path>

    fun readText(path: Path): String

    /** The first [upTo] lines of [path]; bytes that are not UTF-8 text become `�`. */
    fun readLines(path: Path, upTo: Int): List<String>

    /** Write [text] to [path], creating or replacing the file. */
    fun writeText(path: Path, text: String)
}

/**
 * Small things remembered between runs: the directories the open
 * and save dialogs were last in. Setting null changes nothing.
 */
expect object AppState {
    var lastOpenDir: Path?
    var lastSaveDir: Path?
}
