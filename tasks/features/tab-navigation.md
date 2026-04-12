# Tab-like navigation: `gt`, `gT`, `gg`

> **Status: backlog**

Vim-style tab switching:

- `gt` — next tab (currently `Ctrl+Tab`)
- `gT` — previous tab (currently `Ctrl+Shift+Tab`)
- `gg` — first tab? Or "go to tab N" if preceded by a count?

**Problem:** `g` is currently bound to "grab" (toggle move mode).
Options:

1. Rebind grab to something else (e.g., `G` alone, freeing `g`
   as a prefix for `gt`/`gT`/`gg`).
2. Make `g` a "leader key" with a timeout: `g` alone after a
   short delay = grab; `g` followed quickly by `t`/`T`/another
   key = the compound command. This is how vim's `g` prefix
   works.
3. Leave `g` as grab and use different bindings for tab nav
   (keep `Ctrl+Tab` as the only way).

Option 2 is the most vim-faithful but requires a key-sequence
state machine (partial match → wait → timeout → resolve). That's
a useful primitive for the macro system (below) too.
