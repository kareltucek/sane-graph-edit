# Optimize: preserve center of mass of each connected component

When the user runs `:optimize` on a full connected component (no
selection, or a selection that equals the component), the springs
can still pull the whole component in one direction — the layout
drifts. That's disorienting: the topology tidied up, but nothing
visually anchors where it was.

Fix: before returning from the optimize pass, for every connected
component whose nodes *all* moved during the pass, compute the
before/after center of mass and translate the component back by
the delta. Nothing in the relative layout changes; only the
absolute position is preserved.

Scope:
- Applies only to components that are fully relaxable (i.e. every
  node in the component got a spring). If any node was anchored
  (cross-boundary edges, `MoveSelectedOnly` with a partial
  selection), drift is fine — anchored nodes already define the
  frame.
- Probably lives in `LayoutOptimizer.compute` right after the
  `springMap.forEach { ... n.position += ... }` loop.

Open question: should we also preserve center of mass across the
whole graph (sum of all components) rather than per-component?
Probably per-component is better: if two disjoint components drift
toward each other because the gravity spring bridges them, that's
actually desired behaviour.
