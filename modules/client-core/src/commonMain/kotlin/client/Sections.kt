package client

/**
 * Parts of a text file that have a name, so that a link can name
 * one (`<file>#<section>`) and still be right after lines were added
 * above it. For now those are the sections of a markdown file: a
 * heading and what is under it, up to the next heading of its level
 * or above.
 */
object Sections {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val fence = Regex("^\\s*(```|~~~)")

    /** A heading's line (from 1), its level and its text. */
    private data class Heading(val line: Int, val level: Int, val text: String)

    /** The lines of the section [name] of [path], or null when there is none or the file cannot be read. */
    fun find(path: Path, name: String): FileLink.Lines? =
        try {
            find(Fs.readLines(path, Int.MAX_VALUE), name)
        } catch (e: Exception) {
            null
        }

    /**
     * The section of [lines] whose heading is [name], or failing that
     * starts with it, or failing that has it somewhere; the first of
     * such. Letter case does not matter. Blank lines at its end are
     * not part of it.
     */
    fun find(lines: List<String>, name: String): FileLink.Lines? {
        val headings = headings(lines)
        val wanted = name.trim()
        val tests = listOf<(String) -> Boolean>(
            { it.equals(wanted, ignoreCase = true) },
            { it.startsWith(wanted, ignoreCase = true) },
            { it.contains(wanted, ignoreCase = true) },
        )
        return tests.firstNotNullOfOrNull { test -> headings.firstOrNull { test(it.text) } }?.let { found ->
            val next = headings.firstOrNull { it.line > found.line && it.level <= found.level }?.line ?: (lines.size + 1)
            val last = (found.line until next).lastOrNull { lines[it - 1].isNotBlank() } ?: found.line
            FileLink.Lines(found.line, last)
        }
    }

    /** The headings of [lines]; a line in a fenced block is not one. */
    private fun headings(lines: List<String>): List<Heading> {
        var fenced = false
        return lines.mapIndexedNotNull { index, line ->
            if (fence.containsMatchIn(line)) fenced = !fenced
            heading.matchEntire(line)?.takeIf { !fenced }?.let { Heading(index + 1, it.groupValues[1].length, it.groupValues[2]) }
        }
    }
}
