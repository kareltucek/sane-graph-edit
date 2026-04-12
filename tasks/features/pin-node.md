# Pin a node (always-visible at readable size)

> **Status: backlog**

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
