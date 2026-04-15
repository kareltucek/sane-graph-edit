package ui

import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Two-table key dispatcher with multi-key timeout.
 *
 * **`defaults`** (built-in): key notation → command name.
 * Immutable after construction. This is the terminal layer —
 * it speaks command names that [CommandRegistry] executes.
 *
 * **`mappings`** (user-defined): key notation → key sequence.
 * Populated from the init file and `:map` / `:remap` commands.
 * This is the routing layer — it rewrites keys before they
 * reach `defaults`.
 *
 * Resolution on each keypress:
 *
 * 1. Accumulate the pressed key onto `pending`.
 * 2. Check if `pending` exactly matches a mapping or default.
 * 3. Check if `pending` is a prefix of any longer mapping or
 *    default.
 * 4. Four cases:
 *    - exact match + is prefix → ambiguous, start timeout
 *    - exact match + not prefix → fire immediately
 *    - no match + is prefix → wait for more keys
 *    - no match + not prefix → dead end, try partial resolve
 *
 * When a mapping fires, its RHS is a key sequence:
 * - **Non-recursive** (`map`/`noremap`): each RHS key is resolved
 *   through `defaults` only (skipping `mappings`).
 * - **Recursive** (`remap`): each RHS key is fed back through the
 *   full pipeline (`mappings` + `defaults`).
 *
 * See `tasks/features/command-mode.md` for the full spec.
 */
