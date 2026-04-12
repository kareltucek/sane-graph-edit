# Vim-style macro registers

> **Status: backlog**

Allow the user to record and replay sequences of editor commands,
similar to vim's `q{register}...q` / `@{register}`.

**Minimum viable:**

- `q` + a letter (e.g., `qa`) starts recording into register `a`.
- Every subsequent single-key command is appended to the register.
- `q` again stops recording.
- `@a` replays register `a`.

The existing `executeMacro` method in `GraphKeyListener.impl`
already runs a string of commands character by character — a macro
register is just a persistable version of that string.

**Stretch:**

- Named registers can also hold node sets (save the current
  selection into a register; recall it later to re-select those
  nodes). Not needed yet — park until a use case appears.
- Persist registers across sessions (write to
  `~/.config/sane-graph-edit/macros`).
- A macro editor UI for viewing / editing stored macros.
