# Roadmap

Task files in this directory, numbered in order of execution.
Each file is self-contained: goal, design, touch points, open
questions. Check the status line at the top of each file.

## Done

| #   | Task                          | File                  |
|-----|-------------------------------|-----------------------|
| 001 | Undo / redo                   | [001-undo.md]         |
| 002 | File open / save UI           | [002-file-ui.md]      |
| 003 | Tabs + cross-tab copy/paste   | [003-tabs.md]         |
| 004 | SVG exporter                  | [004-svg-export.md]   |

## Pending

| #   | Task                          | File                  |
|-----|-------------------------------|-----------------------|
| 005 | Standard menu bar             | [005-menu-bar.md]     |

## Planned

| #   | Task                              | File                      |
|-----|-----------------------------------|---------------------------|
| 006 | Port UI to Compose Multiplatform  | [006-compose-port.md]     |
| 007 | Markdown editing in node labels   | [007-markdown-editing.md] |

## Backlog

| #   | Task                              | File                      |
|-----|-----------------------------------|---------------------------|
| 008 | User stories (vim keys, search, macros, …) | [008-user-stories.md] |

## Ordering rationale

- **004 before 005**: the menu bar includes Export SVG / Export
  Selection items that depend on the SVG exporter. Can be
  implemented in parallel (menu items greyed out until 004
  lands), but cleaner to do 004 first.
- **005 before 006**: the menu bar is small Swing work that
  ships immediately. If we defer it to the Compose port, it's
  free (Compose has `MenuBar` built in), but the Swing editor
  benefits from it now.
- **006 before 007**: Markdown rendering requires
  `AnnotatedString` / rich-text layout, which Swing can't do
  well. Compose is the prerequisite.
- **007 last**: it's a stretch feature. The editor is fully
  usable without it.

## Also done (not in numbered tasks)

These were handled as standalone commits rather than dedicated
task plans:

- Gradle (Kotlin DSL) build + wrapper
- Developer documentation + mermaid architecture diagrams
- README with install instructions + keybinding reference
- Session persistence (`~/.config/sane-graph-edit/`)
- Autosave backups (`~/.cache/sane-graph-edit/backups/`)
- AppImage + jpackage packaging
- Makefile
