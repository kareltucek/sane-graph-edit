# Scale command + transform mode (vim-style `s`, `sx`, `sy`)

> **Status: spec / backlog**

Add a third cursor-driven transform to join `grab` (move) and
`rotate`: **scale**. Cursor position drives the scale factor; the
selection scales around its bbox centre. Tap `x` / `y` while in
the gesture to constrain to one axis. Same gesture shape applies
to grab and rotate — the new "Transform" mode hosts all three.

The shape is deliberately Blender-like (`g`/`r`/`s`, with `x`/`y`
axis locks), since that's the muscle memory target.

## User-visible behaviour

`s` is the default binding for scale.

- `s` — enter scale mode. Cursor movement scales the selection
  around its bbox centre. Second `s` commits as one undoable
  step. `<Esc>` cancels and reverts positions.
- `sx` — enter scale mode, then lock to the X axis: only
  horizontal scale changes, Y stays 1.0. Tap `x` again to release
  the lock and return to free 2D scale. Blender semantics: `x`
  locks *Y*.
- `sy` — same but vertical. `sy` squashes / stretches the
  subgraph vertically.
- `x` / `y` work *while a transform gesture is live* (scale,
  grab, or rotate — see "Cross-mode reuse" below). Outside those
  gestures they retain their normal bindings.

The mode is advertised in a new status-bar line at the bottom of
the canvas (see "Status bar").

## Architecture

### Modal gesture — mirrors rotate

The state machine lives on `GraphMouseController`, where grab
and rotate already live:

- New enum value `States.Scaling` alongside `MovingNodes`,
  `Rotating`, etc. (GraphMouseListener.kt:202).
- New method `startOrEndScale()` modelled on
  `startOrEndRotate()`. First call → `captureScaleStart()`
  snapshots positions into `scaleStartPositions`, records the
  bbox centre in world coordinates (the scale anchor), and
  records the start cursor position in *screen* coordinates
  (the factor reference — see below). Second call → commits a
  `MoveNodesCommand(g, before, after)` via
  `history.commitWithoutRun(...)` (same pattern as rotate,
  GraphMouseController.kt:302–305).
- `dragScale(screenPos)` is called from `mouseMoved` /
  `mouseDragged` (same hook points as `dragRotate`,
  GraphMouseListener.kt mouseMoved branch). It recomputes every
  node's position from the captured starting positions each
  frame — no accumulated drift.
- `cancelActiveModal()` grows a new branch for `Scaling` that
  restores `scaleStartPositions`. `<Esc>` already routes through
  here via `impl.unselectAll` (GraphKeyListener.kt:324).
- `s` invokes `mouseListener.controller.startOrEndScale()`
  through `CommandRegistry`, same wiring as grab
  (GraphKeyListener.kt:111).

### Scale anchor and scale factor

**Anchor (where the selection scales from):** bbox centre of the
selection in world coordinates, captured on entry. Selection
grows or shrinks in place — it doesn't slide across the canvas.

**Factor (how much to scale):** cursor distance from *screen
centre*, per axis, compared against the cursor's distance at
gesture start.

```
cx, cy = screen centre (canvas.width / 2, canvas.height / 2)
dx0 = cursorStartScreen.x - cx
dy0 = cursorStartScreen.y - cy
dx  = cursorNowScreen.x   - cx
dy  = cursorNowScreen.y   - cy

sx = dx / dx0   (if not axis-locked)
sy = dy / dy0   (if not axis-locked)

n.position = anchor + ((startPos − anchor) scaled by (sx, sy))
```

Using screen centre (rather than the bbox centre or the start
cursor position) gives a stable, pan/zoom-independent reference:
the user can think of the screen as a 2D "scale dial" where the
centre is `1.0` and moving outward grows the selection.

