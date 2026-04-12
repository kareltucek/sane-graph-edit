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

## Pending

| #   | Task                          | File                  |
|-----|-------------------------------|-----------------------|
| 004 | SVG exporter                  | [004-svg-export.md]   |

## Planned

| #   | Task                              | File                      |
|-----|-----------------------------------|---------------------------|
| 005 | Port UI to Compose Multiplatform  | [005-compose-port.md]     |
| 006 | Markdown editing in node labels   | [006-markdown-editing.md] |

## Ordering rationale

- **004 before 005**: SVG export is pure model→file, doesn't
  touch the UI, and ships a user-visible feature. Doing it while
  Swing is still the UI means fewer moving parts.
- **005 before 006**: Markdown rendering requires
  `AnnotatedString` / rich-text layout, which Swing can't do
  well. Compose is the prerequisite.
- **006 last**: it's a stretch feature. The editor is fully
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
