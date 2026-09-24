package parser_dot

import Graph
import java.util.*

sealed class LogAction()

data class LogGraphStart(val id: String) : LogAction()
data class LogGraphEnd(val id: String) : LogAction()
data class LogNodeDefined(val id: String) : LogAction()
data class LogEdgeDefined(val id: String) : LogAction()
/** A graph-level attribute statement; the value lives in [Graph.attributes]. */
data class LogGraphAttribute(val key: String) : LogAction()
data class LogComment(val value: String) : LogAction()
data class LogSectionBreak(val mustBePresent: String) : LogAction()

class ParseLog(
    val actions: Queue<LogAction> = LinkedList(),
) {
    fun add(a: LogAction) {
        actions.add(a)
    }

    companion object {
        fun dummy(g: Graph): ParseLog {
            return ParseLog()
                .also { it.add(LogGraphStart(g.id)) }
                .also { it.add(LogGraphEnd(g.id)) }
        }
    }

}