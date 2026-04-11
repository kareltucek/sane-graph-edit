# File save / open UI

## Goal

Replace the hardcoded `dot.dot` filename with real file dialogs.

## Current state

```kotlin
// GraphView.kt
fun saveFile() { DotGraphLoader.saveToFile(g, "dot.dot") }
fun loadFile() { g = DotGraphLoader.loadFromFile("dot.dot"); boundScreen() }
```

Both hardcoded to a file named `dot.dot` in the process CWD. No
error handling, no dirty tracking, no file path memory.

## Design

### Bindings

| Shortcut         | Action                                              |
|------------------|-----------------------------------------------------|
| `Ctrl+N`         | New empty graph (in current tab, or new tab)        |
| `Ctrl+O`         | Open — `JFileChooser`, filters for `*.dot`          |
| `Ctrl+S`         | Save to current path; if unset, fall through to Save As |
| `Ctrl+Shift+S`   | Save As — `JFileChooser`, DOT filter                |
| `Ctrl+E`         | Export SVG — see `svg-export.md`                    |

### `GraphView` state additions

```kotlin
class GraphView {
    var currentFile: Path? = null
    var isDirty: Boolean = false
    // window title reflects: "[*] filename.dot — sane-graph-edit"
}
```

`isDirty` flips to `true` on any mutation (hooked once we have the
`Command` abstraction from `undo.md` — every `history.apply` marks
dirty). `saveFile()` / `saveAs()` clear it. Open/new clear it.

### Remember last directory

Persist the last-used directory to `~/.config/sane-graph-edit/state`
(or `System.getProperty("user.home") + "/.sanegrapedit.json"` to
avoid adding a JSON dependency — it's two fields, plain `Properties`
is fine).

```properties
lastOpenDir=/home/karel/notes/graphs
lastExportDir=/home/karel/notes/graphs
```

### Close-while-dirty prompt

Override `JFrame.processWindowEvent` to intercept `WINDOW_CLOSING`.
If any tab is dirty, show `JOptionPane.showConfirmDialog` with
Save / Discard / Cancel. Cancel aborts the close, the other two do
what they say.

For individual tab-close (once tabs land), same prompt scoped to
one tab.

### Recent files — defer

Stretch, not in scope. Persist structure is already there, just no
menu to show it. Add a `File → Recent` submenu when/if we ever grow
a menu bar.

## Touch points

- `ui/GraphView.kt` — replace `saveFile`/`loadFile`, add
  `currentFile`, `isDirty`.
- New `ui/FileOps.kt` — `openDialog`, `saveDialog`, `exportDialog`,
  all returning a `Path?` (null on cancel).
- New `ui/AppState.kt` — tiny `Properties`-backed preference store
  for last-used directories.
- `ui/Window.kt` — title update + close prompt.
- `ui/GraphKeyListener.kt` — new bindings; route through
  `GraphView`, not directly to `DotGraphLoader`.

## Testing

- Unit: `AppState` round-trips directory paths.
- Manual: open → edit → close → "save changes?" prompt appears.
- Manual: `Ctrl+S` on a fresh graph opens Save As; afterwards,
  `Ctrl+S` writes silently.

## Open questions

- **SVG export filter extension.** If the user types `foo` in Save
  As and the filter is `*.svg`, do we add the extension? Convention
  (VS Code, Inkscape) says yes — append if missing. Implement that.
- **Non-existent load path.** Current code would crash with an
  `IOException`. Replace with a `JOptionPane` error dialog.
