# Search (vim-style `/`, `?`, `n`, `N`)

> **Status: backlog**

Find nodes by label text. Needs a minimal command-line UI:

- `/` opens a search bar at the bottom of the canvas (like vim's
  command line). Type a query, press Enter.
- The first matching node is selected and the view centres on it.
- `n` goes to the next match, `N` to the previous.
- `?` opens the bar for backward search (or just reverse the
  `n`/`N` direction).
- `Escape` dismisses the bar.
- `:` could eventually open a general command bar (`:w` = save,
  `:q` = quit, `:e foo.dot` = open), but that's a separate story.

**Implementation sketch:**

- A `JTextField` (Swing) or `TextField` composable (Compose) that
  appears at the bottom edge of the canvas, layered above it
  (same approach as NodeEditor and StylePicker).
- Matching: substring by default, regex if the query starts with
  `/` or contains unescaped regex metacharacters. Case-insensitive
  by default, case-sensitive if the query contains uppercase
  (smartcase, like vim).
- Match list: all nodes whose `attributes.text` matches, sorted
  by position (left-to-right, top-to-bottom) for a stable
  `n`/`N` traversal order.