Degenerate case: if the cursor starts within ±ε pixels of the
screen-centre line on either axis, `dx0` or `dy0` is effectively
zero and the ratio blows up. Freeze the affected axis at `1.0`
until the cursor moves outside ε, then begin scaling from there
(re-bootstrap that axis's `dx0` at the first frame where `|dx0|
> ε`). This keeps the gesture usable even if the user happens
to click near screen centre.

### Cross-mode reuse: all three gestures enter Transform mode

Grab, rotate, and scale all flip the mapper into the shared
"Transform" mode on entry and restore "Normal" on commit /
cancel. This means `x` / `y` axis locks are uniformly available
across gestures:

- **Scale:** axis lock forces that axis's scale factor to `1.0`
  (the main use case).
- **Grab:** axis lock constrains cursor-driven translation to
  the unlocked axis. `grab` + `x` → drag only along X.
- **Rotate:** axis lock is a deliberate no-op in 2D. Tapping `x`
  or `y` during rotate flashes a hint in the status bar but
  does not change rotation; 2D rotation has only one axis (Z).

Axis-lock state is a pair of booleans on the mouse controller,
shared across the three gestures: `lockX: Boolean, lockY: Boolean`
(Blender semantics: `x` toggles `lockY`, `y` toggles `lockX`).
Locks reset when the gesture ends.

### Mode-scoped key dispatch on KeyMapper

Today the mapper is entirely global (KeyMapper.kt `feedKey`) —
defaults and user mappings share one namespace. To make `x`/`y`
behave differently mid-gesture we add a minimal mode layer:

- `var mode: Mode = Mode.Normal` on `KeyMapper`. Values:
  `Normal`, `Transform`.
- `modeBindings: Map<Mode, Map<String, String>>` consulted
  *before* `mappings` and `defaults`.
- `startOrEndScale`, `startOrEndMove`, `startOrEndRotate` set
  `mode = Transform` on entry; commit / cancel restore
  `Mode.Normal`.
- Bindings registered into `Mode.Transform`:
  - `x` → `toggle-axis-lock-y`
  - `y` → `toggle-axis-lock-x`
  - `<Esc>` → falls through to the global `cancelActiveModal`
    path (works today via `impl.unselectAll`).
  - `s` / `g` / `r` (or whatever is bound to the active
    gesture's toggle) → falls through so the second tap still
    commits.
- **Other keys are swallowed.** In `Transform` mode, any key not
  listed above is consumed and ignored — no fall-through to
  normal dispatch. This prevents accidental `u` / `d` / etc.
  mid-gesture.

### Status bar

The canvas currently has no persistent status line. Add one.

- New `StatusBar` JComponent, a thin monospace label anchored
  to the bottom edge of `GraphView` (same anchoring pattern as
  `CommandBar`, GraphView.kt wherever `placeMeAt` is used).
- Displays a compact mode indicator:
  - Normal mode: empty (bar visible as a 1-line slate, or
    hidden — pick one; leaning "always visible" so there's no
    layout jump on mode change).
  - Transform mode: `-- SCALE --`, `-- GRAB --`, `-- ROTATE --`
    plus an axis-lock suffix when set: `-- SCALE (X) --` means
    "X axis only" (Y is locked).
  - Macro recording (`isRecording` on KeyMapper, line 79):
    piggyback — show `recording @q` when active. This finally
    surfaces a state that has no visible indicator today.
- Controller ownership: `GraphView.statusBar` holds the
  component; `KeyMapper` / `GraphMouseController` push updates
  via a callback (`onModeChange: (ModeInfo) -> Unit`) set by
  the view during construction, same shape as `markSetter` on
  KeyMapper (KeyMapper.kt:85).

Keep scope tight: no colours, no flashing, no right-aligned
clock. Just a text line.

### Cursor tracking

For scale specifically we need cursor position in *screen*
coordinates (the scale factor is screen-centre-relative). Grab
and rotate already read world coordinates from
`GraphView.lastCursorPosition`. `dragScale` takes the raw AWT
`MouseEvent.point` and computes against canvas-centre directly —
no transform involved, and it's unaffected by pan/zoom, which
matches the "screen as scale dial" mental model.

### Undo

One `MoveNodesCommand(g, before, after)` on commit, via
`history.commitWithoutRun`. The UI has already moved nodes
during the drag; the command is only added to the undo stack,
not re-executed. Cancel (`<Esc>`) restores `scaleStartPositions`
directly and writes *no* history entry — matching rotate and
grab.

## Implementation order

1. **Mouse controller state.** Add `States.Scaling`,
   `scaleStartPositions`, `scaleAnchor`, `scaleStartCursorScreen`
   on `GraphMouseController`. Implement `startOrEndScale`,
   `captureScaleStart`, `commitScaleIfAny`, `dragScale`, and
   grow `cancelActiveModal`.
2. **Axis-lock flags.** Add `lockX`, `lockY` (shared across
   gestures) and `toggle-axis-lock-x` / `toggle-axis-lock-y`
   commands. Have `dragScale` / `dragMoveNode` respect the
   flags; `dragRotate` explicitly ignores them (but a status-bar
   flash when tapped is a nice-to-have).
3. **KeyMapper.mode + modeBindings.** Add the field and the
   lookup order. Wire `startOrEndMove` / `startOrEndRotate` /
   `startOrEndScale` to flip the mode on entry/exit. Swallow
   unbound keys while in `Transform`.
4. **Default bindings.** Register `s` in normal-mode defaults;
   register `x` / `y` in Transform-mode bindings.
5. **StatusBar component.** New file `StatusBar.kt`, wire into
   `GraphView`, plumb a `ModeInfo` callback through KeyMapper
   and the mouse controller.
6. **Tests.** Mirror `MirrorRotateTest`: free scale, X-locked
   scale, Y-locked scale, cancel reverts, undo/redo round-trip,
   commit-to-history is a single step, Transform mode swallows
   unbound keys, axis-lock during grab constrains movement.

## Remaining unknowns

None blocking. Things to watch during implementation:

- **StatusBar always-visible vs mode-only.** Leaning
  always-visible (one-line slate) to avoid layout shifts.
  Decide when writing the component.
- **Degeneracy epsilon.** A few pixels (say 4) for the
  screen-centre band. Tune by feel during the first round.
- **Rotate + axis-lock UX.** Flashing a "no-op in 2D" hint the
  first time a user taps `x` during rotate is friendly but
  cheap to skip.
