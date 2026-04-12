# Roadmap

Task files in this directory, numbered in order of execution.
Done tasks live under `done/`. Feature specs under `features/`
(done features under `done/features/`).

## Done

| Task                          | File                              |
|-------------------------------|-----------------------------------|
| Undo / redo                   | `done/001-undo.md`                |
| File open / save UI           | `done/002-file-ui.md`             |
| Tabs + cross-tab copy/paste   | `done/003-tabs.md`                |
| SVG exporter                  | `done/004-svg-export.md`          |
| Graph filtering (h/H)         | `done/features/hide-filter.md`    |
| Command mode + key mappings   | `done/features/command-mode.md`   |
| Macro registers (q/@)         | `done/features/macro-registers.md`|

## Pending

| Task                          | File                              |
|-------------------------------|-----------------------------------|
| Standard menu bar             | `005-menu-bar.md`                 |

## Planned

| Task                              | File                          |
|-----------------------------------|-------------------------------|
| Port UI to Compose Multiplatform  | `006-compose-port.md`         |
| Markdown editing in node labels   | `007-markdown-editing.md`     |

## Feature backlog

| File                         | Summary                                |
|------------------------------|----------------------------------------|
| `features/yank-paste.md`    | vim-style y/p/d clipboard bindings     |
| `features/optimizer-anchor.md` | layout optimizer should not move anchor |
| `features/pull-children.md`  | pull children nodes to parent          |
| `features/mirror-rotate.md`  | mirror/rotate selection                |
| `features/dotlike-extension.md` | save as .dotlike instead of .dot    |
| `features/pin-node.md`       | always-visible node at any zoom        |
| `features/tab-navigation.md` | gt/gT/gg vim-style tab switching       |
| `features/subdivide-edges.md`| inverse of D: insert hub into edges    |
| `features/search.md`         | /query, ?query, n, N node search       |
| `features/undo-rebinding.md` | u/U/Ctrl+Z, free r                     |

## Also done (not in numbered tasks)

- Gradle (Kotlin DSL) build + wrapper
- Developer documentation + mermaid architecture diagrams
- README with install instructions + keybinding reference
- Session persistence (`~/.config/sane-graph-edit/`)
- Autosave backups (`~/.cache/sane-graph-edit/backups/`)
- AppImage + jpackage packaging
- Makefile
- Invert selection (`i`)
