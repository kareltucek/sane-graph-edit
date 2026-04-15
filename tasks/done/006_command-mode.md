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
3. **Key mappings** — keys (or key sequences) map to other key
   sequences. Mappings are overridable via an init file.

Together, these let the user remap any key, compose multi-key
sequences, and script the editor from a vimrc-like config file.

## How vim does it

### The RHS is always a key sequence

In vim, the right-hand side of every mapping is a **key sequence**,
not a command name. If you want to invoke a named command, you
spell out the keystrokes that open the command bar and type the
command:

```vim
nnoremap gt :tabnew<CR>
```

The RHS is literally `:` `t` `a` `b` `n` `e` `w` `<CR>`. Vim
replays those keys as if the user typed them: `:` opens the
command line, the letters type "tabnew", `<CR>` executes it.

We adopt the same model. **Keys all the way down.** Command
names live in the `:` bar, not in the mapping layer.

### Two tables, two resolution phases

- **`defaults`** — built-in, maps key sequences to command names.
  Hardcoded at startup, not directly visible to the user.
  `{"u": "undo", "d": "delete", "<C-s>": "save", ...}`

- **`mappings`** — user-defined (from init file or `:map`), maps
  key sequences to key sequences.
  `{"z": "u", "gt": ":next-tab<CR>"}`

Resolution:

```
press key K
  1. look up K in mappings
     → found RHS: replay RHS as key sequence
       - map (non-recursive): replay through defaults only
       - remap (recursive): replay through mappings + defaults
     → not found: fall through
  2. look up K in defaults
     → found command name: execute via CommandRegistry
     → not found: ignore
```

Example — `map z u` (non-recursive):
press `z` → mappings: `z→u` → replay `u` through defaults only
→ defaults: `u→undo` → execute.

Example — `remap a b` + `remap b c` (recursive):
press `a` → mappings: `a→b` → replay `b` through mappings+defaults
→ mappings: `b→c` → replay `c` through mappings+defaults
→ no mapping for `c` → defaults: `c→clear-edges` → execute.

Example — mapping to a named command:
```
map gt :next-tab<CR>
```
press `g`, `t` → mappings: `gt→:next-tab<CR>` → replay
`:` → opens command bar
`n`, `e`, `x`, `t`, `-`, `t`, `a`, `b` → typed into bar
`<CR>` → executes `next-tab` via CommandRegistry.

### map vs remap vs noremap

| Command    | Behaviour     | Notes |
|------------|---------------|-------|
| `map`      | non-recursive | RHS replayed through `defaults` only. Safe default. |
| `noremap`  | non-recursive | Alias for `map`. Accepted for vim muscle memory. |
| `remap`    | recursive     | RHS replayed through `mappings` + `defaults`. Powerful but can loop. |

`map` = `noremap` = safe. `remap` = recursive = your footgun,
your problem.

Recursive expansion has a max depth (default 1000). If exceeded,
the mapping is aborted and a warning is printed. This prevents
`remap a b` + `remap b a` from hanging the editor.

### Macro registers are always recursive

When `@a` replays a macro register, the recorded key sequence
is fed through the full pipeline (mappings + defaults), same as
`remap`. This matches vim: a macro replays keystrokes as if the
user typed them, and user mappings apply inside macros.

The distinction is:

- **`map`/`noremap`**: the key-binding layer. Non-recursive.
  You're telling the editor "when I press X, pretend I pressed
  Y, and resolve Y against built-ins only."
- **`remap`**: same layer, but Y is resolved through both
  user mappings and built-ins. Rare, dangerous, available.
- **Macro registers** (`@a`): the automation layer. Always
  recursive. You recorded a sequence of keystrokes, and replay
  should behave as if you typed them again — including any
  remappings you've configured.

### Mode-specific mappings

Vim has separate mapping tables per mode (`nmap`, `imap`, `vmap`).
sane-graph-edit has:

- **Canvas mode** — the main editing mode. Single-key commands.
- **Node-edit mode** — text input in the popup editor.
- **Style-picker mode** — mouse clicks on colour/shape buttons.
- **Command-mode** (new) — typing in the `:` bar.

Mappings apply in **canvas mode only**. The other modes are modal
overlays where keys have their natural meaning.

### Key notation

Adopted from vim (well-known, unambiguous):

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

When a key is pressed, the mapper checks whether it is a
**prefix** of any longer mapping:

- **Not a prefix of anything longer** → resolve immediately.
  No wait, no latency. E.g., `d` mapped and nothing starts
  with `d...` → pressing `d` fires instantly.
- **IS a prefix of a longer mapping** (e.g., `g` is pressed and
  `gt` exists) → start a timer (`timeoutlen`, default 500ms).
  If a follow-up key arrives and completes a longer mapping,
  fire that. If the timer expires: fire `g`'s standalone binding
  if it has one, or discard as incomplete if it doesn't.

Consequences:

- Keys that aren't prefixes (the vast majority) are instant.
- Keys that ARE prefixes of multi-key sequences incur a small
  delay — the cost of supporting `gt`-style combos.
- A "leader key" (e.g., `,`) that has no standalone binding and
  is only ever a prefix never triggers the timeout — it waits
  indefinitely for the follow-up, since there's nothing to fire
  on expiry.

`timeoutlen` is configurable via `set timeoutlen=500`.

## Architecture

### CommandRegistry

Maps command names (strings) to executable actions.

