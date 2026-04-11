# Revival roadmap

This document tracks the plan to bring `sane-graph-edit` from its
"long forgotten changes" state to a publishable 0.1 release.

Each numbered item has a dedicated file in this directory with a
detailed design and checklist. Check those for the actual plans.

## Order of work

The ordering below reflects a dependency chain: earlier items unblock
later ones, and each step leaves the project in a runnable state.

1. **Gradle build** — *done*
2. **Developer docs** — *done* (`docs/developer/architecture.md`)
3. **Undo / redo** — *done*
4. **File UI** — *done* (`JFileChooser`, dirty tracking, last-dir
   memory, close-on-dirty prompt)
5. **Tabs + cross-tab copy/paste** — *done* (`JTabbedPane`,
   per-tab graph/history/transform/file, `Ctrl+T`/`Ctrl+W`/`Ctrl+Tab`,
   `Ctrl+C/X/V` clipboard)
6. **[SVG exporter](svg-export.md)** — the web-friendly angle. The
   renderer already speaks `Graphics2D`; a thin SVG-writing
   `Graphics2D` subclass, or a direct walk over the graph using the
   same primitives the `Plotter` uses, gets us scriptable export.
7. **README + user docs** — done alongside each feature.

## Non-goals for 0.1

- Kotlin/JS port or browser frontend. The user clarified: publishing
  SVG to a personal server for phone viewing is enough.
- Tests for the renderer or input layer. Tests for pure graph logic
  (selection math, closure computation, DOT round-tripping) are in
  scope, Swing-level tests are not.
- Refactoring `GraphMouseListener` (the file itself carries a
  `// Todo: refactor this!` note). Cleanup is tempting, but out of
  scope for the revival — it works, and rewriting input handling is a
  separate project.

## Open questions deferred to implementation time

- **Coalescing for undo:** dragging a node emits one mutation per
  mouse-move. We don't want each pixel in the undo stack. See
  `undo.md` for the proposal (time-window coalescing on the active
  command).
- **Edge identity for undo:** `Edge.equals` is currently identity.
  Adding undo means we need to be able to *re*-insert an edge we
  removed. Keeping identity equality is fine as long as the undo
  entry holds the *same* `Edge` object.
- **SVG text vs geometry:** we can emit `<text>` elements (small,
  selectable, depends on viewer fonts) or convert text to paths
  (large, pixel-perfect, viewer-independent). Default to `<text>`
  with a font fallback; revisit if fonts don't match.
