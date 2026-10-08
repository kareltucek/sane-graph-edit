package parser_dot

import utils.Utils.orElse
import java.util.*

// (Yes, this is a naive tokenizer.)
class Tokenizer(
    val input: String,
    val tokenQueue: Queue<DotGraphLoader.Token> = LinkedList<DotGraphLoader.Token>(),
    var idx: Int = 0,
) {
    val idPattern = "[a-zA-Z:0-9._-]+".toRegex()
    fun pushMatch(tpe: TokenType, token: String) {
        tokenQueue.add(DotGraphLoader.Token(tpe, token))
        idx += token.length
    }

    fun pushAcross(r: Regex): (TokenType, String) -> Unit {
        return { tpe, dontcare ->
            val match = r.find(input, idx)
            val newIdx = match?.range?.last?.let { it + 1 }

            if (newIdx != null) {
                val token = input.substring(idx, newIdx)
                tokenQueue.add(DotGraphLoader.Token(tpe, token))
                idx = newIdx!!
            } else {
                idx++
            }
        }
    }

    fun skipAcross(r: Regex): (TokenType, String) -> Unit {
        return { tpe, dontCare -> idx = r.find(input, idx)?.range?.last?.let { it + 1 }.orElse(idx + 1) }
    }

    fun lookahead(n: Int) = input[(idx + n).coerceIn(0, input.length - 1)]


    fun sectionBreak(tpe: TokenType, dontcare: String) {
        this.pushMatch(tpe, dontcare)
        tokenQueue.add(DotGraphLoader.Token(TokenType.Delimiter, "\n"))
    }

    fun literal(tpe: TokenType, dontcare: String) {
        when (input[idx]) {
            '<' -> {
                var token = ""
                var bracketState = 0

                do {
                    token += input[idx]
                    when (input[idx]) {
                        '<' -> bracketState++
                        '>' -> bracketState--
                    }
                    idx++
                } while (idx < input.length && bracketState > 0)

                tokenQueue.add(DotGraphLoader.Token(TokenType.Id, token))
            }

            '"' -> {
                idx++
                var token = ""
                while (idx < input.length && input[idx] != '"') {
                    when (input[idx]) {
                        '\\' -> when (lookahead(1)) {
                            'n', 'l', 'r' -> {
                                token += '\n'; idx += 2
                            }

                            '"' -> {
                                token += '"'; idx += 2
                            }

                            '\n', '\r' -> {
                                idx += 2
                            }

                            else -> {
                                token += input[idx]; idx++; token += input[idx]; idx++
                            }
                        }

                        else -> {
                            token += input[idx]
                            idx++
                        }
                    }
                }
                idx++
                tokenQueue.add(DotGraphLoader.Token(TokenType.Id, token))
            }

            else -> {
                idPattern.matchAt(input, idx)?.value
                    ?.let { pushMatch(TokenType.Id, it) }
                    .orElse { idx++ }

            }
        }
    }


    data class LexerRecord(val type: TokenType, val pattern: Regex, val f: (TokenType, String) -> Unit)

    val table: List<LexerRecord> = listOf(
        LexerRecord(TokenType.Op, "[\\]}={\\[]".toRegex(), this::pushMatch),
        LexerRecord(TokenType.Op, "<-|--|->".toRegex(), this::pushMatch),
        LexerRecord(TokenType.Comment, "//[^\\r\\n]*".toRegex(), this::pushMatch),
        LexerRecord(TokenType.Comment, "#[^\\r\\n]*".toRegex(), this::pushMatch),
        LexerRecord(TokenType.Comment, "/[\\\\*]".toRegex(), pushAcross("[\\\\*][/]".toRegex())),
        LexerRecord(TokenType.SectionBreak, "\\r\\s*\\n(\\r\\n)+|\\n\\s*\\n+|\\r\\s*\\r+".toRegex(), this::sectionBreak),
        LexerRecord(TokenType.Delimiter, "[;\\n\\r]".toRegex(), this::pushMatch),
        LexerRecord(TokenType.Invalid, "\\s".toRegex(), skipAcross("\\s\\s*".toRegex())),
        LexerRecord(TokenType.Op, ".".toRegex(), this::literal),
    )

    fun String.prettify(): String {
        return this
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    fun lex(debug: Boolean = false): Queue<DotGraphLoader.Token> {
        while (idx < input.length) {
            table
                .find { it.pattern.matchesAt(input, idx) }
                .orElse(LexerRecord(TokenType.Invalid, ".".toRegex(), { dontcare1, dontcare2 -> idx++ }))
                .let { record ->
                    val oldIdx = idx
                    val oldStackSize = tokenQueue.size
                    val match = record.pattern.matchAt(input, idx)!!.value

                    record.f(record.type, match)

                    if (debug) {
                        val context = input.substring(oldIdx, (oldIdx + 20).coerceIn(0, input.length)).prettify()
                        val match2 = match.prettify()
                        val token =
                            tokenQueue.last().takeIf { tokenQueue.size > oldStackSize }?.value?.let { "'$it'" }
                                ?.prettify()
                        println("matched   ${record.pattern}   to   '$match2'->${token}   at   '${context}'")
                    }
                }
        }

        return tokenQueue
    }
}