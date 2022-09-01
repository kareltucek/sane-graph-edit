import DotGraphLoader.Test.testLexer
import DotGraphLoader.Test.testParser
import parser_dot.Serializer
import parser_dot.Tokenizer
import parser_dot.TokenType
import java.io.File
import java.util.*

object DotGraphLoader {
    data class Token(val tpe: TokenType, val value: String)

    fun tokenize(input: String, debug: Boolean = false): Queue<Token> {
        return Tokenizer(input).lex(debug)
    }

    fun parse(tokens: Queue<Token>): Graph {
        return parser_dot.Parser(tokens).parseGraph()
    }

    fun serialize(g: Graph): String {
        return Serializer(g).serialize()
    }

    fun loadFromFile(filename: String): Graph {
        val content = File(filename).readText()
        val g = parse(tokenize(content))
        return g
    }

    fun saveToFile(g: Graph, filename: String) {
        val content = serialize(g)
        File(filename)
            .also {
                if (!it.exists()) {
                    it.createNewFile()
                }
            }
            .writeText(content)
    }

    object Test {
        val input = """
           graph graphname {
             // This attribute applies to the graph itself
             size="1,1";
             // The label attribute can be used to change the label of a node
             /* comment */
             a [label="Foo \" \
           lael test\a\b\c \n\l\r"];
             // Here, the node shape is changed.
             b [shape=< <U><TABLE><TR><TD>a</TD></TR></U>>];
             // These edges both have different line properties
             a -- b -- c [color=blue];
             b -- d [style=dotted];
             n5 -> n6 [ label="abc" ];
           
             // [style=invis] hides a node.
           } 
        """.trimIndent()

        fun testLexer() {

            val wantList = listOf(
                "1,1",
                "< <U><TABLE><TR><TD>a</TD></TR></U>>",
                "Foo \" lael test\\a\\b\\c \n\n\n",
                "\n\n"
            )

            val res = DotGraphLoader.tokenize(input, true)

            wantList.forEach { want ->
                if (!res.any { got -> got.value == want }) {
                   throw Throwable("'$want' not found")
                }
            }
        }

        fun testParser() {
            val res = parse(tokenize(input))
            val dbg = 33
        }
    }
}

fun main(args: Array<String>) {
    testLexer()
    testParser()
}
