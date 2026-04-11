# SVG exporter

## Goal

Export the currently-open graph to an SVG file. Resulting file is
uploaded (by hand) to a personal web server and viewed on a phone.
So the SVG needs to be:

- **Self-contained** — no external font files or CSS references.
- **Scalable** — `viewBox` set so the whole graph fits on any screen
  with pinch-zoom.
- **Small-ish** — well under a MB for a reasonably sized graph.
- **Readable** — labels stay sharp at any zoom.

## Non-goals

- Interactivity (clickable nodes, hover highlights). Stretch goal —
  cheap to add later with `<a>` wrappers but not needed for v0.1.
- Round-tripping: no need to parse SVG back. DOT stays the native
  format.
- Preserving Graphviz/DOT semantics. The export is a *picture*, not
  a graph definition.

## Design

### Approach: direct Plotter rewrite, not a Graphics2D subclass

Two realistic paths:

1. **SVGGraphics2D subclass.** Implement `Graphics2D` and emit SVG
   primitives from `drawRect`, `drawLine`, `fillOval`, etc. Then
   call the existing `Plotter.drawGraph(svgG2d, graph)`. Exists in
   Batik (`org.apache.batik:batik-svggen`) as a ready solution,
   ~10 MB of dependency.
2. **Direct walker.** Write a small `SvgWriter` that iterates
   `graph.nodes` / `graph.edges` and emits SVG elements matching
   the `Plotter` primitives — rect, line, text, fillOval.

Path 1 is less code if we take Batik. Path 2 is smaller and more
under our control. The `Plotter` has maybe a dozen primitives; a
direct walker is ~150 lines. **Go with path 2.**

### Output shape

```xml
<svg xmlns="http://www.w3.org/2000/svg"
     viewBox="{ul.x} {ul.y} {w} {h}"
     font-family="sans-serif">
  <style>
    .node-bg { /* per-node fill can go inline */ }
    .edge { stroke: black; stroke-width: 1; fill: none; }
    .arrow { fill: black; }
  </style>
  <g class="edges">
    <line x1=".." y1=".." x2=".." y2=".." class="edge"/>
    <circle cx=".." cy=".." r=".." class="arrow"/>
    ...
  </g>
  <g class="nodes">
    <g class="node">
      <rect x=".." y=".." width=".." height=".." rx="5"
            fill="#ffffff" stroke="#000000"/>
      <text x=".." y=".." font-size="12">line 1</text>
      <text x=".." y=".." font-size="12">line 2</text>
    </g>
    ...
  </g>
</svg>
```

### Reuse the Plotter's cached measurements

`Node.cache.shapeBounds`, `Node.cache.textBounds`, `Edge.cache.srcPt`,
`Edge.cache.dstPt` are already populated after the first paint.
*Trigger a `recompute()` before exporting* so the measurements are
fresh — but from there, SVG writing is a pure read of the cache +
the `Node.cache.shape` to decide rect vs oval.

The catch: `recompute()` needs a `Graphics2D` for font metrics. We
get one from `new BufferedImage(1,1,TYPE_INT_ARGB).createGraphics()`
— cheap, doesn't need a visible window, works headless.

### Viewport

Use `GraphTools.computeBoundingBox(graph.nodes)` plus a margin of
e.g. `20 px` worldspace. That becomes the SVG `viewBox`. No graph
transform — export always renders at "scale 1", pan = 0.

### Keybinding

`Ctrl+E` → "Export SVG…" → `JFileChooser` with SVG filter, default
filename `<current-file-basename>.svg`.

Also expose it as a plain method on `GraphView` so the CLI (future)
or tests can call it without going through the UI.

### Headless export mode (stretch — defer unless trivial)

`main --export foo.dot foo.svg` would load the DOT file and write
SVG without opening a window. Useful for scripting phone-upload.
Don't block v0.1 on this, but structure the code so it's a ~20-line
follow-up.

## Touch points

- New `src/main/kotlin/export/SvgWriter.kt` — pure function
  `fun writeSvg(g: Graph, bounds: Rectangle): String`.
- `ui/GraphView.kt` — `fun exportSvg(path: Path)`.
- `ui/GraphKeyListener.kt` — `Ctrl+E` → `JFileChooser` → call
  `exportSvg`.

## Testing

- Unit test: round-trip a small graph — load DOT → export SVG →
  parse SVG (regex or a tiny XML read) → assert node count matches.
- Snapshot test: export `Graph.testGraph()` to SVG and diff against
  a committed `testGraph.svg`. Diffs become the review signal.
- Manual: export, open in Firefox/Chromium, view on phone.

## Open questions

- **Fonts on the phone.** `font-family="sans-serif"` uses whatever
  the device picks. For titles where the exact font matters,
  fall back to converting text → paths via `FontRenderContext`.
  Defer until we see a problem.
- **Unicode in labels.** Escape `<`, `>`, `&`, `"`, `'` and trust
  UTF-8 for the rest. The DOT parser already accepts unicode, so
  this should round-trip fine.
- **Curved edges.** Current edges are straight lines. If we add
  spline edges later, the SVG writer gets a new branch emitting
  `<path d="M.. C.. ..">`. Not in scope for v0.1.
