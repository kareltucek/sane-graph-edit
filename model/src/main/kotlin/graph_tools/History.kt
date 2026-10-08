package graph_tools

/**
 * Single reversible mutation to a [Graph].
 *
 * Implementations must:
 *
 *  - make [redo] the only path that actually performs the mutation
 *    (the [History] will call it when the command is applied, so the
 *    calling code should NOT touch the graph directly before that);
 *  - make [undo] an exact inverse — running `redo(); undo()` from any
 *    state must leave the graph observably unchanged.
 *
 * Commands hold enough state to be replayed later (e.g. the node list
 * to re-add, the before/after positions for a move). That state should
 * be the minimum necessary: a move command captures a delta, not a
 * snapshot of every field of every node.
 */
interface Command {
    /** Perform or re-perform the mutation. */
    fun redo()

    /** Reverse the effects of [redo] exactly. */
    fun undo()

    /**
     * Try to fold this command into [previous] so the two appear as one
     * entry in the history. Implementations return true only when the
     * merge is meaningful (e.g. continued typing, ongoing drag).
     *
     * The [History] only checks against the top of the stack. On a
     * successful merge, [redo] has already run (the mutation is live);
     * the previous command's captured state is what gets kept, extended
     * by whatever this command adds.
     */
    fun coalesceInto(previous: Command): Boolean = false
}

/**
 * Bounded undo/redo stack attached to a [Graph].
 *
 * A command is "applied" by calling [apply], which runs its [Command.redo]
 * and pushes it onto the undo stack (after optional coalescing with the
 * previous entry). Applying a new command clears the redo stack — the
 * usual branching-history contract.
 *
 * Each [Graph] owns one [History]. Once tabs land, each tab has its own
 * graph and its own history; closing a tab drops its stack entirely.
 */
class History(
    /** Maximum number of entries to keep on the undo side. Oldest entries are dropped first. */
    val maxDepth: Int = 200,
) {
    private val undoStack: ArrayDeque<Command> = ArrayDeque()
    private val redoStack: ArrayDeque<Command> = ArrayDeque()

    /** True if at least one mutation has been applied and not yet undone. */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** True if at least one undone mutation is available to replay. */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Observers notified after every `apply`, `undo`, or `redo`. Used by
     * [GraphView] to flip its dirty flag and refresh window titles. Each
     * listener runs on whichever thread triggered the change; currently
     * everything happens on the Swing EDT.
     */
    private val listeners: MutableList<() -> Unit> = mutableListOf()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    private fun fire() {
        listeners.forEach { it.invoke() }
    }

    /**
     * Execute [cmd] and record it. If the stack's top command agrees to
     * absorb the new one ([Command.coalesceInto]), the top entry stays
     * in place and only its state advances; otherwise [cmd] becomes a
     * new undo entry. Either way the redo stack is cleared.
     */
    fun apply(cmd: Command) {
        cmd.redo()
        val merged = undoStack.lastOrNull()?.let { cmd.coalesceInto(it) } == true
        if (!merged) {
            undoStack.addLast(cmd)
            while (undoStack.size > maxDepth) {
                undoStack.removeFirst()
            }
        }
        redoStack.clear()
        fire()
    }

    /**
     * Push an already-applied command onto the stack without re-running
     * it. Useful for commands whose effects are built up incrementally
     * during a user gesture (e.g. a drag) and then committed once the
     * gesture ends — the mutation is already live at commit time, we just
     * need the stack entry to be able to reverse it later.
     */
    fun commitWithoutRun(cmd: Command) {
        val merged = undoStack.lastOrNull()?.let { cmd.coalesceInto(it) } == true
        if (!merged) {
            undoStack.addLast(cmd)
            while (undoStack.size > maxDepth) {
                undoStack.removeFirst()
            }
        }
        redoStack.clear()
        fire()
    }

    fun undo() {
        val cmd = undoStack.removeLastOrNull() ?: return
        cmd.undo()
        redoStack.addLast(cmd)
        fire()
    }

    fun redo() {
        val cmd = redoStack.removeLastOrNull() ?: return
        cmd.redo()
        undoStack.addLast(cmd)
        fire()
    }

    /** Drop all history. Used when loading a new document into an existing view. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        fire()
    }
}
