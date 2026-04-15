# Roadmap

Active work lives in `tasks/` and `tasks/features/`.
Completed work is under `tasks/done/` numbered by completion
order (`NNN_name.md`).

When a task is finished, move its file into `tasks/done/` and
give it the next available `NNN_` prefix so the directory
reflects the sequence in which things shipped.

## Done (chronological)

| #   | Task                             | File                                     |
|-----|----------------------------------|------------------------------------------|
| 001 | Undo / redo                      | `done/001_undo.md`                       |
| 002 | File open / save UI              | `done/002_file-ui.md`                    |
| 003 | Tabs + cross-tab copy/paste      | `done/003_tabs.md`                       |
| 004 | SVG exporter                     | `done/004_svg-export.md`                 |
| 005 | Graph filtering (`h`/`H`)        | `done/005_hide-filter.md`                |
| 006 | Command mode + key mappings      | `done/006_command-mode.md`               |
| 007 | Macro registers (`q`/`@`)        | `done/007_macro-registers.md`            |
| 008 | Search (`/`, `?`, `n`, `N`)      | `done/008_search.md`                     |
| 009 | Tab navigation (`gt`/`gT`)       | `done/009_tab-navigation.md`             |
| 010 | Persistent node marks (`m`/`'`)  | `done/010_marks.md`                      |
| 011 | CLI / headless mode              | `done/011_cli-headless.md`               |
| 012 | Undo/redo rebinding              | `done/012_undo-rebinding.md`             |
| 013 | Mirror / rotate                  | `done/013_mirror-rotate.md`              |

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
| `features/yank-paste.md`     | vim-style y/p/d clipboard bindings     |
| `features/optimizer-anchor.md` | layout optimizer should not move anchor |
| `features/pull-children.md`  | pull children nodes to parent          |
| `features/dotlike-extension.md` | save as .dotlike instead of .dot    |
| `features/pin-node.md`       | always-visible node at any zoom        |
| `features/subdivide-edges.md`| inverse of D: insert hub into edges    |

## Also done (not in numbered tasks)

- Gradle (Kotlin DSL) build + wrapper
- Developer documentation + mermaid architecture diagrams
- README with install instructions + keybinding reference
- Session persistence (`~/.config/sane-graph-edit/`)
- Autosave backups (`~/.cache/sane-graph-edit/backups/`)
- AppImage + jpackage packaging
- Makefile
- Invert selection (`i`)
- `:help` and `:map` bindings listing
