# Persistent node marks

> **Status: design.**

## Goal

Vim-style marks for nodes: select a set, tap `m<letter>` to save
the selection under that letter, tap `'<letter>` later to
re-select the same nodes. Marks persist across save/load because
they're stored as a node attribute in the DOT file.

Use cases:

- **Human recall**: mark a subset you're editing repeatedly so
  you can jump back to it after navigating away.
- **Scripting**: combined with the headless `-e` mode, mark a
  named subset of a graph and export it programmatically.

## Behaviour

### Setting a mark: `m<letter>`

1. User selects a bunch of nodes.
2. `m` begins a prefix — next keypress is the register letter.
3. `a` (any letter a-z) completes the mapping.
4. All nodes get that letter's mark updated:
   - Nodes in the current selection **gain** the letter.
   - Nodes NOT in the current selection **lose** the letter (if
     they previously had it).
5. The operation is undoable.

So `ma` stores the current selection as mark `a`, replacing
whatever was previously marked `a`. Two successive `ma` on
different selections completely redefine mark `a` each time.

### Recalling a mark: `'<letter>`

1. `'` begins a prefix — next keypress is the register letter.
2. `a` completes the mapping.
3. The current selection is replaced with all nodes carrying
   the `a` mark.

Visible nodes only — marked-but-hidden nodes are skipped, so
filtering interacts cleanly with marks.

### Additive recall: `"<letter>` (maybe)

To add marked nodes to the selection instead of replacing it.
Stretch goal. `"a` = "add mark a to current selection".
Deferred — start with just `'a`.

## Data model

Two storage locations, picked by mark case:

```kotlin
// NodeAttributes: persistent (a-z). Wait — actually uppercase.
class NodeAttributes(
    ...
    var marks: String = "",  // concatenated UPPERCASE letters, e.g. "ABF"
    ...
)

// NodeCache: transient session marks (a-z).
data class NodeCache(
    ...
    var sessionMarks: String = "",  // concatenated lowercase letters
    ...
)
```

A string rather than a `Set<Char>` because:

- It round-trips through DOT as a simple text attribute (for
  `marks`).
- It's short (at most 26 chars for either case).
- Helper methods `hasMark(c)`, `addMark(c)`, `removeMark(c)`
  encapsulate the set semantics. The helpers route to the right
  storage based on `c.isUpperCase()`.

### DOT serialization

Only the persistent `marks` field touches DOT. In
`Node.retrieveAttributes()`:

```kotlin
"marks" to attributes.marks.takeIf { it.isNotEmpty() }
```

In `Node.applyAttribute()`:

```kotlin
"marks" -> attributes.marks = r
```

`sessionMarks` lives on `NodeCache`, never serialised — same
policy as `hideLevel`.

### Lowercase vs uppercase: persistence

Two separate slot spaces, distinguished by case:

- **Lowercase a-z** — session-only. Lost on save/load. Useful
  for transient markers ("hold this set while I navigate").
  Stored on `Node.cache` (transient) — the same place
  `hideLevel` lives.
- **Uppercase A-Z** — persistent. Saved to and loaded from the
  DOT file via `Node.attributes`. Useful for named subsets you
  want to come back to days later, and the input that the
  headless `-e` mode operates on (see `cli-headless.md`).

So `ma` and `mA` set different marks; `'a` and `'A` recall
different sets. The notation is the same, only the storage
location differs.

The user types lowercase by default (faster, no shift). When
they want a mark to persist, they use uppercase as a deliberate
"this matters" signal. Matches vim's intuition (uppercase = more
durable) without using vim's exact split (vim's uppercase
= cross-file, ours = persisted-in-file).

## Implementation sketch

### Commands

Two new commands registered in `CommandRegistry`:

```
"set-mark"   → takes a register letter; updates node.marks
"recall-mark" → takes a register letter; replaces selection
```

These are weird because they take a parameter (the letter). Two
implementation options:

1. **Generate 26 × 2 named commands**: `set-mark-a`, `set-mark-b`,
   …, `recall-mark-z`. Works with the existing `CommandRegistry`.
   Verbose but explicit.

2. **Prefix state in KeyMapper**: `m` and `'` become prefix keys
   like `q` and `@` (macro registers). They wait for one more
   keypress, then dispatch to a generic `set-mark(letter)` /
   `recall-mark(letter)` function.

Option 2 is cleaner and matches how `q`/`@` already work. Go
with it.

### KeyMapper changes

Extend the existing macro-prefix logic:

```kotlin
private var waitingForRegisterAction: Char? = null
// already handles 'q' and '@'; add 'm' and ''' (single quote)
```

`m` followed by a letter → call `setMark(letter)` (or whatever
function, registered via a callback like `commandExecutor`).

`'` followed by a letter → call `recallMark(letter)`.

### Commands implementation

Helpers on `Node` route based on case:

```kotlin
fun Node.getMarks(uppercase: Boolean): String =
    if (uppercase) attributes.marks else cache.sessionMarks

fun Node.setMarks(uppercase: Boolean, value: String) {
    if (uppercase) attributes.marks = value
    else cache.sessionMarks = value
}
```

Commands operate on whichever storage the mark letter selects:

```kotlin
fun setMark(gv: GraphView, mark: Char) {
    val upper = mark.isUpperCase()
    val sel = gv.g.selectedNodes
    val before = gv.g.nodes.associateWith { it.getMarks(upper) }
    val after = gv.g.nodes.associateWith { n ->
        if (n in sel) addChar(n.getMarks(upper), mark)
        else removeChar(n.getMarks(upper), mark)
    }
    gv.g.commit(SetMarksCommand(gv.g, upper, before, after))
}

fun recallMark(gv: GraphView, mark: Char) {
    val upper = mark.isUpperCase()
    val nodes = gv.g.nodes
        .filter { it.isVisible && it.getMarks(upper).contains(mark) }
        .toSet()
    gv.g.cleanSelect(nodes)
    gv.repaint()
}
```

New command `SetMarksCommand` in `graph_tools/Commands.kt`:
before/after maps of `Node → String` plus an `uppercase` flag
so undo/redo writes to the right storage. Recall is not
undoable (selection changes aren't, by existing policy).

### Multi-key prefix in KeyMapper

Handling `m` and `'` as prefix keys: extend
`waitingForRegisterAction` from a Char to something like a
sealed class or an enum that carries the action type, since we
now have four prefix keys (`q`, `@`, `m`, `'`) each doing
different things with the next key.

```kotlin
private enum class RegisterAction { RecordMacro, ReplayMacro, SetMark, RecallMark }
private var waitingForRegister: RegisterAction? = null
```

## Open questions

- **Invalid register letters.** What if the user types `m<Esc>`
  or `mA`? Resolved: Ignore silently, like the macro code does for
  non-alphanumeric after `q`.
- **Case.** Resolved: separate — lowercase = transient
  session-only, uppercase = persisted to DOT. See "Lowercase
  vs uppercase: persistence" above.
- **Visual indicator of which nodes are marked?** Not for v1.
  A user who needs to see marks can open the DOT file in a text
  editor.
- **Displayed in help text.** Yes — add `m<letter>` and
  `'<letter>` to `Constants.helpSelection`.

