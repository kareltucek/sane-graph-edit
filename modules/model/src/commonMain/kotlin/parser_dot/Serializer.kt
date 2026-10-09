package parser_dot

import graph_tools.Edge
import Graph
import graph_tools.Node
import utils.Utils.exhaustive
import utils.Utils.inverseMap
import utils.Utils.orElse

class Serializer(
    val g: Graph,
    val exportedNodes: MutableSet<Node> = mutableSetOf(),
    val exportedEdges: MutableSet<Edge> = mutableSetOf(),
    val usedIds: MutableSet<String> = mutableSetOf(),
    val idGen: IdGen = IdGen()
) {
    lateinit var nameNodes: MutableMap<String, Node>
    lateinit var nodeNames: MutableMap<Node, String>
    lateinit var nameEdges: MutableMap<String, Edge>

    init {
        nameThings()
    }

    fun processLog(a: LogAction): List<String> {
        return when {
            a is LogGraphStart -> processLogGraphStart(a)
            a is LogGraphEnd -> processLogGraphEnd(a)
            a is LogNodeDefined -> processLogNodeDefined(a)
            a is LogEdgeDefined -> processLogEdgeDefined(a)
            a is LogGraphAttribute -> processLogGraphAttribute(a)
            a is LogComment -> processLogComment(a)
            a is LogSectionBreak -> processLogSectionBreak(a)
            else -> throw Throwable("Action type ${a::class.simpleName} not recognized.")
        }.exhaustive()
    }

    /** Unquoted only what Graphviz reads unquoted: a name or a number (not `link-d`, `a.b`, `x:y`). */
    private val graphvizId = "[a-zA-Z_][a-zA-Z0-9_]*|-?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)".toRegex()

    fun serialize(s: String, f: Boolean = false): String {
        return when {
            graphvizId.matches(s) && !f -> s
            "<.*>".toRegex().matches(s) -> s
            else -> {
                s
                    .replace("\n", "\\n")
                    .replace("\"", "\\\"")
                    .let {
                        "\"$it\""
                    }
            }
        }
    }

    fun serializeAttributes(attrs: Map<String, String>): String {
        return attrs.takeIf { it.isNotEmpty() }
            ?.map { "${serialize(it.key)}=${serialize(it.value, true)}" }
            ?.joinToString(prefix = " [ ", separator = "; ", postfix = " ]")
            .orEmpty()
    }

    fun processNode(e: Node): List<String> {
        exportedNodes.add(e)

        return listOf(
            "${serialize(nodeNames[e]!!)}${serializeAttributes(e.retrieveAttributes())};"
        )
    }

    fun processEdge(e: Edge): List<String> {
        exportedEdges.add(e)

        return listOf(
            "${serialize(nodeNames[e.src]!!)} -> ${serialize(nodeNames[e.dst]!!)}${serializeAttributes(e.retrieveAttributes())};"
        )
    }

    fun processLogEdgeDefined(a: LogEdgeDefined): List<String> {
        return nameEdges[a.id]?.let { processEdge(it) }.orEmpty()
    }

    fun processLogNodeDefined(a: LogNodeDefined): List<String> {
        return nameNodes[a.id]?.let { processNode(it) }.orEmpty()
    }

    fun processLogComment(a: LogComment): List<String> {
        return listOf(a.value)
    }

    fun processUnprocessedNodes(): List<String> {
        return g.nodes.filter { !exportedNodes.contains(it) }.flatMap(::processNode)
    }

    fun processUnprocessedEdges(): List<String> {
        return g.edges.filter { !exportedEdges.contains(it) }.flatMap(::processEdge)
    }

    fun processLogGraphEnd(a: LogGraphEnd): List<String> {
        return listOf(
            processUnprocessedNodes(),
            processUnprocessedEdges(),
            listOf("}")
        ).flatten()
    }

    fun processLogSectionBreak(a: LogSectionBreak): List<String> {
        return listOf("")
    }

    /** Graph attributes whose statement is not in the parse log go right after the opening brace. */
    fun processLogGraphStart(a: LogGraphStart): List<String> {
        val logged = g.parseLog?.actions.orEmpty().filterIsInstance<LogGraphAttribute>().map { it.key }.toSet()
        return listOf("digraph ${a.id} {") + graphAttributeLines(g.attributes.keys - logged)
    }

    fun processLogGraphAttribute(a: LogGraphAttribute): List<String> {
        return graphAttributeLines(setOf(a.key))
    }

    private fun graphAttributeLines(keys: Set<String>): List<String> {
        return keys.mapNotNull { k -> g.attributes[k]?.let { v -> "${serialize(k)}=${serialize(v, true)};" } }
    }

    fun findName(prefix: String): String {
        var candidate: String
        do {
            candidate = idGen.new(prefix)
        } while (usedIds.contains(candidate))
        return candidate
    }

    fun nameThings() {
        val nameNodes: MutableMap<String, Node> = mutableMapOf()
        val nameEdges: MutableMap<String, Edge> = mutableMapOf()

        g.nodes.forEach {
            if (it.attributes.name.isNotBlank()) {
                nameNodes[it.attributes.name] = it
                usedIds.add(it.attributes.name)
            }
        }

        g.edges.forEach {
            if (it.attributes.name.isNotBlank()) {
                nameEdges[it.attributes.name] = it
                usedIds.add(it.attributes.name)
            }
        }

        g.nodes.forEach {
            if (!nameNodes.containsValue(it)) {
                val name = findName("n")
                nameNodes[name] = it
                usedIds.add(name)
            }
        }

        /* We are throwing edge names out. */

        this.nameNodes = nameNodes
        this.nameEdges = nameEdges
        nodeNames = nameNodes.inverseMap()
    }

    fun serialize(): String {
        val log = g.parseLog.orElse(ParseLog.dummy(g))

        val lines = log.actions.flatMap { processLog(it) }
        return lines.joinToString(separator = "\n")
    }

    fun serializeSubset(nodes: Set<Node>): String {
        val nodeLines = g.nodes.filter { nodes.contains(it) }.flatMap(::processNode)
        val edgeLines = g.edges
            .filter { nodes.contains(it.src) && nodes.contains(it.dst) }
            .flatMap(::processEdge)
        return (listOf("digraph ${g.id} {") + graphAttributeLines(g.attributes.keys) + nodeLines + edgeLines + listOf("}"))
            .joinToString(separator = "\n")
    }
}