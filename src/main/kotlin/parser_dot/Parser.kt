package parser_dot

import DotGraphLoader
import Graph
import ui.Utils.orElse
import java.util.*

class Parser(
    val input: Queue<DotGraphLoader.Token>,
    val log: ParseLog = ParseLog(),
    val idGen: IdGen = IdGen(),
) {

    fun processWaste(takeDelimiters: Boolean = false) {
        if (input.isNotEmpty()) {
            when (input.first().tpe) {
                TokenType.Delimiter -> if(takeDelimiters) { input.remove() }
                TokenType.Invalid -> input.remove()
                TokenType.Comment -> {
                    log.add(LogComment(input.first().value)); input.remove()
                }
                TokenType.SectionBreak -> {
                    log.add(LogSectionBreak(input.first().value)); input.remove()
                }
                else -> return
            }
        }
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

    fun matchNext(vararg pattern: String, tpe: TokenType? = null, f: (DotGraphLoader.Token?) -> Unit) {
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

    fun <R> tryMatch(vararg pattern: String, tpe: TokenType? = null, f: (DotGraphLoader.Token?) -> R): R {
        val res = if (input.isNotEmpty() && conditionMatches(*pattern, tpe = tpe)) {
            f(input.remove())
        } else {
            f(null)
        }
        processWaste()
        return res
    }

    fun onMatch(vararg pattern: String, tpe: TokenType? = null, f: (DotGraphLoader.Token) -> Unit) {
        if (input.isNotEmpty() && conditionMatches(*pattern, tpe = tpe)) {
            f(input.remove())
        }
        processWaste()
    }
    fun whilePossible(f: () -> Unit) {
        var progressing = true
        while (input.isNotEmpty() && progressing) {
            val sizeBefore = input.size
            f()
            val sizeAfter = input.size
            progressing = sizeAfter > sizeBefore
        }
        processWaste()
    }

    fun until(vararg pattern: String, tpe: TokenType? = null, f: () -> Unit) {
        while (input.isNotEmpty() && !conditionMatches(*pattern, tpe = tpe)) {
           val sizeBefore = input.size
            f()
            val sizeAfter = input.size
            if(sizeAfter == sizeBefore) {
                throw Throwable( "parser cannot continue!")
            }
        }
        processWaste()
    }

    fun <R> process(f: (DotGraphLoader.Token) -> R): R {
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
        matchNext("{") { log.add(LogGraphStart(graphId)) }

        val g = Graph(graphId)
        val ctx = ParserCtx(g, idGen, log)

        until("}") { parseGraphContent(ctx) }

        matchNext("}") { log.add(LogGraphEnd(graphId)) }
        processWaste()

        g.parseLog = log
        return g
    }

    fun parseGraphContent(ctx: ParserCtx) {
        whilePossible {
            when {
                conditionMatches(tpe = TokenType.Id) -> process { tok ->
                    if (input.first().value == "=") {
                        process { eq -> process { attr -> ctx.pushGraphAttribute(tok, attr) } }
                    } else {
                        ctx.pushNode(tok)
                    }
                }

                conditionMatches("<-", "--", "->") -> process { edge ->
                    process { node ->
                        ctx.pushNode(node)
                        ctx.pushEdge(edge)
                    }
                }

                conditionMatches("[") -> parseAttributeList(ctx)
                conditionMatches("graph", "digraph", "subgraph") -> TODO("Not implemented yet!")
                conditionMatches(tpe = TokenType.Delimiter) -> process {
                    ctx.clear()
                }
            }
        }
    }

    private fun parseAttributeList(ctx: ParserCtx) {
        process {}
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
