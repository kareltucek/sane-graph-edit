# Where settling pulls nodes back to

Shift-drag pulls the nodes it pushed aside back to where they were
when the drag started, and the descendants that follow to where they
were relative to the selection then. Those positions are noted at
the start of each drag, in memory, and forgotten when it ends.

Problem: a drag that follows another one takes the positions the
first one left as "where they were". Nodes pushed aside by the first
drag stay aside; nothing remembers where the user had put them.

To decide: what a node's origin is across drags — the position the
user last gave it by hand (kept per node until the user moves it
again?), the document's saved position, or something else.
