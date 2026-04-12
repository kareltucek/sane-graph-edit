# Cut / yank / paste (vim-style `y`, `p`, `d`)

> **Status: backlog**

Now that we have clipboard copy/paste (`Ctrl+C/V/X`), expose it
through single-keystroke bindings that match vim muscle memory:

- `y` — yank (copy) the selection to the clipboard
- `p` — paste from the clipboard at the cursor
- `d` — already bound to delete; keep it, but make sure it also
  puts the deleted nodes into the clipboard (i.e., `d` = cut)

The use case is "split a part of a graph from where it is and put
it elsewhere" — currently requires `Ctrl+X`, navigate, `Ctrl+V`.
With `y`/`p`/`d` it's just `y`, move cursor, `p`.

**Open question:** `d` currently deletes without copying. Changing
it to cut would be a behaviour change. Maybe `d` stays as
destructive delete and `x` becomes cut? Or follow vim literally:
`d` = cut, `D` = delete-and-reconnect (already exists).
