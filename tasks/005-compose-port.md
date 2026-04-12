# 005 — Port UI from Swing to Compose Multiplatform

> **Status: planned.** This is the next significant rework. All
> preceding tasks (undo, file UI, tabs, SVG export) should be
> complete first so the Swing codebase is feature-stable and we
> aren't porting a moving target.

## Motivation

Swing works but carries accumulated pain:

- The `Plotter.setTransforms` bug (overwriting the Graphics2D's
  pre-installed transform, invisible until tabs added an ancestor
  with a vertical offset) is a class of problem that a
  declarative UI eliminates by construction.
- `GraphMouseListener` is 300+ lines of state-machine-by-
  convention with a long-standing `// Todo: refactor this!`.
  Compose's `Modifier.pointerInput { awaitPointerEvent ... }`
  gives explicit gesture state for free.
- `StylePicker` is 200 lines of `GridBagLayout` boilerplate.
  In Compose it's a `Column { Row { Button ... } }`.
- Layout, layering, and focus management across overlapping
  components (`JLayeredPane`, `SpringLayout`, `requestFocus`)
  are fragile and hard to reason about. Compose's `Box` with
  conditional composition replaces all of that.
- Swing's look and feel is dated. Compose ships Material 3 and
  is trivially themeable.
- JetBrains is actively investing in Compose Desktop (quarterly
  releases, growing API surface). Swing is in maintenance mode.

## What stays, what goes

### Reusable as-is (~60% of LOC)

Everything that doesn't import `java.awt` or `javax.swing`:

- `graph_tools/Graph.kt`, `Node.kt`, `Edge.kt` — data model
- `graph_tools/History.kt`, `Commands.kt` — undo / redo
- `graph_tools/GraphTools.kt` — selection math, bounding box
- `graph_tools/LayoutOptimizer.kt` — force-directed layout
- `parser_dot/*` — entire DOT pipeline
- `ui/Session.kt`, `AutosaveManager.kt`, `Clipboard.kt`,
  `BackupPaths.kt`, `XdgPaths.kt`, `AppState.kt` — persistence
- `utils/Vector2.kt`, `Constants.kt` — pure data
- All existing unit tests (34)

### Needs porting (~40% of LOC)

| Current (Swing)                    | Target (Compose)                               | Notes |
|------------------------------------|-------------------------------------------------|-------|
| `Plotter.kt` (Graphics2D)         | DrawScope inside `Canvas { }`                   | Same primitives (drawLine, drawRect, drawOval, drawText), different API. Medium effort. |
| `NodeShape.kt` (Graphics2D)       | DrawScope helpers                               | Small. |
| `GraphCanvas.kt` (JPanel)         | `Canvas { ... }` composable                     | Small — the canvas is a thin shell around the plotter. |
| `GraphView.kt` (JLayeredPane)     | `Box { Canvas { }; if (editing) NodeEditor }` | Medium — see "Node editor" section below. |
| `NodeEditor.kt` (JTextArea overlay)| `BasicTextField` in a popup window              | See dedicated section. |
| `StylePicker.kt` (JPanel+GridBag) | `Column { Row { Button ... } }`                | Big simplification. |
| `GraphMouseListener.kt` (312 LOC) | `Modifier.pointerInput { ... }`                 | Medium — opportunity to rewrite the state machine cleanly. |
| `GraphKeyListener.kt`             | `Modifier.onKeyEvent { }`                       | Small — dispatch table stays the same, wrapper changes. |
| `TabManager.kt` (JTabbedPane)     | `TabRow` + content swap                         | Small. |
| `Window / Main.kt` (JFrame)       | `singleWindowApplication { Window { ... } }`   | Small. |
| `FileOps.kt` (JFileChooser)       | AWT FileDialog interop or compose-file-picker   | Trivial — Compose Desktop can use AWT dialogs. |

### Deleted outright

- `Plotter.t` global. Replace with a `viewTransform` parameter
  threaded through composables via `CompositionLocal` or plain
  function argument. The entire "Plotter.t is a singleton that
  TabManager rewires on tab switch" hack goes away.
- `FontData.cache` and the `withIdentityTransform` workaround.
  Compose's `DrawScope.drawText` uses `TextMeasurer` which has
  its own caching and doesn't exhibit the zero-transform bug.
- The `GraphView.paintComponent → graphCanvas.doDrawing(g)`
  double-paint path. Compose paints each composable exactly
  once per frame, so this entire category of bugs disappears.

## Node editor — the tricky piece

### Current Swing approach

`NodeEditor.kt` is a `JTextArea` overlaid on the canvas via
`JLayeredPane` at layer 1. `updatePosition` uses `SpringLayout`
constraints to anchor the text area at the node's screen-space
position, with font size scaled to match the node's zoom level
and caret position computed from the click coordinates. This is
~145 lines of fiddly measurement code that breaks whenever the
coordinate system shifts (as it did when tabs added a vertical
offset).

### Proposed Compose approach

**Don't try to be pixel-perfect.** Instead, pop up a floating
window (Compose `Popup` or a small `Window`) that is:

