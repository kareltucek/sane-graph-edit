package graph_tools

import utils.Platform
import kotlin.random.Random

/**
 * Identifiers of nodes and edges, unique across the clients of a
 * session: a per-process random prefix and a counter. They address
 * things in patches; they are not the DOT names.
 */
object Ids {
    private val prefix = (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
    fun new(): String = "$prefix-${Platform.nextCount()}"
}
