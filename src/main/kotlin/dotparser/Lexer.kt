import Utils.orElse
import dotparser.TokenType
import java.util.*

object Lexer {
    data class Token(val tpe: TokenType, val value: String)

    fun tokenize(input: String, debug: Boolean = false): Queue<Token> {
        return LexerMachine(input).lex(debug)
    }

    class LexerMachine(
        val input: String,
        val tokenQueue: Queue<Token> = LinkedList<Token>(),
        var idx: Int = 0,
    ) {
        val idPattern = "[a-zA-Z:0-9._-]+".toRegex()
        fun pushMatch(tpe: TokenType, token: String) {
            tokenQueue.add(Token(tpe, token))
            idx += token.length
        }

        fun pushAcross(r: Regex): (TokenType, String) -> Unit {
            return { tpe, dontcare ->
                val newIdx = r.find(input, idx)?.range?.last?.let { it + 1 }

                if (newIdx != null) {
                    val token = input.substring(idx, newIdx!!)
                    tokenQueue.add(Token(tpe, token))
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

                    tokenQueue.add(Token(TokenType.Id, token))
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
                    tokenQueue.add(Token(TokenType.Id, token))
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
            LexerRecord(TokenType.Invalid, "\\s".toRegex(), skipAcross("\\s\\s*".toRegex())),

            LexerRecord(TokenType.Op, "[\\]}={\\[]".toRegex(), this::pushMatch),
            LexerRecord(TokenType.Op, "<-|--|->".toRegex(), this::pushMatch),
            LexerRecord(TokenType.Comment, "//".toRegex(), pushAcross("[\\r\\n][\\r\\n]*".toRegex())),
            LexerRecord(TokenType.Comment, "#".toRegex(), pushAcross("[\\r\\n][\\r\\n]*".toRegex())),
            LexerRecord(TokenType.Comment, "/[\\\\*]".toRegex(), pushAcross("[\\\\*][/]".toRegex())),
            LexerRecord(TokenType.Delimiter, "[;\n\r]".toRegex(), this::pushMatch),
            LexerRecord(TokenType.Op, ".".toRegex(), this::literal),
        )

        fun String.prettify(): String {
            return this
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        }

        fun lex(debug: Boolean = false): Queue<Token> {
            while (idx < input.length) {
                table
                    .find { it.pattern.matchesAt(input, idx) }
                    .orElse(LexerRecord(TokenType.Invalid, "".toRegex(), { dontcare1, dontcare2 -> idx++ }))
                    .let { record ->
                        val oldIdx = idx
                        val oldStackSize = tokenQueue.size
                        val match = record.pattern.matchAt(input, idx)!!.value.prettify()

                        record.f(record.type, match)

                        if (debug) {
                            val context = input.substring(oldIdx, (oldIdx + 20).coerceIn(0, input.length)).prettify()
                            val token =
                                tokenQueue.last().takeIf { tokenQueue.size > oldStackSize }?.value?.let { "'$it'" }
                                    ?.prettify()
                            println("matched   ${record.pattern}   to   '$match'->${token}   at   '${context}'")
                        }
                    }
            }

            return tokenQueue
        }
    }
}

fun testLexer() {
    val input = """
       graph graphname {
         // This attribute applies to the graph itself
         size="1,1";
         // The label attribute can be used to change the label of a node
         /* comment */
         a [label="Foo \" \
       fucking lael test\a\b\c \n\l\r"];
         // Here, the node shape is changed.
         b [shape=< <U><TABLE><TR><TD>a</TD></TR></U>>];
         // These edges both have different line properties
         a -- b -- c [color=blue];
         b -- d [style=dotted];
         // [style=invis] hides a node.
       } 
    """.trimIndent()

    val wantList = listOf(
        "1,1",
        "< <U><TABLE><TR><TD>a</TD></TR></U>>",
        "Foo \" \nfucking lael test\\a\\b\\c \\n\\l\\r"
    )

    val res = Lexer.tokenize(input, true)

    wantList.forEach { want -> assert(res.any { got -> got.value == want }) }
}

fun main(args: Array<String>) {
    testLexer()
}