- **Centre-aligned with the centre of the node** on screen.
  Compute the node's screen-space centre from the view
  transform and the node's workspace position, then position
  the popup so its visual centre coincides.
- **Sized to fit the text** with a reasonable minimum width.
  Let Compose's text layout do the sizing; no manual
  `FontMetrics` math.
- **Styled to match the node** — same background, foreground,
  and font size. The popup is visually "the node, lifted out
  for editing", not a separate dialog.
- **Dismissed on Escape, Enter (configurable), or click-away.**
  On dismiss, the edited text is committed as an
  `EditTextCommand` exactly as today.

Advantages over the current overlay:

- No `SpringLayout` constraint juggling.
- No font-scale matching — the popup's font is fixed (e.g. 14pt)
  regardless of zoom. The node re-renders at zoom-scale once the
  edit is committed.
- No `paintComponent` coordinate entanglement — the popup lives
  in its own Compose layout tree, detached from the canvas's
  coordinate system.
- Works at any zoom level without the "screenspace font vs
  workspace font" dance.

Trade-off: the popup won't track the node if the user
pans/zooms while editing. That's acceptable — editing is a
brief modal interaction, not a background activity. If the user
pans away, dismiss the editor.

## Phased implementation plan

Each phase produces a runnable editor. Phases 1–5 run both UIs
side by side (Swing production, Compose development window);
phase 6 deletes Swing.

### Phase 1 — scaffold

Add the Compose Multiplatform Gradle plugin + desktop
dependencies alongside the existing Swing plugin. Create a
`compose/` source set (or a separate `src/main/kotlin/compose/`
package) with a minimal `singleWindowApplication` that shows a
blank `Canvas`. Verify it compiles and opens.

No Swing code touched. Both windows can be opened from the same
main function for comparison.

### Phase 2 — renderer

Port `Plotter` + `NodeShape` to a `ComposePlotter` object that
takes a `DrawScope` and renders a `Graph`. Run it in the Compose
window against the same `Graph.defaultGraph()`. Compare visually
with the Swing window: nodes, edges, arrowheads, text, colour,
shape — should match.

### Phase 3 — canvas input

Add `Modifier.pointerInput` (pan, zoom, click-to-select,
double-click-to-spawn, drag-to-move, selection-box) and
`Modifier.onKeyEvent` (the single-character dispatch table) to
the Compose canvas. The Compose window is now a functional
editor — you can create nodes, connect them, move them, delete
them, undo/redo — but without the popup editor or style picker.

This is the phase where the `GraphMouseListener` state machine
gets rewritten cleanly.

### Phase 4 — popups

Implement the centre-aligned popup `NodeEditor` (see above)
and the `StylePicker` as Compose composables.  After this phase
the Compose window is feature-complete for single-tab editing.

### Phase 5 — tabs + window chrome

Replace the JTabbedPane-based `TabManager` with a Compose
`TabRow` + content swap. Wire the title bar, close-on-dirty
prompt, file dialogs. After this phase the Compose window is
a full replacement for the Swing one.

### Phase 6 — delete Swing

Remove every file that imports `java.awt` or `javax.swing`
(except `FileOps` if we keep AWT's `FileDialog`). Delete the
`Plotter.t` global. Update `build.gradle.kts` to drop the
`application` plugin in favour of Compose's own run/package
tasks. Update docs.

One commit, clearly titled, so `git revert` gets Swing back
if something goes wrong.

## Risks

- **Compose Desktop font rendering** may look different from
  Swing's. Compose uses Skia underneath; the font hinting and
  subpixel rendering differ. Node text may reflow slightly.
  Mitigation: visual comparison during phase 2.
- **Binary size** grows from ~2 MB (fat jar) to ~30+ MB (Compose
  runtime + Skia). Not a problem for a desktop tool, but worth
  knowing. The AppImage will be larger.
- **Compose Desktop maturity.** Some APIs are still marked
  `@ExperimentalComposeUiApi`. We may hit edge-case bugs in
  pointer input or text input. Mitigation: pin a specific
  Compose version and don't chase bleeding-edge releases.
- **Input regression.** The 300-line mouse listener is being
  rewritten, not translated. If the rewrite doesn't faithfully
  reproduce every gesture (double-click timing, drag dead zone,
  ctrl-click multi-select, selection-box, right-click picker),
  the editor will feel wrong. Mitigation: write a manual test
  checklist before phase 3 and run through it after.

## Open questions

- **Compose version.** Pin to the latest stable at the time of
  starting. As of early 2026, that's ~1.7.x.
- **File dialogs.** Compose Desktop can use AWT's `FileDialog`
  directly (it's a native dialog on Linux). Alternatively,
  community libraries like `compose-file-picker` exist. Decide
  during phase 5.
- **Compose Multiplatform vs Desktop-only.** The "multiplatform"
  part buys us Android/iOS/Web targets we don't need today. But
  using the multiplatform plugin from the start doesn't cost
  anything and keeps the option open. Go with multiplatform
  plugin, desktop-only source set.
- **HiDPI.** Compose handles display scaling natively via Skia.
  The current Swing code has no HiDPI awareness. The port
  should "just work" on HiDPI screens, but verify.
