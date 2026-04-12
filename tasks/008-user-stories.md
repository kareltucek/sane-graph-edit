# 008 — User stories backlog

> **Status: backlog.** A collection of feature requests and ideas,
> roughly ordered by how self-contained they are. Each item is a
> candidate for its own numbered task once we decide to pick it up.

---

## Cut / yank / paste (vim-style `y`, `p`, `d`)

Now that we have clipboard copy/paste (`Ctrl+C/V/X`), expose it
through single-keystroke bindings that match vim muscle memory:

- `y` — yank (copy) the selection to the clipboard
- `p` — paste from the clipboard at the cursor
- `d` — already bound to delete; keep it, but make sure it also
  puts the deleted nodes into the clipboard (i.e., `d` = cut)

The use case is "split a part of a graph from where it is and put
it elsewhere" — currently requires `Ctrl+X`, navigate, `Ctrl+V`.
With `y`/`p`/`d` it's just `y`, move cursor, `p`.

**Open question:** `d` currently deletes without copying. Changing
it to cut would be a behaviour change. Maybe `d` stays as
destructive delete and `x` becomes cut? Or follow vim literally:
`d` = cut, `D` = delete-and-reconnect (already exists).

---

## Layout optimizer affects the parent node

When running the layout optimizer (`o`/`O`) on a selection, the
optimizer moves the parent node even though the user probably
wants it anchored. The restricted variant (`O`) is meant to
address this but apparently doesn't fully.

**Desired behaviour:** the node the user is "working from" (the
`lastActiveNode`? the node under the cursor?) stays pinned during
optimization. Everything else relaxes around it.

---

## Pull children command

A command that repositions all direct children (outgoing
neighbours) of the selected node(s) so they're clustered near the
selection, without changing the selection itself.

Use case: you have a hub node with children scattered across the
canvas; you want to gather them close so you can work on the
cluster.

