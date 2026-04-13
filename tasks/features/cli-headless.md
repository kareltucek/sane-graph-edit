# CLI / headless mode

> **Status: design.**

## Goal

Run the editor from a script to produce outputs (SVG exports,
filtered graph files) without ever opening a window. Primary use
case: "I have a DOT file with marked subsets (see `marks.md`);
export each marked subset to a separate SVG."

Example invocation:

```sh
sane-graph-edit input.dot -e "'a" -e ":export-selection /tmp/a.svg"
sane-graph-edit input.dot -e "'b:export-selection /tmp/b.svg<Enter>"
```

## Argument syntax

Follow vim's conventions:

```
sane-graph-edit [options] [file]
```

| Option         | Meaning                                                                                                                                                                                                                                  |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `-e '<keys>'`  | Execute a key sequence, then exit (unless `-u` given). Multiple `-e` allowed; executed in order. The argument is whatever you would type in the editor — single keys, multi-key combos, `:command<Enter>` lines, mark recalls, anything. |
| `-u`           | After executing `-e`, stay open with a window (interactive mode). Default: exit.                                                                                                                                                         |
| `[file]`       | File to load before executing commands. If omitted, starts with an empty graph.                                                                                                                                                          |
| `-h`, `--help` | Print usage and exit.                                                                                                                                                                                                                    |

With no `-e`, the editor opens its window as today. The headless
path is opt-in.

### Examples

```sh
# Open a file and export it, no window
sane-graph-edit main.dot -e ":export main.svg<Enter>"

# Combine multiple actions in one -e
sane-graph-edit main.dot -e "'a:export-selection a-only.svg<Enter>"

# Or split across multiple -e flags
sane-graph-edit main.dot -e "'a" -e ":export-selection a-only.svg<Enter>"

# Chain: recall mark a, invert selection, hide, export
sane-graph-edit main.dot -e "'aih:export out.svg<Enter>"

# Open a file interactively after pre-configuring
sane-graph-edit main.dot -u -e ":set timeoutlen=300<Enter>"
```

The `-e` argument is a key sequence in the same notation used by
`map`, macros, and the rest of the editor — there's no separate
"command" concept. `:cmd<Enter>` is just a key sequence that
starts with `:`, the same way `'a` is a key sequence that starts
with `'`. The `KeyMapper` handles both uniformly.

## Headless execution model

### What changes vs. normal startup

Normal startup:

1. `Main.main` → `EventQueue.invokeLater(::createAndShowGUI)`
2. `Window` created → `TabManager.bootstrap()` → session
   restore → `autosave.start()`.

Headless startup:

1. Parse args. If no `-e`/`-c` and no `-u`, normal path.
2. Otherwise, skip session restore and autosave.
3. Create a `HeadlessRunner` that holds:
   - A `Graph` (loaded from `[file]` or empty)
   - A `KeyMapper` pointed at a stub `HeadlessView` that the
     commands can operate on
   - Registered commands from `CommandRegistry`
4. For each `-e`/`-c`, tokenize and feed through the mapper.
5. Exit (or transition to UI if `-u`).

### HeadlessView

A minimal stand-in for `GraphView` that the commands can
manipulate without needing an actual window. The `GraphView`
type is used throughout `CommandRegistry`, so the easiest path
is to refactor the commands to depend on a smaller interface
(`CommandContext`?) that `GraphView` implements.

**Simpler alternative for v1**: construct a real `GraphView`
without attaching it to a `Window`/`TabManager`. It'll have a
`Graph`, a `ViewTransform`, and the helpers — most commands
should just work, except the ones that pop dialogs or interact
with the tab manager.

Commands that won't work headless (and should print a friendly
error):

- `save-as` (dialog) — but `:w <path>` works.
- `open` (dialog) — but `:e <path>` works.
- `export-svg` / `export-selection` (dialogs) — need a variant
  that takes a path as an argument. **Add this**: `:export
  <path>` and `:export-selection <path>` as command-bar forms.
- `new-tab` / `close-tab` / `next-tab` etc. — silently no-op
  without a `TabManager`. Acceptable.

### New `:export` command-bar form

Right now `Ctrl+E` pops a file chooser. Add a command-bar
variant that takes an explicit path:

```
:export /path/to/out.svg              — export whole graph
:export-selection /path/to/out.svg    — export current selection
```

Both work in UI mode too (nice for power users who want to type
the path instead of navigating a dialog). They become the
primary headless primitives.

