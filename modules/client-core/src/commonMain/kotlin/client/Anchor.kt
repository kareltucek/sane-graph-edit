package client

/**
 * Lines of a text file named as `git log -L` names them:
 * `<start>,<end>`, either of them a line number or a `/<pattern>/`,
 * the end also `+<count>` lines. At least one of them is a pattern;
 * two numbers are written `<from>-<to>` ([FileLink.Lines]).
 *
 * A pattern is a regular expression looked for in each line: the
 * start's from the first line of the file, the end's from the line
 * after the start. A `/` in it is written `\/`. A number is a line
 * as the text is when the lines are looked for. Like a section
 * ([Sections]), the lines are looked for again whenever the file is
 * read, so they stay right when something else changed it.
 */
data class Anchor(val start: Bound, val end: Bound) {
    sealed interface Bound {
        data class Line(val number: Int) : Bound {
            override fun toString(): String = "$number"
        }

        data class Pattern(val source: String) : Bound {
            val regex: Regex by lazy { Regex(literalBraces(source)) }

            override fun toString(): String = "/${source.replace("/", "\\/")}/"
        }

        /** Only as the end: so many lines from the start on. */
        data class Count(val lines: Int) : Bound {
            override fun toString(): String = "+$lines"
        }
    }

    override fun toString(): String = "$start,$end"

    /** The lines of [lines] (the text of a file, by line) this names; null when a pattern is not found. */
    fun find(lines: List<String>): FileLink.Lines? {
        val first = when (start) {
            is Bound.Line -> start.number.takeIf { it in 1..lines.size }
            is Bound.Pattern -> lines.indexOfFirst { start.regex.containsMatchIn(it) }.takeIf { it >= 0 }?.plus(1)
            is Bound.Count -> null
        }
        val last = first?.let { from ->
            when (end) {
                is Bound.Line -> end.number.coerceAtMost(lines.size).takeIf { it >= from }
                is Bound.Pattern -> (from + 1..lines.size).firstOrNull { end.regex.containsMatchIn(lines[it - 1]) }
                is Bound.Count -> (from + end.lines - 1).coerceAtMost(lines.size).takeIf { end.lines >= 1 }
            }
        }
        return first?.let { from -> last?.let { FileLink.Lines(from, it) } }
    }

    /** The lines of the file [path] this names; null when a pattern is not found or the file cannot be read. */
    fun find(path: Path): FileLink.Lines? =
        try {
            find(Fs.readLines(path, Int.MAX_VALUE))
        } catch (e: Exception) {
            null
        }

    companion object {
        private val quantifier = Regex("\\{[0-9]+(,[0-9]*)?\\}")

        /**
         * [source] with its braces that do not count (`{2}`, `{2,3}`)
         * escaped: a brace of a block (`^}`, `{$`) is meant as itself,
         * and the JVM and JavaScript disagree on which bare ones they
         * take.
         */
        internal fun literalBraces(source: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < source.length) {
                val c = source[i]
                val counts = if (c == '{') quantifier.find(source, i)?.takeIf { it.range.first == i } else null
                when {
                    c == '\\' && i + 1 < source.length -> out.append(c).append(source[i + 1])
                    counts != null -> out.append(counts.value)
                    c == '{' || c == '}' -> out.append('\\').append(c)
                    else -> out.append(c)
                }
                i += if (c == '\\' && i + 1 < source.length) 2 else counts?.value?.length ?: 1
            }
            return out.toString()
        }

        private val number = Regex("[0-9]+")
        private val count = Regex("\\+([0-9]+)")

        /** The anchor [text] writes, or null when it is not one (no pattern in it, a pattern that does not compile). */
        fun parse(text: String): Anchor? {
            val start = bound(text, 0)
            val end = start?.takeIf { (_, after) -> text.getOrNull(after) == ',' }?.let { (_, after) -> bound(text, after + 1) }
            val anchor = start?.let { s -> end?.takeIf { it.second == text.length }?.let { e -> Anchor(s.first, e.first) } }
            val valid = anchor != null && anchor.start !is Bound.Count && (anchor.start is Bound.Pattern || anchor.end is Bound.Pattern) &&
                listOf(anchor.start, anchor.end).filterIsInstance<Bound.Pattern>().all { runCatching { it.regex }.isSuccess }
            return anchor?.takeIf { valid }
        }

        /** The bound written at [at] of [text], and where what follows it starts. */
        private fun bound(text: String, at: Int): Pair<Bound, Int>? =
            when (text.getOrNull(at)) {
                '/' -> pattern(text, at + 1)
                '+' -> count.at(text, at)?.let { Bound.Count(it.groupValues[1].toInt()) to it.range.last + 1 }
                else -> number.at(text, at)?.let { Bound.Line(it.value.toInt()) to it.range.last + 1 }
            }

        private fun Regex.at(text: String, at: Int): MatchResult? = find(text, at)?.takeIf { it.range.first == at }

        /** The pattern starting at [from] of [text], up to the `/` that ends it; `\/` is a `/` of the pattern. */
        private fun pattern(text: String, from: Int): Pair<Bound, Int>? {
            val source = StringBuilder()
            var i = from
            var closed = false
            while (i < text.length && !closed) {
                val escaped = text[i] == '\\' && text.getOrNull(i + 1) == '/'
                closed = text[i] == '/'
                if (escaped) source.append('/') else if (!closed) source.append(text[i])
                i += if (escaped) 2 else 1
            }
            return if (closed && source.isNotEmpty()) Bound.Pattern(source.toString()) to i else null
        }
    }
}
