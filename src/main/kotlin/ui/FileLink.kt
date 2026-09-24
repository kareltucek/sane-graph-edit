package ui

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The `link` node attribute (see docs/file-format.md): a filesystem
 * path, followed with `gf` ([GraphView.followFileLink]).
 */
object FileLink {
    const val ATTRIBUTE = "link"

    /**
     * Resolve [link] to an absolute path: `~/` is the home
     * directory, a relative path is relative to the directory of
     * [containingFile] (the file the node lives in), or to the
     * working directory when the tab has no file.
     */
    fun resolve(link: String, containingFile: Path?): Path {
        val expanded = if (link == "~" || link.startsWith("~/")) {
            System.getProperty("user.home") + link.removePrefix("~")
        } else {
            link
        }
        val base = containingFile?.toAbsolutePath()?.parent ?: Paths.get("").toAbsolutePath()
        return base.resolve(expanded).normalize()
    }
}
