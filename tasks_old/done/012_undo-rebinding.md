# Undo/redo rebinding

> **Status: backlog**

Current bindings: `u` = undo, `r` = redo, `Ctrl+R` = redo.

Problems:
- No `Ctrl+Z` / `Ctrl+Shift+Z` — the standard pair that every
  other app uses. Users who don't read the help graph will try
  `Ctrl+Z` and nothing will happen.
- `r` is a valuable single-key slot. Freeing it opens it for
  other commands (e.g., rotate, or a future use).

**New bindings:**

| Key            | Action |
|----------------|--------|
| `u`            | undo (keep — matches vim) |
| `U`            | redo (shift of undo key; replaces `r`) |
| `Ctrl+Z`       | undo (standard; add back) |
| `Ctrl+Shift+Z` | redo (standard; add back) |
| `Ctrl+R`       | redo (keep for muscle memory) |

`r` becomes unbound and available for reassignment.
