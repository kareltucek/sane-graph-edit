# Layout optimizer affects the parent node

> **Status: backlog**

When running the layout optimizer (`o`/`O`) on a selection, the
optimizer moves the parent node even though the user probably
wants it anchored. The restricted variant (`O`) is meant to
address this but apparently doesn't fully.

**Desired behaviour:** the node the user is "working from" (the
`lastActiveNode`? the node under the cursor?) stays pinned during
optimization. Everything else relaxes around it.
