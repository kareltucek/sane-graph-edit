package graph_tools

import utils.Platform

/**
 * How long the phases of the settling's steps took, level by level, from [reset] to [report]: `o` tells it when it is
 * let go. A phase is timed once a step, not once a node: it costs nothing to keep.
 */
object LayoutTimes {
    enum class Part(val title: String) {
        /** Which nodes go first. */
        Order("order"),
        EdgePoints("edge points"),
        Index("index"),
        /** The nodes one by one: those near each, the pushes and the pulls. */
        Springs("springs"),
        Apply("apply"),
        Reglue("reglue"),
        Carry("carry down"),
    }

    /** The level the steps are of now ([Multilevel.Run]); 0 for a run of one level. */
    var level = 0

    /** Of each level: milliseconds of each part, and of the whole steps; how many steps; how many nodes it has. */
    private class Of(var nodes: Int) {
        val parts = DoubleArray(Part.entries.size)
        var steps = 0
        var ms = 0.0
    }

    private val levels = mutableMapOf<Int, Of>()

    /** How many levels were made, and how long that took; none for a run of one level. */
    var made = 0
    var built = 0.0

    private fun of(level: Int) = levels.getOrPut(level) { Of(0) }

    inline fun <T> time(part: Part, f: () -> T): T {
        val start = Platform.clockMillis()
        val r = f()
        add(part, Platform.clockMillis() - start)
        return r
    }

    fun add(part: Part, took: Double) {
        of(level).parts[part.ordinal] += took
    }

    /** A step of the level of [nodes] nodes took [took] milliseconds. */
    fun step(nodes: Int, took: Double) {
        of(level).let {
            it.nodes = nodes
            it.steps += 1
            it.ms += took
        }
    }

    fun reset() {
        levels.clear()
        level = 0
        made = 0
        built = 0.0
    }

    private fun ms(d: Double) = "${Platform.fixed(d, if (d < 1) 2 else if (d < 10) 1 else 0)} ms"

    /** Each level, the top first: its steps, and what a step of it took on average, whole and by part. */
    fun report(): String =
        (listOf("o: " + (if (made > 0) "$made levels made in ${ms(built)}" else "one level") + "; a step on average:") +
            levels.entries.sortedByDescending { it.key }.map { (i, l) ->
                val per = { d: Double -> ms(d / l.steps.coerceAtLeast(1)) }
                val parts = Part.entries.filter { l.parts[it.ordinal] > 0 }.joinToString(", ") { "${it.title} ${per(l.parts[it.ordinal])}" }
                "  level $i: ${l.nodes} nodes, ${l.steps} steps, ${per(l.ms)}: $parts"
            }).joinToString("\n")
}
