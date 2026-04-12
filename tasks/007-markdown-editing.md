# 007 — Markdown editing support in node labels

> **Status: future.** Depends on 006 (Compose port) — Compose's
> `AnnotatedString` / `RichTextEditor` ecosystem makes this
> feasible; Swing's `JTextArea` does not.

## Goal

Let the user write node labels in a lightweight markup
(Markdown subset or similar) and have the rendered node display
formatted text: **bold**, *italic*, headings, bullet lists,
maybe code spans.

## Why after Compose

Swing's `JTextArea` is plain-text only. `JEditorPane` supports
HTML but its editing experience is poor and its rendering is
visually inconsistent with the rest of the app. Compose opens
two paths:

1. **`AnnotatedString` rendering.** Compose's `drawText` natively
   supports mixed styles (bold, italic, color, size) via
   `AnnotatedString`. A small Markdown-to-AnnotatedString parser
   would let the renderer display rich text without any HTML
   dependency.

2. **Rich text editing.** Community libraries like
   `compose-richeditor` or `compose-markdown` provide editable
   rich-text fields that output Markdown. Alternatively, the
   popup editor (see 005, "Node editor" section) could stay
   plain-text and parse Markdown on dismiss — "edit as source,
   render as rich".

## Scope (rough)

### Minimum viable

- Parse a Markdown subset: `**bold**`, `*italic*`, `# heading`,
  `- bullet`, `` `code` ``.
- Render formatted text inside nodes (Plotter / DrawScope).
- Edit as plain Markdown source in the popup editor; node
  re-renders on dismiss.
- Round-trip through DOT: store the raw Markdown in the `label`
  attribute; Graphviz ignores the markup, we parse it.

### Stretch

- WYSIWYG editing in the popup (bold/italic while you type).
- Inline LaTeX rendering (e.g. `$x^2$`) — useful for math
  graphs.
- Syntax highlighting in the editor popup.
- Configurable: per-node toggle between plain and Markdown mode
  (so you don't need to escape `*` in nodes that aren't using
  markup).

## Open questions

- **Which Markdown parser?** Options: `commonmark-java` (robust,
  well-maintained), `intellij-markdown` (JetBrains, already in
  the Kotlin ecosystem), or a minimal hand-rolled subset parser
  (fewer deps, less capable).
- **Line height / layout.** Mixed font sizes (headings) change
  the node's bounding box. The renderer's `recomputeBounds`
  currently assumes uniform line height. Will need a richer
  text-measurement model.
- **DOT interop.** If the label contains `**bold**`, Graphviz
  sees it as literal asterisks. That's fine for our purposes
  (DOT is the storage format, not a shared interchange), but
  worth documenting.
- **Performance.** Parsing Markdown on every paint is too slow
  for large graphs. Cache the parsed `AnnotatedString` alongside
  the existing `NodeCache.lines`.
