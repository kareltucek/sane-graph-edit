package client

/**
 * Where one text differs from another, line by line: what an edit
 * of a node did to the lines of its file. A node that holds many
 * lines (all of the file) is edited as a whole, and without this the
 * lines of other nodes within it could not tell where lines were
 * put in or taken out.
 */
internal object LineDiff {
    /** Lines [from] up to [to] (from 0, without [to]) of the text before became [lines]. */
    class Hunk(val from: Int, val to: Int, val lines: List<String>)

    /** Above this many line pairs left to compare, the lines between what stayed at the start and the end are one hunk. */
    private const val MOST_PAIRS = 4_000_000

    /** The hunks that make [before] into [after], in their order. */
    fun of(before: List<String>, after: List<String>): List<Hunk> {
        var start = 0
        while (start < before.size && start < after.size && before[start] == after[start]) start++
        var end = 0
        while (end < before.size - start && end < after.size - start && before[before.size - 1 - end] == after[after.size - 1 - end]) end++
        val old = before.subList(start, before.size - end)
        val new = after.subList(start, after.size - end)
        val whole = listOf(Hunk(start, start + old.size, new)).filter { it.from < it.to || it.lines.isNotEmpty() }
        return if (old.size.toLong() * new.size > MOST_PAIRS || old.isEmpty() || new.isEmpty()) whole else common(old, new, start)
    }

    /** The hunks between [old] and [new] around the longest run of lines they have in common, in order; [offset] is where [old] starts. */
    private fun common(old: List<String>, new: List<String>, offset: Int): List<Hunk> {
        val width = new.size + 1
        // how many lines in common the rest of each has, from the end back
        val kept = IntArray((old.size + 1) * width)
        for (i in old.size - 1 downTo 0) {
            for (j in new.size - 1 downTo 0) {
                kept[i * width + j] = if (old[i] == new[j]) kept[(i + 1) * width + j + 1] + 1 else maxOf(kept[(i + 1) * width + j], kept[i * width + j + 1])
            }
        }
        val hunks = mutableListOf<Hunk>()
        var i = 0
        var j = 0
        var from = -1
        var put = mutableListOf<String>()
        val close = {
            if (from >= 0) hunks += Hunk(offset + from, offset + i, put)
            from = -1
            put = mutableListOf()
        }
        while (i < old.size || j < new.size) {
            val same = i < old.size && j < new.size && old[i] == new[j]
            val dropped = !same && i < old.size && (j >= new.size || kept[(i + 1) * width + j] >= kept[i * width + j + 1])
            if (!same && from < 0) from = i
            when {
                same -> {
                    close()
                    i++
                    j++
                }
                dropped -> i++
                else -> put += new[j++]
            }
        }
        close()
        return hunks
    }
}
