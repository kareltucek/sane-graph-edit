F# 005 — Standard menu bar

> **Status: pending.** Can be done immediately (Swing) or deferred
> to the Compose port. Swing implementation is straightforward
> (`JMenuBar`); Compose equivalent is `MenuBar { Menu { Item } }`.
> Either way the work is small.

## Goal

Add a standard top menu bar so the editor's file operations are
discoverable without memorising keyboard shortcuts. The menu bar
is the universal "I don't know how this app works yet" entry
point — right now there is none, and a new user staring at a
blank canvas has no affordance beyond the help graph.

## Menus

### File

| Label              | Shortcut         | Action                                |
|--------------------|------------------|---------------------------------------|
| New Tab            | Ctrl+N           | `TabManager.newTab()`                 |
| Open…              | Ctrl+O           | `GraphView.openFile()`                |
| Save               | Ctrl+S           | `GraphView.saveFile()`                |
| Save As…           | Ctrl+Shift+S     | `GraphView.saveFileAs()`              |
| ─────              |                  |                                       |
| Export SVG…        | Ctrl+E           | `GraphView.exportSvg()` *(after 004)* |
| Export Selection…   | Ctrl+Shift+E     | export selected nodes only *(after 004)* |
| ─────              |                  |                                       |
| Close Tab          | Ctrl+W           | `TabManager.closeCurrent()`           |
| Quit               | Ctrl+Q           | close-all-with-dirty-prompt + exit    |

Export items are greyed out (or hidden) until the SVG exporter
(task 004) lands.

### Edit

| Label              | Shortcut         | Action                                |
|--------------------|------------------|---------------------------------------|
| Undo               | u                | `History.undo()`                      |
| Redo               | r                | `History.redo()`                      |
| ─────              |                  |                                       |
| Cut                | Ctrl+X           | `cutSelection()`                      |
| Copy               | Ctrl+C           | `copySelection()`                     |
| Paste              | Ctrl+V           | `pasteClipboard()`                    |
| ─────              |                  |                                       |
| Select All         | Ctrl+A           | `selectAll()`                         |
| Deselect           | Escape           | `unselectAll()`                       |

### View

| Label                      | Shortcut | Action                        |
|----------------------------|----------|-------------------------------|
| Fit to All / Selection     | 0        | `boundScreen()`               |
| Centre on Selection        | 1        | `centerScreen()`              |
| ─────                      |          |                               |
| Run Layout Optimiser       | o        | `optimize(false)`             |
| Run Layout (Restricted)    | O        | `optimize(true)`              |

### Help

| Label          | Action                                         |
|----------------|-------------------------------------------------|
| Show Help Graph| open a new tab with `Graph.defaultGraph()`     |
| About          | small dialog: name, version, attribution, link |

## Implementation (Swing path)

In `Window.createUI`, before `add(tabManager.tabbedPane)`:

```kotlin
val menuBar = JMenuBar()

val fileMenu = JMenu("File")
fileMenu.add(JMenuItem("New Tab").also {
    it.accelerator = KeyStroke.getKeyStroke(...)
    it.addActionListener { tabManager.newTab() }
})
// ... etc

menuBar.add(fileMenu)
menuBar.add(editMenu)
menuBar.add(viewMenu)
menuBar.add(helpMenu)
jMenuBar = menuBar
```

Shortcuts are already implemented in `GraphKeyListener`; the
menu items just provide discoverability. The `accelerator`
annotations on menu items show the shortcut text in the menu —
they do NOT add a second binding (Swing deduplicates).

## Touch points

- `ui/GraphView.kt` (`Window` class) — add the `JMenuBar`.
- `ui/GraphKeyListener.kt` — add `Ctrl+Q` binding (currently
  missing; closing the window goes through the window listener,
  but there's no keyboard shortcut for it).
- `ui/GraphView.kt` (`GraphView` class) — add `exportSvg` and
  `exportSelection` stubs that become real once 004 lands.

## Notes

- Menu bar eats ~20–25 pixels of vertical space. The canvas
  shrinks accordingly. Since GraphView already handles arbitrary
  sizes via SpringLayout, this should be transparent.
- The menu bar introduces a second path to every action (menu
  click vs keyboard). Both should go through the same `impl`
  methods to avoid divergence.
- On macOS, Swing's `apple.laf.useScreenMenuBar` system property
  moves the menu bar to the system menu bar. Not a priority but
  free if someone sets it.
