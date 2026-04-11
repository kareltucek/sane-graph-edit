# Tabs (multiple open graphs) + cross-tab copy/paste

> **Status: done.** Landed together with file-ui.md. The plan below
> describes what was built; the actual code is in
> `ui/TabManager.kt`, `ui/GraphView.kt`, and `ui/Clipboard.kt`.
> Deviations from the plan are noted inline.

## Goal

Open multiple graphs in one window, switch between them with a tab
bar. Copy a set of nodes from one tab and paste them into another,
preserving style and relative positions.

## Non-goals

- Drag-and-drop tab reordering (stock `JTabbedPane` supports it
  via `setTabLayoutPolicy(SCROLL_TAB_LAYOUT)` — accept defaults).
- Subgraph hierarchy — `Graph.subGraphs` already exists but we are
  treating tabs as independent top-level graphs.

## Design

### Window holds a `JTabbedPane` of `GraphView`s

Right now `Window` holds a single `GraphView`. We change it to hold
a `JTabbedPane`, each tab being one `GraphView`. Each `GraphView`
keeps its own `Graph`, file path, dirty flag, undo stack, and view
transform.

**Important: `Plotter.t` is a global.** Today it's a single
`AffineTransform` on the `Plotter` object. With tabs that breaks: you
switch tab and the view transform is wrong. Two options:

1. Move `t` from `Plotter` into `GraphView` and pass it explicitly
   into `setTransforms(g2d, t, ...)` and every caller that reads it.
2. Keep `Plotter.t` global, but on tab-switch, save the outgoing
   tab's transform and load the incoming one.

Option 1 is the right fix — globals in a renderer are a
thread/reentrance trap. Option 2 is one `tabChanged()` listener and
zero signature changes. Pick option 2 for the first pass; leave a
comment pointing at option 1 as the cleanup.

### New class: `TabManager`

Owns the `JTabbedPane`, knows how to `newTab()`, `closeTab(i)`,
`currentView()`, and listens for tab-change events to swap
`Plotter.t`. `Window` holds a `TabManager` instead of a `GraphView`.

### Keybindings

- `Ctrl+T` — new empty tab
- `Ctrl+W` — close current tab (prompt if dirty)
- `Ctrl+Tab` / `Ctrl+Shift+Tab` — next/previous tab
- `Ctrl+O` — opens in the current tab if empty and clean, otherwise
  in a new tab (matches most editors)

### Copy / paste

A `Clipboard` singleton holding a serialized graph fragment:

```kotlin
data class NodeFragment(
    val nodes: List<Node>,    // cloned
    val edges: List<Edge>,    // cloned, referencing nodes in this list
    val referencePoint: Vector2,  // centre-of-mass at copy time
)
```

`Ctrl+C` captures the current selection (nodes + edges *both*
endpoints of which are selected). `Ctrl+X` is copy + delete.
`Ctrl+V` deep-copies the fragment into the current graph, shifted so
that the fragment's reference point lands at the cursor.

Cross-tab works because the clipboard is process-global and the
fragment holds *cloned* nodes (not references to the source graph's
live nodes). Pasting back into the same tab also works.

### DOT clipboard interop (bonus)

Store the fragment *also* as a DOT string on the system clipboard
(`Toolkit.getDefaultToolkit().systemClipboard`). Enables:

- paste-from-text-editor to drop a graphviz snippet into the tool
- copy-out to a terminal / issue tracker

Use `DotGraphLoader` to round-trip the fragment.

## Touch points

- `ui/Window.kt` — hold a `TabManager`, not a `GraphView`.
- New `ui/TabManager.kt` — `JTabbedPane` wrapper.
- `ui/GraphView.kt` — drop the hardcoded `g = Graph.testGraph()`
  assumption; take the graph as a constructor argument; add
  `currentFile: Path?`, `isDirty: Boolean`.
- `ui/GraphKeyListener.kt` — new bindings; most `impl` methods stay
  operating on the current `GraphView`.
- New `ui/Clipboard.kt` — fragment data class + deep-copy helpers.
- `Plotter.t` — save/restore on tab switch (document the hack).

## Depends on

- **Undo** (`undo.md`) must be in place first. Otherwise closing a
  tab is irreversible in a surprising way, and cross-tab paste
  introduces undo-stack confusion if history is ever moved globally.
- **File UI** (`file-ui.md`) should land first so each tab has a
  sensible title (file name) and close-prompt behaviour.

## Testing

- Open N tabs, switch between them, verify view transform persists.
- Copy nodes from tab A, paste into tab B, verify edges between
  copied nodes survive and edges to non-copied nodes are dropped.
- Close dirty tab → prompt. Save-then-close → no prompt.
- Paste position: copy in tab A with cursor at world (100,100),
  switch to tab B with cursor at (500,500), paste — the paste
  should land at (500,500), not (100,100).
