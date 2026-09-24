# Subdivide edges (inverse of `D`)

> **Status: backlog**

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
