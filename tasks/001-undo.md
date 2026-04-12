# Undo / redo

## Goal

`Ctrl+Z` undoes the last mutation. `Ctrl+Shift+Z` (or `Ctrl+Y`)
redoes. Every user-visible mutation is reversible: add/remove node,
add/remove edge, move nodes, edit text, change style (color, shape,
size), layout-optimizer moves.

## Non-goals

- Undoing selection changes. Selection moves with time but is not
  "data". Undoing a click that deselected would be more annoying
  than helpful. Matches Inkscape's behaviour.
- Undoing view transforms (pan, zoom, fit-to-screen).

## Design

Introduce a `Command` abstraction and a `History` held by the
`Graph`. Every mutating operation goes through `History.apply(cmd)`
which executes the command's `redo()` and pushes it onto the stack.

```kotlin
interface Command {
    fun redo()     // perform / re-perform the mutation
    fun undo()     // inverse
    /** Attempt to merge this with the top of the stack; return true on success. */
    fun coalesceInto(previous: Command): Boolean = false
}

class History(val g: Graph) {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    fun apply(cmd: Command) {
        cmd.redo()
        if (undoStack.isEmpty() || !cmd.coalesceInto(undoStack.last())) {
            undoStack.addLast(cmd)
        }
        redoStack.clear()
    }

    fun undo() { /* pop + undo() + push to redoStack */ }
    fun redo() { /* pop + redo() + push to undoStack */ }
}
```

Each concrete command captures only the information required to
reverse itself — for `MoveNodesCommand`, that's the delta vector and
the node set, not a snapshot of every node's `position`.

### Commands to implement

| Command            | Captures                                    |
|--------------------|---------------------------------------------|
| `AddNodeCommand`   | `Node` ref                                  |
| `AddEdgeCommand`   | `Edge` ref                                  |
| `RemoveNodesCommand` | list of nodes + list of edges they killed |
| `RemoveEdgesCommand` | list of edges                             |
| `MoveNodesCommand` | set of nodes + delta `Vector2`              |
| `EditTextCommand`  | node + before/after text                    |
| `StyleCommand`     | node + before/after `NodeStyle`             |
| `SetShapeCommand`  | node + before/after shape                   |
| `LayoutCommand`    | map of `Node` → before/after position       |

### Coalescing (mouse-drag trap)

`GraphMouseController.dragMoveNode` currently runs every
`mouseMoved` event, which can be tens per second. We don't want one
undo step per pixel. Options:

1. **Per-drag command:** `mousePressed` on a node *begins* an
   accumulation command; `mouseReleased` commits it to the history.
   Dragging emits no history entries mid-drag — it just updates
   positions directly.
2. **Time-window coalescing:** `MoveNodesCommand.coalesceInto` merges
   if the previous command is also a move of the *same node set*
   within the last 500 ms. Same idea as many text editors.

Option 1 is cleaner because the drag boundary is already a natural
transaction. Go with it.

Same logic applies to `LayoutOptimizer`: if triggered via `o`, commit
a single `LayoutCommand`; if triggered continuously via
`optimizeOnDrag`, fold it into the per-drag command.

### Per-graph stacks

`History` is owned by `Graph`, not by `GraphView`. This matters for
tabs: each open document has its own undo stack, and closing a tab
drops its history entirely.

## Touch points

- New file `graph_tools/Command.kt` — interface + concrete commands.
- New file `graph_tools/History.kt` — the stack.
- `Graph.kt` — add `val history: History`; route `add`, `remove`,
  `addAllEdges`, etc. through commands. Or leave the mutators as
  low-level primitives and have higher-level callers construct
  commands. Prefer the latter so that the serializer/parser can
  still populate a fresh `Graph` without spamming history.
- `GraphKeyListener.kt` — `Ctrl+Z` / `Ctrl+Shift+Z` bindings;
  rewrap each `impl.*` mutator to construct the right command.
- `GraphMouseController.dragMoveNode` — start a
  `MoveNodesCommand` on drag-start, commit on drag-end.
- `NodeEditor.endNodeEdit` — construct `EditTextCommand` from
  before/after text.
- `StylePicker` — wrap colour/shape picks in commands.

## Testing

- Unit test: push a sequence of commands, undo all, verify graph
  matches initial state (by serializing to DOT and comparing).
- Unit test: coalescing of `MoveNodesCommand` — many tiny moves
  should collapse into one undo step.
- Manual test: draw a tree, undo to empty, redo back, compare SVG /
  screenshot.

## Open questions

- Should a `remove` followed by undo restore the *exact same*
  `Node`/`Edge` objects, or clones? Clones break outstanding
  references (e.g. `lastActiveNode`). Keep the originals.
- Max history depth? 200 entries is fine — `Graph` instances are
  small and the per-entry delta is usually a handful of doubles.
