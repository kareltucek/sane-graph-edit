package ui

import client.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Completion for the `:` command line (Tab / Ctrl+D in [CommandBar]):
 * command names first, then per command — `:set` settings, `:help`
 * topics, `:export` formats, and paths for `:e`, `:w`, `:source`,
 * `:browse` and `:cd` (directories only) and the path of `:export`.
 */
object CommandCompletion {
    /**
     * What to complete in [line] (the text after `:`, completed at its
     * end): the word starts at [wordStart] and may be replaced by any
     * of [candidates] (sorted, whole replacements for the word).
     */
    data class Result(val wordStart: Int, val candidates: List<String>)

    private val pathCommands = setOf("e", "w", "source", "browse", "cd")
    private val dirCommands = setOf("browse", "cd")

    fun complete(line: String, commands: List<String>, cwd: Path = Workspace.dir): Result {
        val wordStart = line.lastIndexOf(' ') + 1
        val word = line.substring(wordStart)
        val words = line.substring(0, wordStart).split(' ').filter { it.isNotEmpty() }
        val cmd = words.firstOrNull()
        val all = when {
            cmd == null -> commands
            cmd == "set" || cmd == "se" -> settings()
            cmd == "help" && words.size == 1 -> KeyMapper.HELP_TOPICS
            (cmd == "export" || cmd == "export-selection") && words.size == 1 -> listOf("svg", "dot")
            (cmd == "export" || cmd == "export-selection") && words.size == 2 -> paths(word, cwd, dirsOnly = false)
            cmd in pathCommands && words.size == 1 -> paths(word, cwd, dirsOnly = cmd in dirCommands)
            else -> emptyList()
        }
        return Result(wordStart, all.filter { it.startsWith(word) }.distinct().sorted())
    }

    private fun settings(): List<String> =
        KeyMapper.VALUE_SETTINGS.map { "$it=" } +
            KeyMapper.FLAG_SETTINGS +
            KeyMapper.FLAG_SETTINGS.map { "no$it" } +
            KeyMapper.FLAG_SETTINGS.map { "$it!" }

    /**
     * Entries of the directory [word] points into, as replacements for
     * [word] (directories end in `/`). `~/` is kept as typed; relative
     * paths are relative to [cwd], like `:e` resolves them. Hidden
     * entries only when the typed name starts with `.`.
     */
    private fun paths(word: String, cwd: Path, dirsOnly: Boolean): List<String> {
        val slash = word.lastIndexOf('/')
        val typedDir = word.substring(0, slash + 1)
        val namePrefix = word.substring(slash + 1)
        val expanded = if (typedDir.startsWith("~/")) System.getProperty("user.home") + typedDir.drop(1) else typedDir
        val dir = cwd.resolve(expanded.ifEmpty { "." })
        val entries = try {
            Files.list(dir).use { s -> s.toList() }
        } catch (_: Exception) {
            emptyList()
        }
        return entries
            .filter { namePrefix.startsWith(".") || !it.fileName.toString().startsWith(".") }
            .filter { !dirsOnly || Files.isDirectory(it) }
            .map { typedDir + it.fileName.toString() + if (Files.isDirectory(it)) "/" else "" }
    }

    /** Longest common prefix of [items] (empty for none). */
    fun commonPrefix(items: List<String>): String =
        items.reduceOrNull { a, b -> a.commonPrefixWith(b) }.orEmpty()
}