**Possible binding:** `p` is taken (paste). Maybe `P` (pull)?
Or a modifier like `Shift+l` (which is currently "select linked
backward" — so `L`). Needs thought.

---

## Mirror / rotate selection

Commands to mirror the selected nodes horizontally (flip x
coordinates around the selection's centre of mass) or vertically,
and/or rotate by 90°/180°/arbitrary angle.

Use case: you've drawn a tree left-to-right but want it
top-to-bottom, or you want to mirror a symmetric subgraph.

**Possible bindings:** `m` for mirror horizontal, `M` for mirror
vertical, or a "rotate" chord like `Ctrl+R` (currently redo — but
redo is also `r`, so `Ctrl+R` could be freed).

---

## Save as `.dotlike` instead of `.dot`

Using `.dot` as the file extension conflicts with Graphviz DOT on
systems where file associations matter. A distinct extension like
`.dotlike` or `.sge` would make the editor's files unambiguous.

**Considerations:**
- The parser and serializer don't care about the extension — it's
  just a file-dialog filter and a default-name convention.
- Existing `.dot` files should still open fine (the Open dialog
  can accept both `*.dot` and `*.dotlike`).
- The DOT format itself doesn't change — only the extension.

---

## Pin a node (always-visible at readable size)

A per-node flag that forces the node to render at a minimum
screen-space size regardless of zoom level. When zoomed out on a
large graph, pinned nodes stay readable while unpinned ones shrink
to dots.

Use case: landmark / hub nodes that you always want to see as
orientation reference when navigating a large graph.

**Implementation sketch:**
- `Node.attributes.pinned: Boolean`
- In `Plotter.TextPlotter.drawNode`, if `pinned` and the
  computed screen-space font size is below a threshold, override
  the font size to the threshold and recompute bounds for that
  node on the fly.
- Serialize as a custom DOT attribute (`pinned=true`) so it
  round-trips.

---

## Tab-like navigation: `gt`, `gT`, `gg`

Vim-style tab switching:

- `gt` — next tab (currently `Ctrl+Tab`)
- `gT` — previous tab (currently `Ctrl+Shift+Tab`)
- `gg` — first tab? Or "go to tab N" if preceded by a count?

**Problem:** `g` is currently bound to "grab" (toggle move mode).
Options:

1. Rebind grab to something else (e.g., `G` alone, freeing `g`
   as a prefix for `gt`/`gT`/`gg`).
2. Make `g` a "leader key" with a timeout: `g` alone after a
   short delay = grab; `g` followed quickly by `t`/`T`/another
   key = the compound command. This is how vim's `g` prefix
   works.
3. Leave `g` as grab and use different bindings for tab nav
   (keep `Ctrl+Tab` as the only way).

Option 2 is the most vim-faithful but requires a key-sequence
state machine (partial match → wait → timeout → resolve). That's
a useful primitive for the macro system (below) too.

---

## Subdivide edges (inverse of `D`)

`D` deletes a node and reconnects its predecessors to its
successors. The inverse: take a set of edges and insert a new hub
node in the middle.

**Algorithm:**

1. Identify "selected edges" — edges where both src and dst are
   in the current selection.
2. Collect the set of source nodes and the set of destination
   nodes from those edges.
3. Create a new node (at the centroid of the selected edges'
   midpoints, or at the cursor).
4. For each source node, add an edge from it to the new node.
5. For each destination node, add an edge from the new node to
   it.
6. Delete the original selected edges.

The whole thing is one `CompositeCommand` for undo purposes.

**Possible binding:** `s` or `S` (subdivide)? Or a chord.

---

## Vim-style macro registers

Allow the user to record and replay sequences of editor commands,
similar to vim's `q{register}...q` / `@{register}`.

**Minimum viable:**

- `q` + a letter (e.g., `qa`) starts recording into register `a`.
- Every subsequent single-key command is appended to the register.
- `q` again stops recording.
- `@a` replays register `a`.

The existing `executeMacro` method in `GraphKeyListener.impl`
already runs a string of commands character by character — a macro
register is just a persistable version of that string.

**Stretch:**

- Named registers can also hold node sets (save the current
  selection into a register; recall it later to re-select those
  nodes). Not needed yet — park until a use case appears.
- Persist registers across sessions (write to
  `~/.config/sane-graph-edit/macros`).
- A macro editor UI for viewing / editing stored macros.

---

## Search (vim-style `/`, `?`, `n`, `N`)

Find nodes by label text. Needs a minimal command-line UI:

- `/` opens a search bar at the bottom of the canvas (like vim's
  command line). Type a query, press Enter.
- The first matching node is selected and the view centres on it.
- `n` goes to the next match, `N` to the previous.
- `?` opens the bar for backward search (or just reverse the
  `n`/`N` direction).
- `Escape` dismisses the bar.
- `:` could eventually open a general command bar (`:w` = save,
  `:q` = quit, `:e foo.dot` = open), but that's a separate story.

**Implementation sketch:**

- A `JTextField` (Swing) or `TextField` composable (Compose) that
  appears at the bottom edge of the canvas, layered above it
  (same approach as NodeEditor and StylePicker).
- Matching: substring by default, regex if the query starts with
  `/` or contains unescaped regex metacharacters. Case-insensitive
  by default, case-sensitive if the query contains uppercase
  (smartcase, like vim).
- Match list: all nodes whose `attributes.text` matches, sorted
  by position (left-to-right, top-to-bottom) for a stable
  `n`/`N` traversal order.

---

## Graph filtering / hide nodes (`h` / `H`)

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

- `Node.attributes.hideLevel: Int` (default 0). Serialise as a
  custom DOT attribute (`hideLevel=N`) so it round-trips; omit
  when 0.
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

---

## Undo/redo rebinding

Current bindings: `u` = undo, `r` = redo, `Ctrl+R` = redo.

Problems:
- No `Ctrl+Z` / `Ctrl+Shift+Z` — the standard pair that every
  other app uses. Users who don't read the help graph will try
  `Ctrl+Z` and nothing will happen.
- `r` is a valuable single-key slot. Freeing it opens it for
  other commands (e.g., rotate, or a future use).

**New bindings:**

| Key            | Action |
|----------------|--------|
| `u`            | undo (keep — matches vim) |
| `U`            | redo (shift of undo key; replaces `r`) |
| `Ctrl+Z`       | undo (standard; add back) |
| `Ctrl+Shift+Z` | redo (standard; add back) |
| `Ctrl+R`       | redo (keep for muscle memory) |

`r` becomes unbound and available for reassignment.
