# Search (`/`, `?`, `n`, `N`)

> **Status: in progress.**

## Goal

Find nodes by label text with live visual feedback.

## Behaviour

### Opening the search bar

- `/` opens the search bar at the bottom of the canvas (reuses
  the existing `CommandBar`). Cursor is in the text field.
- `?` does the same (reverse direction for `n`/`N` — see below).
- The bar shows the `/` or `?` prefix so the user knows which
  mode they're in.

### Live selection while typing

As the user types into the search bar, the editor continuously:

1. Finds all visible nodes whose `attributes.text` contains the
   query as a substring (case-insensitive by default; case-
   sensitive if the query contains an uppercase letter —
   smartcase, like vim).
2. Selects those nodes (replaces the current selection).
3. Repaints so the matching nodes highlight in real time.

This gives instant feedback: you see the matches narrow as you
type, without waiting for Enter.

### Confirming with Enter

- The current match set is stored as the **search result cache**
  (a `List<Node>` sorted by position, and the query string).
- The view zooms to fit all matches (`boundScreen` / key `0`
  behaviour).
- The search bar closes. Focus returns to the canvas.
- The `n`/`N` cursor is reset to the start of the result list.

### Dismissing with Escape

- Clears the search bar text.
- Restores the selection to whatever it was before the search
  started (undo the live-selection preview).
- Does NOT update the search result cache.

### Navigating matches: `n` / `N`

After a confirmed search (`/query<CR>`):

- `n` — focus the next match. "Focus" means: select that one
  node and center the view on it (`centerScreen` / key `1`).
  The `n`/`N` cursor advances through the cached result list,
  wrapping at the ends.
- `N` — focus the previous match (reverse direction).

If the search was opened with `?` instead of `/`, the `n`/`N`
directions are swapped (like vim).

The cached result list is **independent of the current
selection** — pressing `n` always cycles through the matches
from the last confirmed search, even if the user has since
selected other nodes manually.

### Smartcase

- All-lowercase query → case-insensitive match.
- Query contains at least one uppercase letter → case-sensitive.

Matches vim's `smartcase` option. No regex for now — plain
substring is enough. Regex can be a future extension.

## Implementation sketch

### State on GraphView (or a dedicated SearchState)

```kotlin
class SearchState {
    var query: String = ""
    var results: List<Node> = emptyList()
    var cursor: Int = 0         // index into results for n/N
    var forward: Boolean = true // / = true, ? = false
    var selectionBeforeSearch: Set<Node> = emptySet()
}
```

### CommandBar changes

The `CommandBar` already handles `/` and `?` prefixes (currently
they print "not yet implemented"). Wire them to:

1. On open with `/` or `?`: capture `selectionBeforeSearch`.
2. Add a `DocumentListener` to the text field that fires on
   every keystroke → runs the live-match logic → updates
   selection → repaints.
3. On Enter: store results + query in `SearchState`, zoom,
   close bar.
4. On Escape: restore `selectionBeforeSearch`, close bar.

### `n` / `N` bindings

Register two new commands:

```
"search-next"     → advance cursor, select result, centerScreen
"search-prev"     → same, backwards
```

Add to defaults:

```
"n" → "search-next"
"N" → "search-prev"
```

### Match sorting

Results sorted by position (top-to-bottom, left-to-right) so
`n`/`N` traversal has a spatial order:

```kotlin
results.sortedWith(compareBy({ it.position.y }, { it.position.x }))
```

## Open questions

- **Hidden nodes.** Should search find hidden nodes? Probably
  not — you're searching what you can see. Filter to
  `isVisible` before matching.
- **Empty query.** Live-select with empty query = select nothing
  (or restore pre-search selection). On Enter with empty query =
  no-op, close bar.
