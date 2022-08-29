package dotparser

import Lexer
import Graph
import Utils.orElse
import java.util.*

object Parser {
    interface LogAction {}
    data class LogGraphStart(val id: String) : LogAction
    data class LogGraphEnd(val id: String) : LogAction
    data class LogNodeDefined(val id: String) : LogAction
    data class LogEdgeDefined(val id: String) : LogAction
    data class LogComment(val value: String) : LogAction

    class ParserMachine(
        val input: Queue<Lexer.Token>,
        val log: Queue<LogAction>,
        val idGen: IdGen = IdGen(),
    ) {

        fun processWaste(takeDelimiters: Boolean = false) {
            when (input.first().tpe) {
                TokenType.Delimiter -> input.remove()
                TokenType.Invalid -> input.remove()
                TokenType.Comment -> {
                    log(LogComment(input.first().value)); input.remove()
                }

                else -> return
            }
        }

        fun log(a: LogAction) {
            log.add(a)
        }

        fun throwAway(vararg pattern: String, tpe: TokenType? = null) {
            if (input.isNotEmpty() && conditionMatches(*pattern, tpe = tpe)) {
                input.remove()
            }
            processWaste()
        }

        fun conditionMatches(vararg pattern: String, tpe: TokenType? = null): Boolean {
            return (pattern.isNullOrEmpty() || pattern.contains(input.first().value)) && (tpe == null || tpe == input.first().tpe)
        }

        fun matchNext(vararg pattern: String, tpe: TokenType? = null, f: (Lexer.Token?) -> Unit) {
            while (input.isNotEmpty() && !conditionMatches(*pattern, tpe = tpe)) {
                input.remove()
            }
            if (input.isNotEmpty()) {
                f(input.remove())
            } else {
                f(null)
            }
            processWaste()
        }

        fun <R> tryMatch(vararg pattern: String, tpe: TokenType? = null, f: (Lexer.Token?) -> R): R {
            val res = if (input.isNotEmpty() && conditionMatches(*pattern, tpe = tpe)) {
                f(input.remove())
            } else {
                f(null)
            }
            processWaste()
            return res
        }

        fun onMatch(vararg pattern: String, tpe: TokenType? = null, f: (Lexer.Token) -> Unit) {
            if (input.isNotEmpty() && conditionMatches(*pattern, tpe = tpe)) {
                f(input.remove())
            }
            processWaste()
        }

        fun until(vararg pattern: String, tpe: TokenType? = null, f: () -> Unit) {
            while (input.isNotEmpty() && !conditionMatches(*pattern, tpe = tpe)) {
                f()
            }
            processWaste()
        }

        fun <R> process(f: (Lexer.Token) -> R): R {
            val res = f(input.remove())
            processWaste()
            return res
        }

        fun parseGraph(): Graph {
            var graphId: String = ""
            var graphIsDirected: Boolean = true
            throwAway("strict")
            tryMatch("graph", "digraph") { graphIsDirected = it?.value?.let { it == "directed" }.orElse(true) }
            tryMatch(tpe = TokenType.Id) { graphId = it?.value.orElse(idGen.new("graph")) }
            matchNext("{") { log(LogGraphStart(graphId)) }

            val g = Graph(graphId)
            val ctx = ParserCtx(g, idGen)

            until("}") { parseGraphContent(ctx) }

            matchNext("}") { log(LogGraphEnd(graphId)) }
            processWaste()
            return g
        }

        fun parseGraphContent(ctx: ParserCtx) {
            until(tpe = TokenType.Delimiter) {
                when {
                    conditionMatches(tpe = TokenType.Id) -> process { ctx.pushNode(it) }
                    conditionMatches("<-", "--", "->") -> process { edge ->
                        process { node ->
                            ctx.pushNode(node)
                            ctx.pushEdge(edge)
                        }
                    }

                    conditionMatches("[") -> parseAttributeList(ctx)
                    conditionMatches("graph", "digraph", "subgraph") -> TODO("Not implemented yet!")
                }
            }
            process { ctx.clear() }
        }

        private fun parseAttributeList(ctx: ParserCtx) {
            until("]") {
                val left = tryMatch(tpe = TokenType.Id) { it?.value }
                val eq = tryMatch("=") { it?.value }
                val right = tryMatch(tpe = TokenType.Id) { it?.value }
                ctx.pushAttribute(left, right)
                tryMatch(tpe = TokenType.Delimiter) {}
            }
            process { }
        }

    }

}