package utils

/** The few things the model needs that the JVM and JavaScript do differently. */
expect object Platform {
    /** Milliseconds since the epoch. */
    fun nowMillis(): Long

    /** Milliseconds, finer than whole ones, from no time in particular: for how long something takes, cheap to ask often. */
    fun clockMillis(): Double

    /** [d] with exactly [digits] digits after the decimal point. */
    fun fixed(d: Double, digits: Int): String

    /** 0, 1, 2 …: a new number for every call in this process, from any thread. */
    fun nextCount(): Long

    /** A line for whoever watches the process, not for the user: standard error. */
    fun warn(text: String)

    fun readText(path: String): String

    /** Write [text] to the file at [path], creating or replacing it. */
    fun writeText(path: String, text: String)
}
