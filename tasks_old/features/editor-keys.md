# Which keys and events go to the embedded editor

Now: while a node is edited every key goes to neovim (the drawn
editor), a click inside the node is neovim's, and the editor starts
in insert mode only on a node with a new node's text.

To think through: under which conditions events (keys, clicks, the
wheel, Escape, the window manager's keys) are passed into the
embedded editor, and when they belong to the graph instead — e.g.
how a user leaves the editor, what Escape in normal mode means, and
whether some graph keys stay the graph's while editing.
