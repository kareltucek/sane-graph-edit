# Command mode and key mapping system

> **Status: design.** This is a foundational spec that affects
> almost every other feature. Needs discussion before
> implementation.

## Overview

Three interrelated features:

1. **Command-mode bar** (`:`) — a text input at the bottom of the
   canvas for running commands by name.
2. **Named commands** — every editor action gets a canonical string
   name, forming the command vocabulary.
3. **Key mappings** — keys (or key sequences) are bound to command
   names. Mappings are overridable via an init file.

Together, these let the user remap any key, compose multi-key
sequences, and script the editor from a vimrc-like config file.

## How vim does it (and what to learn from)

### Mappings

Vim has two kinds:

- **`map lhs rhs`** — recursive. When `lhs` is pressed, vim
  expands `rhs` and then checks the result for *further*
  mappings. This is powerful (you can chain mappings) but
  dangerous: `map j gj` + `map gj j` → infinite loop.

- **`noremap lhs rhs`** — non-recursive. When `lhs` is pressed,
  vim expands `rhs` using only built-in commands, ignoring all
  user mappings. Safe, predictable, and what the vim community
  overwhelmingly recommends.

**Takeaway:** default to non-recursive. Call it `map` (not
`noremap` — our users aren't vim experts who know the difference).
Offer `remap` for the rare recursive case, with a warning in the
docs.

### Mode-specific mappings

Vim has separate mapping tables per mode (`nmap` for normal, `imap`
for insert, `vmap` for visual, etc.). sane-graph-edit has:

- **Canvas mode** — the main editing mode. All single-key commands
  live here.
- **Node-edit mode** — text input in the popup editor. Keys are
  literal text, not commands.
- **Style-picker mode** — mouse clicks on colour/shape buttons.
  No key dispatch.
- **Command-mode** (new) — typing in the `:` bar. Keys are literal
  text except Enter (execute) and Escape (dismiss).

Mappings should apply in **canvas mode only**. The other modes are
modal overlays where keys have their natural meaning. No `imap`
equivalent needed — the node editor is a standard text field.

### Key notation

Vim uses `<C-x>` for Ctrl+x, `<S-x>` for Shift+x, `<CR>` for
Enter, `<Esc>` for Escape, `<Space>`, `<Tab>`, etc.

We should adopt the same notation (it's well-known and
unambiguous):

| Notation    | Meaning          |
|-------------|------------------|
| `a`         | literal 'a'      |
| `A`         | literal 'A' (= Shift+a in key-typed space) |
| `<C-s>`     | Ctrl+s           |
| `<C-S-s>`   | Ctrl+Shift+s     |
| `<CR>`      | Enter            |
| `<Esc>`     | Escape           |
| `<Space>`   | Space            |
| `<Tab>`     | Tab              |

### Multi-key sequences and timeouts

If `g` is mapped to "grab" and `gt` is mapped to "next-tab", a
keypress of `g` is ambiguous: the user might be about to press `t`
(completing `gt`) or they might want `g` alone.

Vim solves this with **`timeoutlen`** (default 1000ms): after `g`,
wait up to `timeoutlen` ms for a follow-up key. If one arrives and
matches a longer mapping, use that; if the timer expires, fire the
single-key mapping.

This works but introduces perceptible latency on single-key
commands that are also prefixes. Vim users live with it; our users
might find 1000ms too sluggish for a visual editor where `g`
(grab) should feel instant.

**Options:**

1. **Shorter timeout** (e.g., 300ms). Snappier, but harder to hit
   the follow-up key in time.
2. **No timeout for mappings where the prefix has no standalone
   meaning.** If `g` alone is unbound, `gt` resolves instantly
   (no ambiguity). If `g` IS bound, apply the timeout. This
   requires the mapper to know whether a prefix has a binding.
3. **Explicit leader key.** Reserve a key (e.g., `,` or `\`) as
   the "leader" that starts all multi-key sequences. `g` stays
   instant as "grab"; `,t` / `,T` are tab navigation. No timeout
   needed because the leader itself does nothing.

Option 3 is simplest and avoids the timeout UX problem entirely.
Option 2 is the most vim-faithful. **Recommend option 2 with a
configurable `timeoutlen` defaulting to 500ms.**

### Recursive expansion pitfall

With recursive mappings, the user can accidentally create:

```
remap a b
remap b a    → infinite loop on pressing 'a'
```

Vim detects this (max recursion depth, default 1000) and errors.
We should too, if we ever support recursive mappings. But if we
default to non-recursive `map`, this can't happen — the RHS is
always resolved against built-in commands only.

### The init file

Vim's `.vimrc` is a full scripting language (vimscript, or lua in
neovim). We don't need that. A line-oriented config file is
enough:

```
# ~/.config/sane-graph-edit/init
# Comments start with #. Blank lines ignored.

# Remap keys
map gt next-tab
map gT prev-tab
map U redo
map <C-z> undo
map <C-S-z> redo

# Set options
set timeoutlen=500

# Execute a command on startup (e.g., open a specific file)
# exec open /home/karel/graphs/main.dot
```

Syntax: `map <keys> <command-name>`, `set <option>=<value>`,
`# comment`. One command per line. No conditionals, no loops, no
functions. If we ever need scripting, that's a post-Compose-port
project.

The file is read once at startup, after the session is restored.
`:source <path>` reloads it (or loads a different one).

## Architecture

### CommandRegistry

A singleton that maps command names (strings) to executable
actions.

```kotlin
object CommandRegistry {
    private val commands: MutableMap<String, (GraphView) -> Unit>

    fun register(name: String, action: (GraphView) -> Unit)
    fun execute(name: String, graphView: GraphView): Boolean
    fun list(): List<String>   // for tab-completion in ':'
}
```

Every `impl.*` method gets registered with a canonical name at
startup:

```
"undo"              → impl.undo
"redo"              → impl.redo
"delete"            → impl.deleteNode(gv, false)
"delete-reconnect"  → impl.deleteNode(gv, true)
"select-all"        → impl.selectAll
"edge-forward"      → impl.drawEdge(gv, true)
"edge-backward"     → impl.drawEdge(gv, false)
"save"              → gv.saveFile()
"save-as"           → gv.saveFileAs()
"open"              → gv.openFile()
"export-svg"        → gv.exportSvg()
"export-selection"  → gv.exportSelection()
"new-tab"           → gv.tabManager.newTab()
"close-tab"         → gv.tabManager.closeCurrent()
"next-tab"          → gv.tabManager.selectNext()
"prev-tab"          → gv.tabManager.selectPrevious()
"hide"              → impl.hideSelected
"unhide"            → impl.unhideOneLevel
"invert-selection"  → impl.invertSelection
"optimize"          → impl.optimize(gv, false)
"optimize-restrict" → impl.optimize(gv, true)
...etc
```

### KeyMapper

Holds the key→command mapping table and a pending-key state
machine for multi-key sequences.

```kotlin
class KeyMapper {
    // The mapping table. Key sequences are stored as strings
    // like "g", "gt", "<C-s>".
    private val mappings: MutableMap<String, String>

    // Default (built-in) mappings, set at construction.
    // User mappings override these.
    private val defaults: Map<String, String>

    fun map(keys: String, command: String)
    fun unmap(keys: String)
    fun resolve(keys: String): Resolution
    // Resolution = Resolved(command), Pending, NoMatch
}
```

`GraphKeyListener` changes from a hardcoded dispatch table to
delegating to `KeyMapper.resolve()`:

```
keyTyped 'g' → mapper.resolve("g")
  → Pending (because "gt" also exists)
  → start timeout
  → if 't' arrives before timeout: mapper.resolve("gt") → Resolved("next-tab")
  → if timeout expires: mapper.resolve("g") → Resolved("grab")
```

### CommandLine (the `:` bar)

A text input widget (currently `JTextField`, later a Compose
`TextField`) that appears at the bottom of the canvas when the
user presses `:`.

Supported commands in the bar:

| Command              | Action                                 |
|----------------------|----------------------------------------|
| `:w`                 | save                                   |
| `:w <path>`          | save-as to path                        |
| `:q`                 | close tab (prompt if dirty)            |
| `:wq`                | save then close tab                    |
| `:q!`                | close tab without saving               |
| `:e <path>`          | open file                              |
| `:map <keys> <cmd>`  | add a key mapping                      |
| `:unmap <keys>`      | remove a key mapping                   |
| `:set <opt>=<val>`   | set an option                          |
| `:source <path>`     | execute an init file                   |
| `/<query>`           | search forward (delegates to search)   |
| `?<query>`           | search backward                        |

Tab-completion on command names uses
`CommandRegistry.list()`.

## Default key map

The current hardcoded dispatch table becomes the default mapping:

```
# Structural edits
e       edge-forward
E       edge-backward
v       new-node-edge-forward
V       new-node-edge-backward
a       append-forward
A       append-backward
c       clear-edges
d       delete
D       delete-reconnect
o       optimize
O       optimize-restrict

# Selection & navigation
i       invert-selection
t       select-closure-forward
T       select-closure-backward
l       select-linked-forward
L       select-linked-backward
w       unselect-oldest-forward
W       unselect-oldest-backward
0       bound-screen
1       center-screen
<Space> edit-node
<S-Space> select-and-edit-node

# Filtering
h       hide
H       unhide

# Style
f       paste-format
F       copy-format

# History
u       undo
r       redo

# Movement
g       grab
G       macro:tw0

# Modifier-based
<C-s>   save
<C-S-s> save-as
<C-o>   open
<C-n>   new-tab
<C-t>   new-tab
<C-w>   close-tab
<C-e>   export-svg
<C-S-e> export-selection
<C-Tab> next-tab
<C-S-Tab> prev-tab
<C-a>   select-all
<C-c>   copy
<C-x>   cut
<C-v>   paste
<C-r>   redo
<Esc>   deselect
```

The user's init file can override any of these.

## Open questions for discussion

1. **`map` vs `noremap` naming.** I recommend `map` = non-recursive
   (safe default), `remap` = recursive. This is opposite to vim
   (where `map` is recursive). Is the vim convention too ingrained
   in your muscle memory to reverse?

2. **Timeout value.** 500ms is a guess. Too short and you can't hit
   multi-key sequences; too long and single-key commands that are
   prefixes feel laggy. Vim defaults to 1000ms. What feels right
   for a visual editor where you want instant feedback?

3. **Leader key vs timeout.** An explicit leader key (e.g., `,`)
   eliminates the timeout problem entirely but adds one keystroke
   to every multi-key sequence. Vim supports both (leader key AND
   timeout). Should we?

4. **`:` mode scope.** Should `:` commands be able to take
   arguments beyond simple key/value? E.g., `:delete` (delete
   selected), `:select /regex/` (select nodes matching pattern).
   Or keep it minimal (`:w`, `:q`, `:map`, `:set`) and let the
   canvas-mode commands do the heavy lifting?

5. **Init file location.** `~/.config/sane-graph-edit/init` matches
   XDG. Alternatively, `~/.sanegrapheditrc` matches the vim
   convention of a dotfile in `$HOME`. Or both (XDG first, fall
   back to dotfile)?

6. **Conflict resolution.** If the init file maps `d` to something
   and a later init-file line maps `d` to something else, last
   write wins (like vim). Confirm?

7. **What about the current `executeMacro` mechanism?** It runs
   a string like `"tw0"` character by character through the
   dispatch table. With the new system, a macro would be a named
   command whose body is a command sequence. Should macro strings
   use command names (`"select-closure-forward unselect-oldest-forward bound-screen"`) or
   key sequences (`"tw0"`)? Key sequences are terser but break if
   the user remaps `t`.