Implementation: wire `:export` and `:export-selection` as
special commands in `KeyMapper.executeCommandLine` alongside
`:w`, `:q`, `:e`. Each takes an optional path argument; if
missing, fall back to the dialog (interactive only).

### Key sequence semantics

`-e "'a"` is interpreted as:

1. Tokenize: `["'", "a"]`
2. Feed each through `KeyMapper.feedKey`
3. `'` (quote) is the mark-recall prefix (see `marks.md`).
4. `a` completes it → recall mark a.

`-e ":export foo.svg<CR>"` is:

1. Tokenize: `[":", "e", "x", "p", ..., "<CR>"]`
2. Feed each through the mapper.
3. `:` opens the command bar. Subsequent chars go into the bar
   as text. `<CR>` executes.

**Caveat**: in UI mode, `:` opens a visual bar and subsequent
keypresses go to the text field. In headless mode, there's no
visual bar. The mapper's `replayRhs` already handles this
shape — `:command<CR>` is absorbed and executed directly, no
visual bar involved. Headless just reuses that same machinery
by replaying the `-e` string through `replayRhs` at the top
level.

Actually, the cleanest implementation:

```kotlin
// Pseudocode for headless execution of -e "..."
for (arg in eArgs) {
    val tokens = KeyNotation.tokenize(arg)
    for (tok in tokens) {
        mapper.feedKey(tok)
    }
}
```

Or even simpler, if `arg` starts with `:`, dispatch it directly
to `executeCommandLine` after stripping the prefix and the
trailing `<CR>`. That avoids the `:` → command-bar detour.

## Implementation plan

1. **Args parser** in `Main.kt`. Hand-rolled (no dependencies),
   accepts `-e`, `-c`, `-u`, `-h`, positional file. Unknown
   flags error out.

2. **`:export <path>` and `:export-selection <path>`** in
   `KeyMapper.executeCommandLine`. Fall back to dialog if no
   path. Path is resolved relative to the CWD at launch time,
   which is also what shell redirects expect.

3. **Headless runner**: a small top-level function
   `runHeadless(file: Path?, commands: List<String>): Int`
   that:
   - Builds a fresh `Graph` (empty or loaded from `file`)
   - Constructs a minimal `GraphView` (no `Window`, no
     `TabManager`, no `AutosaveManager`)
   - Creates a `KeyMapper` with defaults, loads the init file,
     sets `activeView`, registers all commands
   - Executes each command string
   - Returns 0 on success, non-zero on error

4. **`Main.main`**: if `-e`/`-c` present and `-u` absent, call
   `runHeadless` and `System.exit` with its return code.
   Otherwise normal path.

## Risks and open questions

- **Swing in headless JVMs.** AWT requires a DISPLAY on Linux
  by default. `-Djava.awt.headless=true` flips it, but then
  `Graphics2D` still works (for font metrics, which the renderer
  needs). We should set the system property in `runHeadless`
  before constructing any Swing widgets: `System.setProperty
  ("java.awt.headless", "true")`.

- **Error reporting.** Headless errors go to stderr with an
  exit code. Interactive errors go to dialogs. The SVG export
  path already has a dialog; needs a split so headless mode
  prints to stderr instead.

- **GraphView construction without a parent.** `GraphView`
  creates a `JLayeredPane` with children (canvas, editor, style
  picker). In headless mode those are never visible, but they
  still allocate AWT resources. Try it — if it works, no
  refactor needed. If not, introduce a `GraphViewLike`
  interface that both `GraphView` and a `HeadlessGraphView`
  implement.

- **Session persistence interaction.** Headless mode should NOT
  touch the session file (it's a quick scripted run, not a
  user's interactive state). Guard `saveSession` behind "is
  there a window?" or similar.

- **Autosave interaction.** Same — don't start the autosave
  timer for a headless run.

- **Init file.** Should `-e` runs still execute the init file?
  Probably yes — user mappings might be needed (e.g., `-e 'gt'`
  where `gt` is mapped). The init file is cheap to load.

## Testing

- Build a tiny DOT file → run with `-e ':export out.svg<CR>'`
  → verify `out.svg` is created and non-empty.
- Run with no `-e` → verify UI opens as before.
- Run with `-u -e ':set timeoutlen=300'` → verify UI opens with
  the setting applied.
- Run with an invalid command → verify non-zero exit and
  stderr message.
