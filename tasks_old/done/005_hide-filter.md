# Graph filtering / hide nodes (`h` / `H`)

> **Status: done**

Show only what you're working on by hiding everything else.

**Bindings:**

- `h` — hide all nodes that are NOT in the current selection
  (i.e., keep only selected nodes visible). Edges to/from hidden
  nodes are also hidden.
- `H` — unhide one level (undo the most recent hide).

**Levelled hiding:**

Each node carries an integer `hideLevel` (default 0 = fully
visible). Hiding increments the level on every affected node;
unhiding decrements it. A node is visible iff `hideLevel == 0`.

This means two successive `h` commands produce two layers:

1. First `h`: select A, B, C → everything else goes to
   `hideLevel = 1`.
2. Second `h`: within the visible {A, B, C}, select just A →
   B and C go to `hideLevel = 1` (they were already 0 after
   surviving the first hide).
3. First `H`: unhides one level → B and C reappear
   (`hideLevel` drops back to 0), but the nodes hidden by the
   first `h` stay at `hideLevel = 1`.
4. Second `H`: unhides the remaining → everything visible.

The level acts as a stack counter, so hides compose and unwind
naturally without needing an explicit stack of "hidden-set
snapshots".

**Implementation sketch:**

- `Node.cache.hideLevel: Int` (default 0). This is view state,
  not graph data — it should NOT be serialised to DOT (opening
  someone else's file shouldn't inherit their current filter).
  Lives on `NodeCache` alongside `textBounds`, `shapeBounds`,
  etc. Lost on file load (all nodes start visible), which is the
  right default.
- Plotter: skip drawing nodes/edges where `hideLevel > 0`.
- `Clicker.selectClickedNode`: ignore hidden nodes (can't click
  what you can't see).
- `GraphTools.computeBoundingBox`, `computeCenterOfMass`: filter
  to visible nodes so `0` (fit-to-all) fits to the visible
  subset.
- Layout optimizer: only move visible nodes; hidden nodes keep
  their positions.
- SVG export: export visible nodes only (unless an "export all"
  flag is passed).
- Commands: `HideCommand` and `UnhideCommand` wrapping the level
  changes, so `h`/`H` participate in undo/redo.

**Open questions:**

- Should hidden nodes participate in edge rendering? E.g., if
  A→B→C and B is hidden, should a "ghost edge" A⟶C be drawn to
  show the indirect connection? Probably not for v1 — just hide
  everything incident to a hidden node.
- Should `h` with an empty selection hide nothing (no-op) or
  hide everything (clear the canvas)? No-op is safer.
- What about edges that cross the visible/hidden boundary? If
  A is visible and B is hidden, the A→B edge disappears. That's
  the expected behaviour — you asked to see only the selection.