```kotlin
object CommandRegistry {
    private val commands: MutableMap<String, (GraphView) -> Unit>

    fun register(name: String, action: (GraphView) -> Unit)
    fun execute(name: String, graphView: GraphView): Boolean
    fun list(): List<String>   // for tab-completion in ':'
}
```

Every `impl.*` method gets registered with a canonical name:

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

Two tables + pending-key state machine.

```kotlin
class KeyMapper {
    // Built-in: key sequence → command name.
    private val defaults: Map<String, String>

    // User-defined: key sequence → key sequence.
    // Populated from init file and :map commands.
    private val mappings: MutableMap<String, Mapping>

    data class Mapping(val rhs: String, val recursive: Boolean)

    fun map(keys: String, rhs: String, recursive: Boolean = false)
    fun unmap(keys: String)

    // Returns Resolved(commandName), Pending, or NoMatch.
    fun resolve(pendingKeys: String): Resolution
}
```

Resolution flow on each keypress:

```
keyTyped 'g' → mapper.resolve("g")
  → "g" is a prefix of "gt" → Pending
  → start timeout (500ms)
  → 't' arrives → mapper.resolve("gt")
    → mappings: "gt" → ":next-tab<CR>" (non-recursive)
    → replay ":next-tab<CR>" through defaults
    → ':' opens command bar, "next-tab" typed, <CR> executes
  OR timeout expires → mapper.resolve("g") (standalone)
    → defaults: "g" → "grab" → execute
```

### CommandLine (the `:` bar)

A text input at the bottom of the canvas. Opens on `:`, `/`, or
`?`. Dismissed by `<Esc>` or `<CR>` (execute).

| Input       | Action                                 |
|-------------|----------------------------------------|
| `:w`        | save                                   |
| `:w <path>` | save-as to path                        |
| `:q`        | close tab (prompt if dirty)            |
| `:wq`       | save then close tab                    |
| `:q!`       | close tab without saving               |
| `:e <path>` | open file                              |
| `:map`      | add a non-recursive key mapping        |
| `:noremap`  | alias for `:map`                       |
| `:remap`    | add a recursive key mapping            |
| `:unmap`    | remove a key mapping                   |
| `:set`      | set an option (`timeoutlen`, etc.)     |
| `:source`   | execute an init file                   |
| `/<query>`  | search forward (delegates to search)   |
| `?<query>`  | search backward                        |

Tab-completion on command names uses `CommandRegistry.list()`.

### The init file

`~/.config/sane-graph-edit/init`. Read once at startup, after
session restore.

```
# Comments start with #. Blank lines ignored.

# Non-recursive (safe, default)
map gt :next-tab<CR>
map gT :prev-tab<CR>
map <C-z> u
map <C-S-z> U

# Recursive (your footgun, your problem)
remap x d

# noremap is an alias for map
noremap z u

# Options
set timeoutlen=500
```

Syntax: one command per line. Same commands as the `:` bar.
No conditionals, no loops, no functions.

`:source <path>` reloads it (or loads a different file).

## Default key map

The current hardcoded dispatch table becomes the `defaults`
table. These map key sequences to command names and are not
user-visible as "mappings" — they're the terminal resolution
layer.

```
# Structural edits
e       → edge-forward
E       → edge-backward
v       → new-node-edge-forward
V       → new-node-edge-backward
a       → append-forward
A       → append-backward
c       → clear-edges
d       → delete
D       → delete-reconnect
o       → optimize
O       → optimize-restrict

# Selection & navigation
i       → invert-selection
t       → select-closure-forward
T       → select-closure-backward
l       → select-linked-forward
L       → select-linked-backward
w       → unselect-oldest-forward
W       → unselect-oldest-backward
0       → bound-screen
1       → center-screen
<Space> → edit-node
<S-Space> → select-and-edit-node

# Filtering
h       → hide
H       → unhide

# Style
f       → paste-format
F       → copy-format

# History
u       → undo
r       → redo

# Movement
g       → grab

# Modifier-based
<C-s>   → save
<C-S-s> → save-as
<C-o>   → open
<C-n>   → new-tab
<C-t>   → new-tab
<C-w>   → close-tab
<C-e>   → export-svg
<C-S-e> → export-selection
<C-Tab> → next-tab
<C-S-Tab> → prev-tab
<C-a>   → select-all
<C-c>   → copy
<C-x>   → cut
<C-v>   → paste
<C-r>   → redo
<Esc>   → deselect
```

The user's init file adds to or overrides the `mappings` table
(key→key). The `defaults` table (key→command) is immutable.

## Design decisions (resolved)

1. **RHS of mappings is always a key sequence.** Not a command
   name. To invoke a command by name, spell out the keystrokes:
   `map gt :next-tab<CR>`. This matches vim exactly and avoids
   the ambiguity of "is `put` a command name or p+u+t?".

2. **`map` = `noremap` = non-recursive.** The safe thing gets
   the obvious name. `noremap` accepted as alias for vim muscle
   memory.

3. **`remap` = recursive.** Supported, with max recursion depth
   1000. Available for users who know what they're doing.

4. **Macro registers are always recursive.** Replaying a macro
   feeds each key through the full pipeline (mappings + defaults)
   as if the user typed it. This is the vim model.

5. **Timeout = 500ms** (`set timeoutlen=500`). Only applies when
   a pressed key is a prefix of a longer mapping. Configurable.

6. **`:` bar is minimal.** `:w`, `:q`, `:map`, `:set`, `:source`,
   `/`, `?`. Canvas-mode keys do the heavy lifting.

7. **Init file: `~/.config/sane-graph-edit/init`** (XDG).

8. **Conflict resolution: last write wins.**
