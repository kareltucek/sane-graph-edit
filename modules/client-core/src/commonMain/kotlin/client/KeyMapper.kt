package client

import Graph

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
    /** For the timeout that resolves an ambiguous key sequence. */
    private val scheduler: Scheduler,
    var timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    /**
     * Keys that stand for other keys out of the box
     * ([defaultKeyMappings]), as a user's `noremap` would make them:
     * the keys on the right are looked up in [defaults]. A user
     * mapping of the same keys comes first.
     */
    private val defaultMappings: Map<String, String> = emptyMap(),
) {
    /** Names of the commands [commandExecutor] knows, for `:help commands`. */
    var commandNames: () -> List<String> = { emptyList() }

    /** `:set <name>=<value>` settings that belong to the application rather than to the client. */
    val applicationSettings: MutableMap<String, (String) -> Unit> = mutableMapOf()

    data class Mapping(val rhs: String, val recursive: Boolean)

    /**
     * Dispatch modes. [Normal] is the usual mapper behaviour —
     * keys resolve against user mappings and defaults. [Transform]
     * is active during a modal gesture (grab / rotate / scale):
     * only the keys listed in [transformModeBindings] fire; every
     * other key is swallowed so the user can't accidentally
     * trigger other commands mid-gesture. The active gesture's
     * mouse-controller method is responsible for flipping this
     * field on entry and restoring [Normal] on commit / cancel.
     */
    enum class Mode { Normal, Transform }

    var mode: Mode = Mode.Normal
        set(value) {
            val changed = field != value
            field = value
            if (changed) modeListener?.invoke(value)
        }

    /**
     * Called whenever [mode] changes. Status bar wires in here so
     * it can refresh the visible mode indicator.
     */
    var modeListener: ((Mode) -> Unit)? = null

    /**
     * Key → command bindings active in [Mode.Transform]. Entries
     * here shadow everything in [defaults] and [mappings] while a
     * modal gesture is live; anything not listed is swallowed.
     *
     * Populated at startup by [installDefaultTransformBindings].
     * Each gesture's toggle key (`s`, `g`, …) re-enters its own
     * `startOrEnd*` command here; because the toggle methods
     * refuse to start a new gesture while another is active,
     * pressing the "wrong" toggle mid-gesture is a harmless
     * no-op rather than a state clobber.
     */
    private val transformModeBindings = mutableMapOf<String, String>()

    /**
     * Register [command] to fire for [key] while the mapper is in
     * [Mode.Transform]. Called by startup wiring (see
     * [installDefaultTransformBindings]); exposed publicly so
     * init-file hooks could in principle add more.
     */
    fun bindInTransformMode(key: String, command: String) {
        transformModeBindings[key] = command
    }

    /**
     * Seed [transformModeBindings] with the built-in allowlist:
     *
     *  - `x` / `y` — toggle axis locks (Blender semantics).
     *  - `s` / `g` — re-enter the gesture's own toggle so the
     *    second tap commits. Each `startOrEnd*` method guards
     *    against starting a new gesture over an active one, so
     *    pressing the wrong letter mid-gesture is a no-op.
     *  - `<Esc>` — route through the normal deselect path,
     *    which cancels the active modal first.
     *
     * Call once after construction, before any gesture can run.
     */
    fun installDefaultTransformBindings() {
        // Blender convention: the letter you press names the
        // axis that *remains free*. So press `x` to scale / move
        // along X only (Y locked); press `y` for Y only (X
        // locked). Tapping the same axis twice releases the
        // constraint; tapping the other axis swaps (radio-style)
        // so only one axis is locked at a time. `sxy` ends with
        // Y free / X locked, not with both locked.
        bindInTransformMode("x", "constrain-axis-x")
        bindInTransformMode("y", "constrain-axis-y")
        bindInTransformMode("s", "scale")
        bindInTransformMode("g", "grab")
        bindInTransformMode("<Esc>", "deselect")
    }

    private val mappings = mutableMapOf<String, Mapping>()
    private var pending: String = ""
    /** Cancels the running timeout, when there is one. */
    private var pendingTimer: (() -> Unit)? = null

    /** Callback invoked to execute a resolved command name. */
    var commandExecutor: ((String, EditorView) -> Unit)? = null

    /** Callback invoked when `:` is replayed (opens the command bar). */
    var commandBarOpener: ((EditorView) -> Unit)? = null

    /** The view keys and commands act on — set by the key listener before feeding keys. */
    var activeView: EditorView? = null

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
     * `@` replays one, `m` sets a mark (`m+` adds to it, `m-`
     * removes from it), `'` recalls a mark. Null when no prefix
     * is active.
     */
    private enum class PrefixAction { RecordMacro, ReplayMacro, SetMark, AddMark, RemoveMark, RecallMark }
    private var waitingForPrefix: PrefixAction? = null

    /** True if currently recording a macro. */
    val isRecording: Boolean get() = recordingRegister != null

    /** True if replaying a macro (suppresses recording to avoid feedback loops). */
    private var replaying: Boolean = false

    /** Callback for setting a mark on the current selection. Set by Window. */
    var markSetter: ((EditorView, Char, MarkChange) -> Unit)? = null

    /** Callback for recalling a mark. Set by Window. */
    var markRecaller: ((EditorView, Char) -> Unit)? = null

    /**
     * Callback for an unmapped `g<letter>`: follow the selected
     * node's `link-<letter>`. Returns false when there is no such
     * link — reporting it, unless the third argument (quiet) is set.
     * Set by Window.
     */
    var linkFollower: ((EditorView, Char, Boolean) -> Boolean)? = null

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

    /**
     * A button of the mouse ([LEFT_MOUSE], [RIGHT_MOUSE]) was used
     * on a node. If it is mapped (`:map <RightMouse> zO`), or stands
     * for something in explore mode ([EXPLORE_MOUSE]), [select] is
     * called, which makes that node the selection, and then the keys
     * it stands for are run. False when it stands for nothing: the
     * button then does what it does out of the box.
     */
    fun mouseKey(key: String, select: () -> Unit): Boolean {
        val exploring = activeView?.let { Explore.isOn(it) } == true
        val mapping = mappings[key] ?: EXPLORE_MOUSE[key]?.takeIf { exploring }?.let { Mapping(it, recursive = false) }
        mapping?.let {
            select()
            replayRhs(it.rhs, it.recursive)
        }
        return mapping != null
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
        val content = activeView?.content
        val busy = content?.isBusy == true
        if (held.isEmpty() && !busy) {
            dispatchKey(key)
        } else {
            held.addLast(key to replaying)
            if (busy) content?.onIdle = ::resume else resume()
        }
    }

    /**
     * Keys that arrived while the view was waiting for the editor
     * ([ViewContent.isBusy]), each with whether it came from a
     * macro: a key assumes that what the key before it started is
     * done (`:wq`, a macro that follows a link and selects there).
     */
    private val held = ArrayDeque<Pair<String, Boolean>>()

    private fun resume() {
        // one of them may start the next wait
        while (held.isNotEmpty() && activeView?.content?.isBusy != true) {
            val (key, fromMacro) = held.removeFirst()
            val before = replaying
            replaying = fromMacro
            try {
                dispatchKey(key)
            } finally {
                replaying = before
            }
        }
    }

    private fun dispatchKey(key: String) {
        // --- Transform mode: restricted allowlist ---
        // While a modal gesture (grab / rotate / scale) is live,
        // only the keys in `transformModeBindings` fire. Everything
        // else is swallowed so an accidental `u` / `d` / etc. can't
        // clobber the gesture. `<Esc>` is included so the user's
        // first Escape still cancels the gesture (routes through
        // the `deselect` command → GestureController.cancelActiveModal).
        if (mode == Mode.Transform) {
            transformModeBindings[key]?.let { executeCommand(it) }
            // Don't record in macros, don't accumulate in pending —
            // Transform mode is atomic, one keystroke at a time.
            return
        }

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
            if (prefix == PrefixAction.SetMark && (key == "+" || key == "-")) {
                // `m+a`, `m-a`: the letter is still to come
                waitingForPrefix = if (key == "+") PrefixAction.AddMark else PrefixAction.RemoveMark
            } else if (key == PINNED_MARK) {
                // the nodes pinned in explore mode, not a mark kept on the nodes
                when (prefix) {
                    PrefixAction.SetMark -> activeView?.let { Explore.mark(it, MarkChange.Set) }
                    PrefixAction.AddMark -> activeView?.let { Explore.mark(it, MarkChange.Add) }
                    PrefixAction.RemoveMark -> activeView?.let { Explore.mark(it, MarkChange.Remove) }
                    PrefixAction.RecallMark -> activeView?.let { Explore.recall(it) }
                    else -> Unit
                }
            } else if (letter != null && letter.isLetterOrDigit() && key.length == 1) {
                when (prefix) {
                    PrefixAction.RecordMacro -> startRecording(letter)
                    PrefixAction.ReplayMacro -> replayRegister(letter)
                    PrefixAction.SetMark -> activeView?.let { markSetter?.invoke(it, letter, MarkChange.Set) }
                    PrefixAction.AddMark -> activeView?.let { markSetter?.invoke(it, letter, MarkChange.Add) }
                    PrefixAction.RemoveMark -> activeView?.let { markSetter?.invoke(it, letter, MarkChange.Remove) }
                    PrefixAction.RecallMark -> activeView?.let { markRecaller?.invoke(it, letter) }
                }
            }
            return
        }

        // The prefix keys below start a sequence of their own; after
        // a waiting key they are its continuation (`gm` = link-m).
        val startsSequence = pending.isEmpty()

        // `q` toggles recording. If recording, stop. If not
        // recording, wait for the next key as register name.
        if (key == "q" && startsSequence) {
            if (isRecording) {
                stopRecording()
            } else {
                waitingForPrefix = PrefixAction.RecordMacro
            }
            return
        }

        // `@` starts replay — wait for the register name.
        if (key == "@" && startsSequence) {
            waitingForPrefix = PrefixAction.ReplayMacro
            return
        }

        // `m` sets a mark — wait for the register letter.
        if (key == "m" && startsSequence) {
            waitingForPrefix = PrefixAction.SetMark
            return
        }

        // `'` recalls a mark — wait for the register letter.
        if (key == "'" && startsSequence) {
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
                if (accumulated.length > 1 && !followLink(accumulated)) {
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

        val standsFor = defaultMappings[keys]
        if (standsFor != null) {
            replayRhs(standsFor, recursive = false)
            return
        }

        val command = defaults[keys]
        if (command != null) {
            executeCommand(command)
        }
    }

    /**
     * True if typing `g<letter>` follows `link-<letter>`: either the
     * keys are unclaimed (see [followLink]), or they run the
     * `follow-file-link` command (`gf` for `link-f`).
     */
    fun followsLink(letter: Char): Boolean {
        val keys = "g$letter"
        val unclaimed = !hasExactMatch(keys) && !isPrefixOfLonger(keys)
        val isGf = letter == 'f' && keys !in mappings && defaults[keys] == "follow-file-link"
        return unclaimed || isGf
    }

    /**
     * `g<letter>` that no mapping claims: try the selected node's
     * `link-<letter>` via [linkFollower]. True if the keys were
     * consumed: the link was followed, or there is none and that was
     * reported — a failed `gm` must not grab. Only a letter that
     * means something during a grab (`gx`: grab along X) is left to
     * be resolved as `g` + the letter.
     */
    private fun followLink(accumulated: String): Boolean {
        val letter = accumulated.getOrNull(1)
        val isGLetter = accumulated.length == 2 && accumulated[0] == 'g' && letter != null && letter.isLetter()
        val gv = activeView
        val follower = linkFollower
        return if (isGLetter && gv != null && follower != null) {
            val continuesGrab = letter.toString() in transformModeBindings
            follower(gv, letter!!, continuesGrab) || !continuesGrab
        } else {
            false
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
            utils.Platform.warn("sane-graph-edit: max mapping recursion depth ($MAX_RECURSION) exceeded, aborting")
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

            // The longest run of keys from here that means something: `gt` is one key sequence, not `g` and `t`.
            // Recursive: the full pipeline (mappings + defaults), with the depth against loops. Non-recursive: defaults only.
            val known = { keys: String -> (recursive && keys in mappings) || keys in defaultMappings || keys in defaults }
            val length = (tokens.size - i downTo 1).firstOrNull { n -> known(tokens.subList(i, i + n).joinToString("")) } ?: 1
            val keys = tokens.subList(i, i + length).joinToString("")
            val mapping = mappings[keys].takeIf { recursive }
            val standsFor = defaultMappings[keys]
            when {
                mapping != null -> replayRhs(mapping.rhs, mapping.recursive, depth + 1)
                standsFor != null -> replayRhs(standsFor, recursive = false, depth + 1)
                else -> defaults[keys]?.let { executeCommand(it) }
            }
            i += length
        }
    }

    // --- helpers ---

    private fun hasExactMatch(keys: String): Boolean =
        keys in mappings || keys in defaultMappings || keys in defaults

    private fun isPrefixOfLonger(prefix: String): Boolean =
        (mappings.keys + defaultMappings.keys + defaults.keys).any { it.startsWith(prefix) && it != prefix }

    /**
     * An operator that was run and waits for its motion (`zf` waits
     * for `t`, `T`, `l` or `L`): the next command is not run but
     * given to it. Null when none waits.
     */
    private var waitingOperator: String? = null

    private fun executeCommand(name: String) {
        val gv = activeView ?: return
        // nodes still on their way stop before a command acts (an undo takes back their move whole); a gesture's own keys leave them be
        if (mode != Mode.Transform) gv.gestures.finishSettling()
        // a key the user holds will be let go; one of a macro or a mapping will not
        gv.byHeldKey = !replaying
        val operator = waitingOperator
        when {
            operator != null -> {
                waitingOperator = null
                // Escape takes the operator back
                if (name != "deselect") executeCommandLine("$operator $name")
            }
            name in OPERATORS -> waitingOperator = name
            // a binding to a command with its arguments
            ' ' in name -> executeCommandLine(name)
            else -> commandExecutor?.invoke(name, gv)
        }
    }

    /**
     * Resolve a path argument from a command-bar line. Expands a
     * leading `~/` to the user's home directory and converts the
     * result to an absolute [Path] (a relative path is
     * relative to the [Workspace]). Shells normally
     * expand `~` before invoking us, but `~` typed inside the
     * in-app `:` bar (or inside a `-e` argument that's quoted at
     * the shell level) arrives literally — so we expand it
     * ourselves.
     */
    private fun resolvePath(raw: String): Path {
        val trimmed = raw.trim()
        val expanded = when {
            trimmed == "~" -> Fs.home
            trimmed.startsWith("~/") -> Fs.home + trimmed.substring(1)
            else -> trimmed
        }
        return Workspace.resolve(Fs.path(expanded))
    }

    /**
     * Parse `<fmt> <path>` from a :export-style command. Returns
     * `(fmt, path)` on success or null with an error printed if
     * the format is missing/unknown. [verb] is used in the error
     * message so :export and :export-selection both report
     * themselves correctly.
     */
    private fun parseExportArgs(args: String, verb: String): Pair<String, String>? {
        val split = args.trim().split("\\s+".toRegex(), limit = 2)
        if (split.size < 2 || split[0].isBlank() || split[1].isBlank()) {
            utils.Platform.warn("sane-graph-edit: :$verb requires <fmt> <path> (fmt: svg|dot)")
            return null
        }
        val fmt = split[0].lowercase()
        if (fmt != "svg" && fmt != "dot") {
            utils.Platform.warn("sane-graph-edit: :$verb unknown format '$fmt' (expected svg|dot)")
            return null
        }
        return fmt to split[1]
    }

    private fun renderExport(g: Graph, nodes: Set<graph_tools.Node>, fmt: String): String =
        when (fmt) {
            "dot" -> parser_dot.Serializer(g).serializeSubset(nodes)
            else -> export.SvgWriter.write(g, nodes)
        }

    /**
     * Execute a command-bar line. Config commands (`map`, `set`,
     * `source`, etc.) work without a view; graph commands
     * (`:w`, `:q`, `:e`, named commands) require one and are
     * silently skipped if [activeView] is null (which happens
     * during init-file loading before the UI is up).
     */
    fun executeCommandLine(line: String) {
        activeView?.gestures?.finishSettling()
        activeView?.byHeldKey = false
        val parts = line.split("\\s+".toRegex(), limit = 2)
        val cmd = parts[0]
        val args = parts.getOrNull(1)

        // --- Config commands (no view needed) ---
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
            "set", "se" -> {
                if (args != null) {
                    val eqIdx = args.indexOf('=')
                    if (eqIdx > 0) {
                        val optName = args.substring(0, eqIdx).trim()
                        val optVal = args.substring(eqIdx + 1).trim()
                        applySetting(optName, optVal)
                    } else {
                        applyFlag(args.trim())
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
                    gv.content.saveCopy(resolvePath(args).toAbsolutePath().normalize()) { written ->
                        written.onFailure { utils.Platform.warn("sane-graph-edit: :w failed: ${it.message}") }
                    }
                } else {
                    commandExecutor?.invoke("save", gv)
                }
            }
            "wa" -> {
                // every graph that has a file, and every file nodes keep their text in; a graph without a name is told of
                val views = (listOf(gv) + gv.shell.views()).distinct().filter { it.content.isDirty }
                val (named, unnamed) = views.partition { it.content.currentFile != null || it.content.sourceView != null }
                named.forEach { it.content.save() }
                FileContent.saveAll(gv)
                if (unnamed.isNotEmpty()) gv.shell.message("wa: ${unnamed.size} unsaved without a file name (:w <file> in each)")
            }
            "q" -> gv.shell.quit(force = false, all = false)
            "q!" -> gv.shell.quit(force = true, all = false)
            "qa" -> gv.shell.quit(force = false, all = true)
            "qa!" -> gv.shell.quit(force = true, all = true)
            "wq" -> {
                gv.content.save { saved -> if (saved) gv.shell.quit(force = false, all = false) }
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
                // :export <fmt> <path> — write the whole graph in <fmt> (svg|dot).
                // No-arg form falls back to the SVG dialog (interactive only).
                if (args != null) {
                    val parsed = parseExportArgs(args, "export")
                    if (parsed != null) {
                        val (fmt, path) = parsed
                        try {
                            val visible = gv.g.visibleNodes()
                            Fs.writeText(
                                resolvePath(path),
                                renderExport(gv.g, visible, fmt),
                            )
                        } catch (t: Throwable) {
                            utils.Platform.warn("sane-graph-edit: :export failed: ${t.message}")
                        }
                    }
                } else {
                    commandExecutor?.invoke("export-svg", gv)
                }
            }
            "export-selection" -> {
                // :export-selection <fmt> <path> — write only current selection in <fmt>.
                if (args != null) {
                    val parsed = parseExportArgs(args, "export-selection")
                    if (parsed != null) {
                        val (fmt, path) = parsed
                        try {
                            val sel = gv.g.selectedNodes.takeIf { it.isNotEmpty() }
                                ?: gv.g.nodes.toSet()
                            Fs.writeText(
                                resolvePath(path),
                                renderExport(gv.g, sel, fmt),
                            )
                        } catch (t: Throwable) {
                            utils.Platform.warn("sane-graph-edit: :export-selection failed: ${t.message}")
                        }
                    }
                } else {
                    commandExecutor?.invoke("export-selection", gv)
                }
            }
            "e" -> {
                if (args != null) {
                    try {
                        gv.shell.edit(resolvePath(args))
                    } catch (t: Throwable) {
                        utils.Platform.warn("sane-graph-edit: :e failed: ${t.message}")
                    }
                } else {
                    // vim: bare :e re-reads the current file
                    commandExecutor?.invoke("reload", gv)
                }
            }
            "e!" -> gv.content.reload(force = true)
            "content-type" -> {
                val type = graph_tools.NodeType.parse(args)
                when {
                    // without an argument: say what the node is
                    args.isNullOrBlank() -> gv.shell.message(
                        (gv.g.selectedNodes.toList().ifEmpty { listOfNotNull(gv.g.lastActiveNode) })
                            .map { graph_tools.NodeType.of(it) }.distinct()
                            .joinToString(", ") { it.attribute ?: graph_tools.NodeType.Kind.Plain.id }
                            .ifEmpty { "content-type: no node is selected" },
                    )
                    type == null -> gv.shell.message("content-type: `${args.trim()}` is not one (plain, markdown, code, code-<language>, each may be followed by :<file>; terminal)")
                    else -> {
                        FileContent.forget(gv, gv.g.selectedNodes.toList().ifEmpty { listOfNotNull(gv.g.lastActiveNode) })
                        Actions.typeSelection(gv, type)
                        FileContent.reconcile(gv)
                    }
                }
            }
            "z" -> {
                val text = args?.trim().orEmpty()
                val z = text.removePrefix("=").toDoubleOrNull()
                when {
                    // without an argument: say where the node is
                    text.isEmpty() -> gv.shell.message(
                        (gv.g.selectedNodes.toList().ifEmpty { listOfNotNull(gv.g.lastActiveNode) })
                            .map { graph_tools.Depth.of(it) }.distinct()
                            .joinToString(", ") { with(utils.Utils) { "z = ${it.toNiceString()}" } }
                            .ifEmpty { "z: no node is selected" },
                    )
                    z == null -> gv.shell.message("z: `$text` is not a number (z 100, z +50, z -50, z =-50)")
                    // a sign means from where the node is; `z 0`, `z 100` and `z =-100` say where to
                    else -> {
                        Actions.depthSelection(gv, z, relative = text.startsWith("+") || text.startsWith("-"))
                        // a graph with depth in it is shown with it
                        if (gv.g.nodes.any { graph_tools.Depth.of(it) != 0.0 }) gv.viewTransform.threeD = true
                    }
                }
            }
            "fold", "unfold", "toggle-fold" -> {
                val how = when (cmd) {
                    "fold" -> ZCommands.Fold.Fold
                    "unfold" -> ZCommands.Fold.Unfold
                    else -> ZCommands.Fold.Toggle
                }
                // the motion as its command, or as the key that runs it out of the box (`fold t`)
                val motion = args?.trim().orEmpty()
                ZCommands.fold(gv, how, defaults[motion]?.takeIf { ZCommands.isMotion(it) } ?: motion, cmd)
            }
            "browse" -> {
                // :browse [dir] — file-tree tab; no arg = working directory
                val dir = args?.let { resolvePath(it) } ?: Workspace.dir
                gv.shell.browse(dir)
            }
            "cd" -> {
                // :cd <dir> — switch the workspace; no arg = show it
                val dir = args?.let { resolvePath(it) }
                when {
                    dir == null -> gv.shell.message(Workspace.dir.toString())
                    !Fs.isDirectory(dir) -> gv.shell.message("cd: not a directory: $dir")
                    else -> gv.shell.switchWorkspace(dir)
                }
            }
            else -> {
                // Try as a registered command name
                commandExecutor?.invoke(cmd, gv)
            }
        }
    }

    private fun applySetting(name: String, value: String) {
        when (name) {
            "timeoutlen" -> value.toIntOrNull()?.let { timeoutMs = it }
            "spring-scale" -> value.toDoubleOrNull()?.let { graph_tools.LayoutOptimizer.springScale = it }
            "spring-scale-step" -> value.toDoubleOrNull()?.let { graph_tools.LayoutOptimizer.springScaleStep = it }
            "picker" -> FilePicker.command = value.ifBlank { FilePicker.DEFAULT_COMMAND }
            "wall-scale" -> value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { graph_tools.ViewModes.wallScale = it.coerceIn(0.05, 20.0) }
            // a level or several, comma-separated
            "suns" -> value.split(',').map { it.trim().toIntOrNull()?.takeIf { l -> l >= 0 } }.takeIf { it.isNotEmpty() && null !in it }
                ?.let { levels -> activeView?.gestures?.showSuns(levels.filterNotNull().toSet()) }
            else -> applicationSettings[name]?.invoke(value) ?: utils.Platform.warn("sane-graph-edit: unknown setting '$name'")
        }
    }

    /**
     * vim-style boolean options: `:set <flag>` turns it on,
     * `:set no<flag>` off, `:set <flag>!` / `:set inv<flag>` toggles.
     * The modes are global, so every tab repaints; the status bar of
     * the active tab shows them (others refresh on tab switch).
     */
    private fun applyFlag(spec: String) {
        val (name, change) = when {
            spec.endsWith("!") -> spec.dropLast(1) to { v: Boolean -> !v }
            spec.startsWith("inv") -> spec.removePrefix("inv") to { v: Boolean -> !v }
            spec.startsWith("no") && spec.removePrefix("no") in flagNames ->
                spec.removePrefix("no") to { _: Boolean -> false }
            else -> spec to { _: Boolean -> true }
        }
        when (name) {
            "unhide" -> graph_tools.ViewModes.unhide = change(graph_tools.ViewModes.unhide)
            "show-parents" -> graph_tools.ViewModes.showParents = change(graph_tools.ViewModes.showParents)
            "show-children", "show-childs" -> graph_tools.ViewModes.showChildren = change(graph_tools.ViewModes.showChildren)
            "dynamic" -> graph_tools.ViewModes.dynamic = change(graph_tools.ViewModes.dynamic)
            "walls" -> graph_tools.ViewModes.walls = change(graph_tools.ViewModes.walls)
            "discard" -> graph_tools.ViewModes.discard = change(graph_tools.ViewModes.discard)
            // of the graph shown, not of all of them
            "explore" -> activeView?.let { Explore.set(it, change(Explore.isOn(it))) }
            "3d" -> activeView?.let { it.viewTransform.threeD = change(it.viewTransform.threeD) }
            "suns" -> activeView?.gestures?.let { if (change(it.sunsShown)) it.showSuns(null) else it.hideSuns() }
            else -> applicationFlags[name]?.let { flag ->
                flag.on = change(flag.on)
                flag.set(flag.on)
            } ?: utils.Platform.warn("sane-graph-edit: unknown setting '$spec'")
        }
        activeView?.shell?.modesChanged()
    }

    /** A flag that is the application's own rather than the client's: whether it is [on], and what to tell the application when that changes. */
    class ApplicationFlag(var on: Boolean, val set: (Boolean) -> Unit)

    /** `:set <name>` / `no<name>` / `<name>!` flags that belong to the application, e.g. `nvim-theme`. */
    val applicationFlags: MutableMap<String, ApplicationFlag> = mutableMapOf()

    /** [FLAG_SETTINGS] and the application's, plus aliases (not offered by completion). */
    private val flagNames: Set<String> get() = FLAG_SETTINGS.toSet() + applicationFlags.keys + "show-childs"

    /**
     * Load an init file. Each line is a command-bar command
     * (`map`, `remap`, `noremap`, `set`, etc.). Comments start
     * with `#`. Blank lines are ignored.
     */
    fun loadInitFile(path: Path) {
        if (!Fs.exists(path)) return
        try {
            Fs.readText(path).lines().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                executeCommandLine(line)
            }
        } catch (t: Throwable) {
            utils.Platform.warn("sane-graph-edit: failed to load init file $path: ${t.message}")
        }
    }

    // --- timer ---

    /**
     * A modifier (Shift, Ctrl, …) went down: if a key sequence is
     * waiting for its timeout, restart the wait, so reaching for Shift
     * in `gT` doesn't let `g` fire on its own (and grab eat the `T`).
     */
    fun modifierPressed() {
        if (pendingTimer != null) {
            cancelTimer()
            startTimeout()
        }
    }

    /**
     * The mouse moved: if the key waiting for its timeout starts a
     * mouse gesture (`g` = grab, which could still become `gt`), the
     * gesture starts now instead of after the timeout.
     */
    fun mouseMoved() {
        val gestureWaiting = pendingTimer != null && pending !in mappings && defaults[pending] in MOUSE_GESTURES
        if (gestureWaiting) {
            cancelTimer()
            onTimeout()
        }
    }

    private fun startTimeout() {
        pendingTimer = scheduler.after(timeoutMs) { onTimeout() }
    }

    private fun cancelTimer() {
        pendingTimer?.invoke()
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
        val initPath = Fs.configDir.resolve("init")
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
        sb.appendLine("  map gt :tabnext<Enter>")
        sb.appendLine("  map gT :tabprevious<Enter>")
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
        sb.appendLine("  spring-scale=<n>  Multiplier on the layout optimiser's distance")
        sb.appendLine("                    forces — both edge attraction (connected nodes")
        sb.appendLine("                    pull toward a target distance) and node")
        sb.appendLine("                    repulsion (unconnected nodes push apart when")
        sb.appendLine("                    too close). 1.0 is the baseline; smaller =")
        sb.appendLine("                    tighter whole graph, larger = looser.")
        sb.appendLine("                    :spring-shorter / :spring-longer adjust it")
        sb.appendLine("                    (and run an optimize pass). Node")
        sb.appendLine("                    repulsion floors at 1x radii sum to avoid")
        sb.appendLine("                    overlap.")
        sb.appendLine("                    Session-only. Default: 1.0. Current: ${graph_tools.LayoutOptimizer.springScale}.")
        sb.appendLine()
        sb.appendLine("  spring-scale-step=<n>")
        sb.appendLine("                    Per-second multiplier for :spring-shorter /")
        sb.appendLine("                    :spring-longer. Default 0.8 = 'shrink by 20%")
        sb.appendLine("                    over one second held'. Values closer to 1.0 are")
        sb.appendLine("                    gentler; lower values are more aggressive.")
        sb.appendLine("                    Session-only. Default: 0.8. Current: ${graph_tools.LayoutOptimizer.springScaleStep}.")
        sb.appendLine()
        sb.appendLine("  picker=<command>  Shell command behind Ctrl+P (pick-file), run with")
        sb.appendLine("                    `sh -c` in the background. Env: SGE_DIR (the")
        sb.appendLine("                    workspace directory), SGE_FILE. It prints")
        sb.appendLine("                    `path[:nodeid]` lines (relative to SGE_DIR); the")
        sb.appendLine("                    first opens in this tab, the rest in new tabs.")
        sb.appendLine("                    Opening a terminal is its job. Default: xterm +")
        sb.appendLine("                    find + fzf, window class sge-picker.")
        sb.appendLine("                    Empty value = default.")
        sb.appendLine()
        sb.appendLine("Modes (on/off: `:set <mode>`, `:set no<mode>`, toggle `:set <mode>!`;")
        sb.appendLine("shown in the status bar while on; global, session-only):")
        sb.appendLine()
        sb.appendLine("  unhide            Traversal (select linked / closure, G) also")
        sb.appendLine("                    steps into hidden nodes and unhides the ones")
        sb.appendLine("                    it selects (one undo step).")
        sb.appendLine("                    Current: ${if (graph_tools.ViewModes.unhide) "on" else "off"}.")
        sb.appendLine()
        sb.appendLine("  show-parents      Also show hidden nodes that are selected, or")
        sb.appendLine("                    parents of a non-hidden or selected node.")
        sb.appendLine("                    Display only; hide levels are untouched.")
        sb.appendLine("                    Current: ${if (graph_tools.ViewModes.showParents) "on" else "off"}.")
        sb.appendLine()
        sb.appendLine("  show-children     Same, for children. Alias: show-childs.")
        sb.appendLine("                    Current: ${if (graph_tools.ViewModes.showChildren) "on" else "off"}.")
        sb.appendLine()
        sb.appendLine("  explore           Explore mode (key `x`; `x` or Escape leaves it), of the")
        sb.appendLine("                    graph shown: only the")
        sb.appendLine("                    pinned nodes are shown, at first the selected ones,")
        sb.appendLine("                    with their parents and children faint. A click pins")
        sb.appendLine("                    the node and its children, one generation")
        sb.appendLine("                    (:explore-show). A right click folds: all pinned")
        sb.appendLine("                    below the node that is not below another pinned")
        sb.appendLine("                    node too, or with none the node itself")
        sb.appendLine("                    (:explore-hide). The pinned nodes are the mark `!`")
        sb.appendLine("                    (m! m+! m-! '!). Nodes that come to be seen are placed")
        sb.appendLine("                    as with `dynamic`. Nothing is hidden for real: leaving")
        sb.appendLine("                    the mode shows what was shown before.")
        sb.appendLine("                    The buttons can be mapped: <LeftMouse>, <RightMouse>.")
        sb.appendLine("                    Current: ${if (activeView?.let { Explore.isOn(it) } == true) "on" else "off"}.")
        sb.appendLine()
        sb.appendLine("  dynamic           Nodes that become visible are moved next to")
        sb.appendLine("                    their visible neighbours, left to right:")
        sb.appendLine("                    children right of their parents, parents left")
        sb.appendLine("                    of their children, stacked without overlap.")
        sb.appendLine("                    A real move: one undo step, saved with the file.")
        sb.appendLine("                    Current: ${if (graph_tools.ViewModes.dynamic) "on" else "off"}.")
        sb.appendLine()
        sb.appendLine("  walls             In a headset the graph is on the walls of the")
        sb.appendLine("                    room, bent around it, its middle straight ahead;")
        sb.appendLine("                    made smaller to fit, but in explore mode, where")
        sb.appendLine("                    it is as big as it is. Nothing is moved.")
        sb.appendLine("                    Current: ${if (graph_tools.ViewModes.walls) "on" else "off"}.")
        sb.appendLine()
        sb.appendLine("  wall-scale=<n>    How many millimetres of a wall a unit of the graph")
        sb.appendLine("                    is (walls). 1: a node 150 units wide is 15 cm.")
        sb.appendLine("                    Current: ${graph_tools.ViewModes.wallScale}.")
        sb.appendLine()
        sb.appendLine("  suns              Show the systems `o` settles the whole graph by: a")
        sb.appendLine("                    ring (3D: a wire icosahedron) around each, as big")
        sb.appendLine("                    as the room it wants, coloured by level; made anew")
        sb.appendLine("                    now and whenever `o` is let go.")
        sb.appendLine("  suns=<levels>     Only those of these levels (e.g. 0,1,2), also while")
        sb.appendLine("                    `o` runs: 0 the nodes (suns coloured, planets")
        sb.appendLine("                    black), 1 their systems,")
        sb.appendLine("                    2 theirs, ...; `o` on the whole graph then moves")
        sb.appendLine("                    those levels only.")
        sb.appendLine("                    Current: ${if (activeView?.gestures?.sunsShown == true) "on" else "off"}.")
        sb.appendLine()
        sb.appendLine("  discard           Never ask about unsaved changes: opening,")
        sb.appendLine("                    following links, reloading, jumping, closing")
        sb.appendLine("                    tabs and quitting discard them silently.")
        sb.appendLine("                    Current: ${if (graph_tools.ViewModes.discard) "on" else "off"}.")
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
        val registered = commandNames()
        // Built-in :bar verbs (handled inline in executeCommandLine,
        // not registered in CommandRegistry).
        val builtins = listOf(
            "browse [<dir>]          — file-tree tab (gf opens / browses)",
            "cd <dir>                — switch the workspace (its own session of open tabs)",
            "cd                      — show the workspace directory",
            "e <path>                — open file, or a directory's file tree (new tab)",
            "e                       — reload current file (asks if unsaved)",
            "e!                      — reload current file, discarding changes",
            "export <fmt> <path>     — write graph in <fmt> (svg|dot) to <path>",
            "export-selection <fmt> <path> — write selection in <fmt> (svg|dot)",
            "help [topic]            — this page (topic: commands, keys)",
            "map <lhs> <rhs>         — add non-recursive key mapping",
            "noremap <lhs> <rhs>     — alias for :map",
            "q                       — close current tab (quit if last)",
            "q!                      — same, discarding unsaved changes",
            "qa                      — quit (asks about unsaved tabs)",
            "qa!                     — quit, discarding unsaved changes",
            "remap <lhs> <rhs>       — add recursive key mapping",
            "set <opt>=<val>         — change a setting (e.g. timeoutlen)",
            "source <path>           — load commands from a file",
            "unmap <lhs>             — remove a user mapping",
            "w [<path>]              — save (to <path> if given)",
            "wq                      — save then close tab (quit if last)",
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
            defaultMappings[key]?.let { return "→ $it" }
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
        val allKeys = (defaults.keys + defaultMappings.keys + mappings.keys + prefixKeyLabels.keys).toSet()
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
        activeView?.shell?.message(text) ?: print(text)
    }

    companion object {
        const val MAX_RECURSION = 1000
        const val DEFAULT_TIMEOUT_MS = 500

        /** Commands handled in [executeCommandLine] itself (the rest are [CommandRegistry] names). */
        val BUILTIN_COMMANDS = listOf(
            "map", "noremap", "remap", "unmap", "set", "se", "source",
            "w", "wa", "q", "q!", "qa", "qa!", "wq", "help", "export", "export-selection", "e", "e!", "browse", "cd",
            "fold", "unfold", "toggle-fold", "content-type", "z",
        )

        /** Commands that take a motion: run from a key they wait for the next command and take it as theirs (`zf` then `t`). */
        val OPERATORS = setOf("fold", "unfold", "toggle-fold")

        /**
         * Keys that stand for other keys out of the box. `i` is in
         * (children), `o` is out (parents); lower case one
         * generation, upper case all.
         */
        fun defaultKeyMappings(): Map<String, String> = mapOf(
            "zi" to "zal",
            "zI" to "zat",
            "zo" to "zaL",
            "zO" to "zaT",
            "zr" to "H",
            "zm" to "h",
        )

        /** Commands driven by moving the mouse ([mouseMoved]). */
        val MOUSE_GESTURES = setOf("grab", "scale", "rotate")

        /** `:set <name>=<value>` settings ([applySetting]). */
        val VALUE_SETTINGS = listOf("timeoutlen", "spring-scale", "spring-scale-step", "picker", "wall-scale", "suns")

        /** The names of the settings that are the application's own ([applicationSettings]), for completion: it adds them. */
        val APPLICATION_SETTINGS = mutableSetOf<String>()

        /** The same for its flags ([applicationFlags]). */
        val APPLICATION_FLAGS = mutableSetOf<String>()

        /** `:set <flag>` / `no<flag>` / `<flag>!` settings ([applyFlag]). */
        val FLAG_SETTINGS = listOf("unhide", "show-parents", "show-children", "dynamic", "walls", "discard", "3d", "explore", "suns")

        /** A button of the mouse on a node, as a key that can be mapped (`:map <RightMouse> zO`): the left one clicked, the right one pressed. */
        const val LEFT_MOUSE = "<LeftMouse>"
        const val RIGHT_MOUSE = "<RightMouse>"

        /** The mark that stands for the nodes pinned in explore mode: `m!`, `m+!`, `m-!`, `'!` ([Explore.mark], [Explore.recall]). */
        const val PINNED_MARK = "!"

        /** What the buttons of the mouse stand for in explore mode ([Explore]), unless the user mapped them: the left one shows, the right one hides. */
        val EXPLORE_MOUSE = mapOf(LEFT_MOUSE to ":explore-show<CR>", RIGHT_MOUSE to ":explore-hide<CR>")

        /** `:help <topic>` topics. */
        val HELP_TOPICS = listOf("commands", "keys", "map", "bindings", "init", "config", "settings")

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

            // View to the target, and folding (see also [defaultKeyMappings])
            "zz" to "center-screen",
            "z1" to "center-screen",
            "z0" to "bound-screen",
            "zt" to "view-top",
            "zb" to "view-bottom",
            "zs" to "view-left",
            "ze" to "view-right",
            "zf" to "fold",
            "zd" to "unfold",
            "za" to "toggle-fold",
            "zR" to "unhide-all",
            "-" to "detail-less",
            "=" to "detail-more",
            "<Space>" to "edit-node",
            "<S-Space>" to "select-and-edit-node",

            // Filtering
            "h" to "hide",
            "H" to "unhide",
            "x" to "explore",

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
            "gt" to "tabnext",
            "gT" to "tabprevious",
            "gf" to "follow-file-link",
            "gF" to "follow-file-link-in-tab",
            "G" to "subgraph-focus",
            "s" to "scale",

            // Modifier-based
            "<C-s>" to "save",
            "<C-S-s>" to "saveas",
            "<C-p>" to "pick-file",
            "<C-o>" to "jump-back",
            "<C-i>" to "jump-forward",
            "<F5>" to "reload",
            "<C-n>" to "tabedit",
            "<C-t>" to "tabedit",
            "<C-w>" to "tabclose",
            "<C-e>" to "export-svg",
            "<C-S-e>" to "export-selection",
            "<C-Tab>" to "tabnext",
            "<C-S-Tab>" to "tabprevious",
            "<C-PgDn>" to "tabnext",
            "<C-PgUp>" to "tabprevious",
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