class KeyMapper(
    private val defaults: Map<String, String>,
    var timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    data class Mapping(val rhs: String, val recursive: Boolean)

    private val mappings = mutableMapOf<String, Mapping>()
    private var pending: String = ""
    private var pendingTimer: Timer? = null

    /** Callback invoked to execute a resolved command name. */
    var commandExecutor: ((String, GraphView) -> Unit)? = null

    /** Callback invoked when `:` is replayed (opens the command bar). */
    var commandBarOpener: ((GraphView) -> Unit)? = null

    /** The currently active GraphView — set by the key listener before feeding keys. */
    var activeView: GraphView? = null

    // --- macro registers ---

    /** Named registers: single char → recorded key sequence. */
    val registers = mutableMapOf<Char, String>()

    /**
     * Macro recording state:
     * - `null` → not recording
     * - a Char → recording into that register
     */
    private var recordingRegister: Char? = null
    private val recordingBuffer = StringBuilder()

    /**
     * Prefix actions waiting for a letter: `q` records a macro,
     * `@` replays one, `m` sets a mark, `'` recalls a mark. Null
     * when no prefix is active.
     */
    private enum class PrefixAction { RecordMacro, ReplayMacro, SetMark, RecallMark }
    private var waitingForPrefix: PrefixAction? = null

    /** True if currently recording a macro. */
    val isRecording: Boolean get() = recordingRegister != null

    /** True if replaying a macro (suppresses recording to avoid feedback loops). */
    private var replaying: Boolean = false

    /** Callback for setting a mark on the current selection. Set by Window. */
    var markSetter: ((GraphView, Char) -> Unit)? = null

    /** Callback for recalling a mark. Set by Window. */
    var markRecaller: ((GraphView, Char) -> Unit)? = null

    /**
     * When non-null, we're accumulating characters into a
     * command-bar line (after `:` was fed). `<CR>` ends
     * accumulation and dispatches via [executeCommandLine].
     *
     * Set in two situations:
     *  - Headless `-e` replay where `:` is fed as a literal key
     *    (no visual bar to open).
     *  - Mapping RHS replay (see [replayRhs]).
     *
     * In interactive canvas mode, `:` is intercepted by the key
     * listener and opens the real command bar before the key
     * reaches the mapper, so this buffer is never used there.
     */
    private var commandBuffer: StringBuilder? = null

    // --- public API ---

    fun map(lhs: String, rhs: String, recursive: Boolean = false) {
        mappings[lhs] = Mapping(rhs, recursive)
    }

    fun unmap(lhs: String) {
        mappings.remove(lhs)
    }

    /** Clear all user mappings (but not defaults). */
    fun clearMappings() {
        mappings.clear()
    }

    // --- macro recording/replay ---

    private fun startRecording(register: Char) {
        recordingRegister = register
        recordingBuffer.clear()
    }

    private fun stopRecording() {
        val reg = recordingRegister ?: return
        registers[reg] = recordingBuffer.toString()
        recordingRegister = null
        recordingBuffer.clear()
    }

    /**
     * Replay a register's key sequence through the full pipeline
     * (mappings + defaults). This is recursive by design: a macro
     * replays keystrokes as if the user typed them, so user
     * remappings apply inside macros.
     */
    private fun replayRegister(register: Char) {
        val sequence = registers[register]
        if (sequence.isNullOrEmpty()) return
        replaying = true
        try {
            val tokens = KeyNotation.tokenize(sequence)
            for (tok in tokens) {
                feedKey(tok)
            }
        } finally {
            replaying = false
        }
    }

    /**
     * Feed a single key notation (like `"e"`, `"<C-s>"`,
     * `"<Space>"`) into the mapper. Call this from the key
     * listener for every keypress.
     */
    fun feedKey(key: String) {
        // --- Command-buffer mode (":..." in flight) ---
        val buf = commandBuffer
        if (buf != null) {
            when (key) {
                "<CR>" -> {
                    commandBuffer = null
                    val line = buf.toString().trim()
                    if (line.isNotEmpty()) executeCommandLine(line)
                }
                "<Esc>" -> {
                    // Cancel the buffered command
                    commandBuffer = null
                }
                else -> {
                    // Any other token: append its visible form.
                    // Tokens are single chars or angle-bracket tokens.
                    // Spaces come through as " ".
                    buf.append(key)
                }
            }
            return
        }

        // --- Prefix keys: q, @, m, ' (single quote) ---
        // If we're waiting for a letter after a prefix key,
        // consume this key as the action target.
        val prefix = waitingForPrefix
        if (prefix != null) {
            waitingForPrefix = null
            val letter = key.firstOrNull()
            if (letter != null && letter.isLetterOrDigit() && key.length == 1) {
                when (prefix) {
                    PrefixAction.RecordMacro -> startRecording(letter)
                    PrefixAction.ReplayMacro -> replayRegister(letter)
                    PrefixAction.SetMark -> activeView?.let { markSetter?.invoke(it, letter) }
                    PrefixAction.RecallMark -> activeView?.let { markRecaller?.invoke(it, letter) }
                }
            }
            return
        }

        // `q` toggles recording. If recording, stop. If not
        // recording, wait for the next key as register name.
        if (key == "q") {
            if (isRecording) {
                stopRecording()
            } else {
                waitingForPrefix = PrefixAction.RecordMacro
            }
            return
        }

        // `@` starts replay — wait for the register name.
        if (key == "@") {
            waitingForPrefix = PrefixAction.ReplayMacro
            return
        }

        // `m` sets a mark — wait for the register letter.
        if (key == "m") {
            waitingForPrefix = PrefixAction.SetMark
            return
        }

        // `'` recalls a mark — wait for the register letter.
        if (key == "'") {
            waitingForPrefix = PrefixAction.RecallMark
            return
        }

        // `:` enters command-buffer mode. Subsequent chars are
        // appended until <CR>. Used by headless replay and by
        // mapping RHSes that contain ":command<CR>". In interactive
        // canvas mode the key listener intercepts `:` before it
        // reaches the mapper, so this path only fires during replay.
        if (key == ":") {
            commandBuffer = StringBuilder()
            return
        }

        // --- Normal dispatch ---
        // If recording, capture this key (unless we're inside
        // a replay, to avoid feedback loops).
        if (isRecording && !replaying) {
            recordingBuffer.append(key)
        }

        cancelTimer()
        val accumulated = pending + key
        resolveAccumulated(accumulated)
    }

    /** Cancel any pending timeout and clear accumulated keys. */
    fun reset() {
        cancelTimer()
        pending = ""
    }

    /** True if the mapper is waiting for more keys. */
    val isPending: Boolean get() = pending.isNotEmpty()

    // --- resolution logic ---

    private fun resolveAccumulated(accumulated: String) {
        val hasExact = hasExactMatch(accumulated)
        val hasPrefix = isPrefixOfLonger(accumulated)

        when {
            hasExact && hasPrefix -> {
                // Ambiguous: could be this key, or the start of
                // something longer. Wait for timeout.
                pending = accumulated
                startTimeout()
            }

            hasExact && !hasPrefix -> {
                // Definite match, nothing longer possible.
                pending = ""
                fire(accumulated)
            }

            !hasExact && hasPrefix -> {
                // No match yet, but could become one. Wait.
                pending = accumulated
                // Don't start timeout unless there's a shorter
                // prefix that HAS a match — otherwise wait
                // indefinitely (leader-key behaviour).
            }

            else -> {
                // Dead end: accumulated doesn't match anything and
                // isn't a prefix. Try to salvage by resolving the
                // longest matching prefix and reprocessing the rest.
                pending = ""
                if (accumulated.length > 1) {
                    resolvePartial(accumulated)
                }
                // Single key with no binding → ignore.
            }
        }
    }

    /**
     * Timeout expired: fire whatever the accumulated keys match
     * (standalone binding), or discard if they don't match
     * anything on their own.
     */
    private fun onTimeout() {
        val keys = pending
        pending = ""
        if (hasExactMatch(keys)) {
            fire(keys)
        }
        // else: incomplete sequence, discard silently.
    }

    /**
     * Fire the best match for [keys]. Checks mappings first,
     * then defaults. For mappings, replays the RHS as a key
     * sequence.
     */
    private fun fire(keys: String) {
        // Mappings take priority over defaults.
        val mapping = mappings[keys]
        if (mapping != null) {
            replayRhs(mapping.rhs, mapping.recursive)
            return
        }

        val command = defaults[keys]
        if (command != null) {
            executeCommand(command)
        }
    }

    /**
     * The accumulated keys are a dead end. Try progressively
     * shorter prefixes until one matches, fire it, and
     * reprocess the leftover keys.
     */
    private fun resolvePartial(accumulated: String) {
        // Tokenize accumulated back into individual key notations
        // so we can split at the right boundaries.
        val tokens = KeyNotation.tokenize(accumulated)
        for (splitAt in tokens.size - 1 downTo 1) {
            val prefix = tokens.take(splitAt).joinToString("")
            if (hasExactMatch(prefix)) {
                fire(prefix)
                // Reprocess remaining tokens
                val rest = tokens.drop(splitAt)
                for (k in rest) {
                    feedKey(k)
                }
                return
            }
        }
        // Nothing matched at all — discard.
    }

    /**
     * Replay a mapping's RHS as a key sequence.
     *
     * Handles the `:command<CR>` pattern: if we encounter `:`,
     * accumulate subsequent chars until `<CR>` and execute the
     * accumulated string as a command-bar line.
     */
    private fun replayRhs(rhs: String, recursive: Boolean, depth: Int = 0) {
        if (depth > MAX_RECURSION) {
            System.err.println("sane-graph-edit: max mapping recursion depth ($MAX_RECURSION) exceeded, aborting")
            return
        }

        val tokens = KeyNotation.tokenize(rhs)
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]

            // Handle :command<CR> pattern inline — don't visually
            // open the bar, just execute the command.
            if (tok == ":") {
                val buf = StringBuilder()
                i++
                while (i < tokens.size && tokens[i] != "<CR>") {
                    buf.append(tokens[i])
                    i++
                }
                if (i < tokens.size) i++ // skip <CR>
                val cmdLine = buf.toString().trim()
                if (cmdLine.isNotEmpty()) {
                    executeCommandLine(cmdLine)
                }
                continue
            }

            if (recursive) {
                // Feed through the full pipeline (mappings + defaults).
                // Use recursion depth to prevent infinite loops.
                val mapping = mappings[tok]
                if (mapping != null) {
                    replayRhs(mapping.rhs, mapping.recursive, depth + 1)
                } else {
                    val cmd = defaults[tok]
                    if (cmd != null) executeCommand(cmd)
                }
            } else {
                // Non-recursive: defaults only, skip mappings.
                val cmd = defaults[tok]
                if (cmd != null) executeCommand(cmd)
            }
            i++
        }
    }

    // --- helpers ---

    private fun hasExactMatch(keys: String): Boolean =
        keys in mappings || keys in defaults

    private fun isPrefixOfLonger(prefix: String): Boolean =
        mappings.keys.any { it.startsWith(prefix) && it != prefix } ||
            defaults.keys.any { it.startsWith(prefix) && it != prefix }

    private fun executeCommand(name: String) {
        val gv = activeView ?: return
        commandExecutor?.invoke(name, gv)
    }

    /**
     * Resolve a path argument from a command-bar line. Expands a
     * leading `~/` to the user's home directory and converts the
     * result to an absolute [java.nio.file.Path]. Shells normally
     * expand `~` before invoking us, but `~` typed inside the
     * in-app `:` bar (or inside a `-e` argument that's quoted at
     * the shell level) arrives literally — so we expand it
     * ourselves.
     */
    private fun resolvePath(raw: String): java.nio.file.Path {
        val trimmed = raw.trim()
        val expanded = when {
            trimmed == "~" -> System.getProperty("user.home")
            trimmed.startsWith("~/") -> System.getProperty("user.home") + trimmed.substring(1)
            else -> trimmed
        }
        return java.nio.file.Paths.get(expanded)
    }

    /**
     * Execute a command-bar line. Config commands (`map`, `set`,
     * `source`, etc.) work without a [GraphView]; graph commands
     * (`:w`, `:q`, `:e`, named commands) require one and are
     * silently skipped if [activeView] is null (which happens
     * during init-file loading before the UI is up).
     */
    fun executeCommandLine(line: String) {
        val parts = line.split("\\s+".toRegex(), limit = 2)
        val cmd = parts[0]
        val args = parts.getOrNull(1)

        // --- Config commands (no GraphView needed) ---
        when (cmd) {
            "map", "noremap" -> {
                if (args != null) {
                    val mapParts = args.split("\\s+".toRegex(), limit = 2)
                    if (mapParts.size == 2) {
                        map(mapParts[0], mapParts[1], recursive = false)
                    }
                } else {
                    // :map with no args → list current bindings
                    showHelpText(formatBindings())
                }
                return
            }
            "remap" -> {
                if (args != null) {
                    val mapParts = args.split("\\s+".toRegex(), limit = 2)
                    if (mapParts.size == 2) {
                        map(mapParts[0], mapParts[1], recursive = true)
                    }
                }
                return
            }
            "unmap" -> {
                if (args != null) unmap(args.trim())
                return
            }
            "set" -> {
                if (args != null) {
                    val eqIdx = args.indexOf('=')
                    if (eqIdx > 0) {
                        val optName = args.substring(0, eqIdx).trim()
                        val optVal = args.substring(eqIdx + 1).trim()
                        applySetting(optName, optVal)
                    }
                }
                return
            }
            "source" -> {
                if (args != null) {
                    loadInitFile(resolvePath(args))
                }
                return
            }
        }

        // --- Graph commands (need an active view) ---
        val gv = activeView ?: return
        when (cmd) {
            "w" -> {
                if (args != null) {
                    try {
                        DotGraphLoader.saveToFile(gv.g, resolvePath(args).toString())
                    } catch (t: Throwable) {
                        System.err.println("sane-graph-edit: :w failed: ${t.message}")
                    }
                } else {
                    CommandRegistry.execute("save", gv)
                }
            }
            "q" -> CommandRegistry.execute("close-tab", gv)
            "q!" -> gv.tabManager?.closeCurrent()
            "wq" -> {
                if (CommandRegistry.execute("save", gv)) {
                    CommandRegistry.execute("close-tab", gv)
                }
            }
            "help" -> {
                val what = args?.trim() ?: ""
                val text = when (what) {
                    "" -> formatHelpOverview()
                    "commands" -> formatCommandList()
                    "keys", "map", "bindings" -> formatBindings()
                    "init", "config", "settings" -> formatInitHelp()
                    else -> "sane-graph-edit: unknown :help topic '$what'\n\n" +
                        formatHelpOverview()
                }
                showHelpText(text)
            }
            "export" -> {
                // :export <path> — write the whole graph as SVG.
                // No-path form falls back to the dialog (interactive only).
                if (args != null) {
                    try {
                        val visible = gv.g.nodes.filter { it.isVisible }.toSet()
                        java.nio.file.Files.writeString(
                            resolvePath(args),
                            export.SvgWriter.write(gv.g, visible),
                        )
                    } catch (t: Throwable) {
                        System.err.println("sane-graph-edit: :export failed: ${t.message}")
                    }
                } else {
                    CommandRegistry.execute("export-svg", gv)
                }
            }
            "export-selection" -> {
                // :export-selection <path> — write only current selection.
                if (args != null) {
                    try {
                        val sel = gv.g.selectedNodes.takeIf { it.isNotEmpty() }
                        java.nio.file.Files.writeString(
                            resolvePath(args),
                            export.SvgWriter.write(gv.g, sel),
                        )
                    } catch (t: Throwable) {
                        System.err.println("sane-graph-edit: :export-selection failed: ${t.message}")
                    }
                } else {
                    CommandRegistry.execute("export-selection", gv)
                }
            }
            "e" -> {
                if (args != null) {
                    try {
                        val resolved = resolvePath(args)
                        val loaded = DotGraphLoader.loadFromFile(resolved.toString())
                        gv.tabManager?.newTab(graph = loaded, path = resolved)
                    } catch (t: Throwable) {
                        System.err.println("sane-graph-edit: :e failed: ${t.message}")
                    }
                } else {
                    CommandRegistry.execute("open", gv)
                }
            }
            else -> {
                // Try as a registered command name
                CommandRegistry.execute(cmd, gv)
            }
        }
    }

    private fun applySetting(name: String, value: String) {
        when (name) {
            "timeoutlen" -> value.toIntOrNull()?.let { timeoutMs = it }
            "spring-scale" -> value.toDoubleOrNull()?.let { graph_tools.LayoutOptimizer.springScale = it }
            else -> System.err.println("sane-graph-edit: unknown setting '$name'")
        }
    }

    /**
     * Load an init file. Each line is a command-bar command
     * (`map`, `remap`, `noremap`, `set`, etc.). Comments start
     * with `#`. Blank lines are ignored.
     */
    fun loadInitFile(path: java.nio.file.Path) {
        if (!java.nio.file.Files.exists(path)) return
        try {
            java.nio.file.Files.readAllLines(path).forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                executeCommandLine(line)
            }
        } catch (t: Throwable) {
            System.err.println("sane-graph-edit: failed to load init file $path: ${t.message}")
        }
    }

    // --- timer ---

    private fun startTimeout() {
        val timer = Timer(timeoutMs) { onTimeout() }
        timer.isRepeats = false
        timer.start()
        pendingTimer = timer
    }

    private fun cancelTimer() {
        pendingTimer?.stop()
        pendingTimer = null
    }

    // --- help / bindings formatters ---

    /**
     * Top-level :help landing page: lists the available :help
     * variants plus a pointer at the rest.
     */
    private fun formatHelpOverview(): String = """
        sane-graph-edit :help

        :help commands       List every registered command name
                             (use as targets in the : bar or in
                             `map` RHSes via :cmd<Enter>).
        :help keys           List active key bindings, grouped by
                             base letter. Empty slots are visible
                             as headers with no entries.
        :help map            Alias for :help keys.
        :help bindings       Alias for :help keys.
        :help init           Init file path and available :set options.
        :help config         Alias for :help init.
        :help settings       Alias for :help init.

        :map                 With no arguments, same as :help keys.
                             With arguments, adds a mapping (see
                             :help commands → map / remap / unmap).

        Dismiss this pane with Escape, Enter, or q.
    """.trimIndent() + "\n"

    /**
     * :help init — show where the init file lives and which
     * `:set <opt>=<val>` options exist. Whenever we add a new
     * settable option, it gets a row here so the user can
     * discover it without reading source.
     */
    private fun formatInitHelp(): String {
        val initPath = XdgPaths.appConfigDir.resolve("init")
        val sb = StringBuilder()
        sb.appendLine("sane-graph-edit init file & settings")
        sb.appendLine()
        sb.appendLine("Init file:")
        sb.appendLine("  $initPath")
        sb.appendLine()
        sb.appendLine("  Loaded once at startup (and whenever `:source <path>` runs)")
        sb.appendLine("  against a different path). One command per line, `#` for")
        sb.appendLine("  comments. Contents are the same commands you'd type in the")
        sb.appendLine("  : bar, without the leading colon.")
        sb.appendLine()
        sb.appendLine("Example init file:")
        sb.appendLine("  # comments and blank lines are ignored")
        sb.appendLine("  map gt :next-tab<Enter>")
        sb.appendLine("  map gT :prev-tab<Enter>")
        sb.appendLine("  set timeoutlen=300")
        sb.appendLine()
        sb.appendLine("Available settings (use `:set <name>=<value>`):")
        sb.appendLine()
        sb.appendLine("  timeoutlen=<ms>   Milliseconds to wait for the next key in a")
        sb.appendLine("                    multi-key sequence (e.g. 500ms between `g` and")
        sb.appendLine("                    `t` for the `gt` mapping). Only applies when")
        sb.appendLine("                    the pressed key is a prefix of a longer")
        sb.appendLine("                    mapping AND has a standalone binding itself.")
        sb.appendLine("                    Default: ${DEFAULT_TIMEOUT_MS}. Current: $timeoutMs.")
        sb.appendLine()
        sb.appendLine("  spring-scale=<n>  Multiplier on the BB-spring target distance —")
        sb.appendLine("                    how spread-out layout-optimiser (`o`) makes the")
        sb.appendLine("                    graph. 1.0 is the baseline; smaller = tighter,")
        sb.appendLine("                    larger = looser. `-` / `=` adjust it by 0.9x /")
        sb.appendLine("                    1.11x per tap (and run an optimize pass).")
        sb.appendLine("                    Session-only. Default: 1.0. Current: ${graph_tools.LayoutOptimizer.springScale}.")
        sb.appendLine()
        sb.appendLine("Related:")
        sb.appendLine("  :source <path>    Load a different init file.")
        sb.appendLine("  :help keys        Show active key bindings.")
        sb.appendLine("  :help commands    Show all available commands.")
        return sb.toString()
    }

    /**
     * Format the full command-name registry plus the built-in
     * command-bar verbs (`:w`, `:map`, `:set`, …) as a two-column
     * alphabetical list. Used by `:help commands`.
     */
    private fun formatCommandList(): String {
        val registered = CommandRegistry.list()
        // Built-in :bar verbs (handled inline in executeCommandLine,
        // not registered in CommandRegistry).
        val builtins = listOf(
            "e <path>                — open file (new tab)",
            "export <path>           — write graph as SVG to <path>",
            "export-selection <path> — write selection as SVG",
            "help [topic]            — this page (topic: commands, keys)",
            "map <lhs> <rhs>         — add non-recursive key mapping",
            "noremap <lhs> <rhs>     — alias for :map",
            "q                       — close current tab",
            "q!                      — close current tab, no prompt",
            "remap <lhs> <rhs>       — add recursive key mapping",
            "set <opt>=<val>         — change a setting (e.g. timeoutlen)",
            "source <path>           — load commands from a file",
            "unmap <lhs>             — remove a user mapping",
            "w [<path>]              — save (to <path> if given)",
            "wq                      — save then close tab",
        )
        val sb = StringBuilder()
        sb.appendLine("Command-bar built-ins (type after `:` and press Enter):")
        sb.appendLine()
        for (entry in builtins) sb.append("  ").append(entry).append('\n')

        sb.appendLine()
        sb.appendLine("Registered command names (use as `:<name><Enter>` or in mapping RHSes):")
        sb.appendLine()
        val names = registered.sorted()
        val half = (names.size + 1) / 2
        val left = names.take(half)
        val right = names.drop(half)
        val colWidth = (names.maxOfOrNull { it.length } ?: 0) + 4
        for (i in left.indices) {
            val l = left[i].padEnd(colWidth)
            val r = right.getOrNull(i) ?: ""
            sb.append("  ").append(l).append(r).append('\n')
        }
        return sb.toString()
    }

    /**
     * Format all active key bindings grouped by base slot:
     * every letter a-z (with its variants: `a`, `A`, `<C-a>`,
     * `<C-S-a>`), every digit, then prefix keys and other
     * specials. Empty slots get a header with no entries so the
     * user can see what's free at a glance.
     */
    private fun formatBindings(): String {
        val sb = StringBuilder()

        // Hardcoded prefix keys in feedKey — not in defaults.
        val prefixKeyLabels = mapOf(
            "q" to "(record macro q<reg>)",
            "@" to "(replay macro @<reg>)",
            "m" to "(set mark m<reg>)",
            "'" to "(recall mark '<reg>)",
            ":" to "(open command bar)",
            "/" to "(search forward)",
            "?" to "(search backward)",
        )

        // Effective binding for a key (user mapping wins, then
        // default, then prefix label). Null = truly unbound.
        fun effective(key: String): String? {
            val userMapping = mappings[key]
            if (userMapping != null) return "→ ${userMapping.rhs}" +
                if (userMapping.recursive) "  [recursive]" else ""
            defaults[key]?.let { return it }
            prefixKeyLabels[key]?.let { return it }
            return null
        }

        // Classify each bound key to a slot. Returns:
        //   - "a".."z" for letter slots (single chars or
        //     angle-bracket tokens whose inner key is a letter)
        //   - "0".."9" for digit slots
        //   - multi-key sequences (more than one token) → first
        //     token's slot (if letter/digit) else "multi"
        //   - otherwise → "special"
        fun slotFor(key: String): String {
            val tokens = KeyNotation.tokenize(key)
            if (tokens.size > 1) {
                val first = slotFor(tokens[0])
                if (first in "a".."z" || first in "0".."9") return first
                return "multi"
            }
            val tok = tokens.single()
            if (tok.length == 1) {
                val ch = tok[0]
                return when {
                    ch.isLetter() -> ch.lowercaseChar().toString()
                    ch.isDigit() -> ch.toString()
                    else -> "special"
                }
            }
            // Angle-bracket token: parse to get the key name.
            val parsed = KeyNotation.parse(tok) ?: return "special"
            val name = parsed.keyName
            return when {
                name.length == 1 && name[0].isLetter() -> name[0].lowercaseChar().toString()
                name.length == 1 && name[0].isDigit() -> name.toString()
                else -> "special"
            }
        }

        // Collect all bound keys (defaults + mappings + hardcoded
        // prefixes) and bucket by slot.
        val allKeys = (defaults.keys + mappings.keys + prefixKeyLabels.keys).toSet()
        val bySlot = mutableMapOf<String, MutableList<String>>()
        for (key in allKeys) {
            bySlot.getOrPut(slotFor(key)) { mutableListOf() }.add(key)
        }
        // Sort each slot's entries: plain char first, then
        // uppercase, then angle-bracket tokens, ordered by length
        // then lex.
        val slotOrder = Comparator<String> { a, b ->
            val la = a.length
            val lb = b.length
            if (la != lb) la.compareTo(lb) else a.compareTo(b)
        }
        for (list in bySlot.values) list.sortWith(slotOrder)

        sb.appendLine("Active key bindings (user mappings override defaults; empty slots are free):")

        fun emitSlot(header: String, keys: List<String>) {
            sb.appendLine()
            sb.append("- ").append(header).append(':').append('\n')
            for (key in keys) {
                val v = effective(key) ?: continue
                sb.append("    ").append(key.padEnd(10)).append(' ').append(v).append('\n')
            }
        }

        // Letters a-z — show every slot, even empty.
        for (c in 'a'..'z') {
            emitSlot(c.toString(), bySlot[c.toString()] ?: emptyList())
        }
        // Digits 0-9 — show every slot, even empty.
        for (c in '0'..'9') {
            emitSlot(c.toString(), bySlot[c.toString()] ?: emptyList())
        }
        // Special (Esc, CR, Tab, symbols, …).
        bySlot["special"]?.let { emitSlot("special", it) }
        // Multi-key sequences that didn't start with a letter/digit.
        bySlot["multi"]?.let { emitSlot("multi-key", it) }

        return sb.toString()
    }

    /**
     * Show [text] in a bottom-docked message area (GUI mode)
     * or print to stdout (headless mode). Falls back to stdout
     * if the message area isn't available (e.g., unit tests).
     */
    private fun showHelpText(text: String) {
        val headless = java.awt.GraphicsEnvironment.isHeadless()
        if (headless) {
            print(text)
            return
        }
        val msg = activeView?.messageArea
        if (msg != null) {
            msg.show(text)
        } else {
            print(text)
        }
    }

    companion object {
        const val MAX_RECURSION = 1000
        const val DEFAULT_TIMEOUT_MS = 500

        /** Build the defaults table from the editor's hardcoded bindings. */
        fun defaultBindings(): Map<String, String> = mapOf(
            // Structural edits
            "e" to "edge-forward",
            "E" to "edge-backward",
            "v" to "new-node-edge-forward",
            "V" to "new-node-edge-backward",
            "a" to "append-forward",
            "A" to "append-backward",
            "c" to "clear-edges",
            "d" to "delete",
            "D" to "delete-reconnect",
            "o" to "optimize",
            "O" to "optimize-restrict",

            // Selection & navigation
            "i" to "invert-selection",
            "t" to "select-closure-forward",
            "T" to "select-closure-backward",
            "l" to "select-linked-forward",
            "L" to "select-linked-backward",
            "w" to "unselect-oldest-forward",
            "W" to "unselect-oldest-backward",
            "0" to "bound-screen",
            "1" to "center-screen",
            "-" to "spring-shorter",
            "=" to "spring-longer",
            "<Space>" to "edit-node",
            "<S-Space>" to "select-and-edit-node",

            // Filtering
            "h" to "hide",
            "H" to "unhide",

            // Style
            "f" to "paste-format",
            "F" to "copy-format",

            // History
            "u" to "undo",
            "U" to "redo",

            // Search navigation
            "n" to "search-next",
            "N" to "search-prev",

            // Movement
            "g" to "grab",
            "gt" to "next-tab",
            "gT" to "prev-tab",
            "G" to "subgraph-focus",

            // Modifier-based
            "<C-s>" to "save",
            "<C-S-s>" to "save-as",
            "<C-o>" to "open",
            "<C-n>" to "new-tab",
            "<C-t>" to "new-tab",
            "<C-w>" to "close-tab",
            "<C-e>" to "export-svg",
            "<C-S-e>" to "export-selection",
            "<C-Tab>" to "next-tab",
            "<C-S-Tab>" to "prev-tab",
            "<C-PgDn>" to "next-tab",
            "<C-PgUp>" to "prev-tab",
            "<C-a>" to "select-all",
            "<C-c>" to "copy",
            "<C-x>" to "cut",
            "<C-v>" to "paste",
            "<C-z>" to "undo",
            "<C-S-z>" to "redo",
            "<C-r>" to "redo",
            "<Esc>" to "deselect",
        )
    }
}
