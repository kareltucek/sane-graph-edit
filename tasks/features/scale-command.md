# Scale command + transform mode (vim-style `s`, `sx`, `sy`)

> **Status: spec / backlog**

Add a third cursor-driven transform to join `grab` (move) and
`rotate`: **scale**. Cursor movement away from the selection's
bbox centre grows the selection; movement inward shrinks it.
Tap `x` / `y` while in the gesture to constrain to one axis.

The shape is deliberately Blender-like (`g`/`r`/`s`, with `x`/`y`
axis locks), since that's the muscle memory target.

## User-visible behaviour

Assume `s` is bound to scale (actual key TBD; see open questions).

- `s` — enter scale mode. Cursor movement scales the selection
  around its bbox centre. Second `s` commits as one undoable
  step. `<Esc>` cancels and reverts positions.
- `sx` — enter scale mode, then immediately lock to the X axis:
  only horizontal scale changes, Y stays 1.0. Tap `x` again to
  release the lock and return to free 2D scale.
- `sy` — same but vertical. `sy` squashes / stretches the
  subgraph vertically.
- `x` / `y` work *while in scale mode*. Outside scale mode they
  retain their normal bindings (nothing by default).

## Architecture

### Modal gesture — mirrors rotate

The state machine lives on `GraphMouseController`, where grab
and rotate already live:

- New enum value `States.Scaling` alongside `MovingNodes`,
  `Rotating`, etc. (GraphMouseListener.kt:202).
- New method `startOrEndScale()` modelled on
  `startOrEndRotate()`. First call → `captureScaleStart()`
  snapshots positions into `scaleStartPositions` and records
  the initial cursor-to-centre vector. Second call → commits a
  `MoveNodesCommand(g, before, after)` via
  `history.commitWithoutRun(...)` (same pattern as rotate,
  GraphMouseController.kt:302–305).
- `dragScale(pos)` is called from `mouseMoved` / `mouseDragged`
  (same hook points as `dragRotate`, GraphMouseListener.kt
  mouseMoved branch). It recomputes every node's position from
  the captured starting positions each frame — no accumulated
  drift.
- `cancelActiveModal()` grows a new branch for `Scaling` that
  restores `scaleStartPositions`. `<Esc>` already routes through
  here via `impl.unselectAll` (GraphKeyListener.kt:324).
- Bound key (say `s`) invokes
  `mouseListener.controller.startOrEndScale()` through
  `CommandRegistry`, same wiring as grab
  (GraphKeyListener.kt:111).

### Reference point and scale factor

Bbox centre of the current selection, captured once on entry
(matches rotate / mirror, GraphTools.computeBoundingBox).

Scale factor per axis from the cursor:

- `dx0, dy0 = cursorStart - centre`
- `dx, dy  = cursorNow  - centre`
- `sx = dx / dx0` (if not axis-locked and |dx0| above epsilon)
- `sy = dy / dy0` (same)
- `n.position = centre + ((startPos − centre) scaled by (sx, sy))`

This is the Blender convention. Distance ratio to bbox centre,
not absolute cursor delta, so the gesture "feels" scale-invariant
across zoom levels.

Degenerate case: `|dx0|` or `|dy0|` near zero (cursor sits on the
centre line when the gesture begins). Two options — pick one:

1. Fall back to pixel-linear mapping on that axis (e.g.
   `sx = 1 + (dx − dx0) / K` for some constant K).
2. Refuse to activate until the cursor is at least N pixels from
   the centre, otherwise nothing happens and the mode stays
   armed.

### Axis lock and the "transform mode"

Lock state is a pair of booleans on the controller:
`lockX: Boolean`, `lockY: Boolean`. Both start false (free 2D
scale). During the gesture:

- `lockX = true` → `sx` is forced to `1.0`.
- `lockY = true` → `sy` is forced to `1.0`.
- User pressing `x` toggles `lockY` (Blender semantic: "constrain
  to X" means Y is locked).
- User pressing `y` toggles `lockX` (constrain to Y).

Locks reset on commit/cancel.

To route `x`/`y` differently while the mode is active we need
*some* mode-aware dispatch in `KeyMapper`. Today the mapper is
entirely global (KeyMapper.kt, `feedKey`) — defaults and user
mappings share one namespace. Proposal:

- Add `var mode: Mode = Mode.Normal` on `KeyMapper` with at least
  `Mode.Normal` and `Mode.Transform`.
- Add a second mapping table `modeBindings: Map<Mode, Map<String,
  String>>` consulted *before* `mappings` and `defaults`.
- `startOrEndScale()` sets `mode = Mode.Transform` on entry and
  restores `mode = Mode.Normal` on commit / cancel. Same hook
  would later apply to grab and rotate if we want axis-locked
  variants of those too — which is why the mode is called
  "Transform" rather than "Scale".
- Bindings registered into `Mode.Transform`:
  - `x` → `toggle-axis-lock-y` (yes, `x`-key locks Y, per above)
  - `y` → `toggle-axis-lock-x`
  - `<Esc>` → cancel (can still fall through to the global
    binding that resolves to `cancelActiveModal`)

Letters not bound in the mode fall through to the normal mapper,
so the user can still (say) press `u` mid-gesture if we decide we
want that. Whether falling through is desirable is an open
question — Blender *swallows* everything except a small allowlist.

### Cursor tracking

Same source as rotate: `GraphView.lastCursorPosition` is updated
on every `mouseMoved` / `mouseDragged` by `GraphMouseListener`.
`dragScale` is called from the same dispatch points, so no new
plumbing is required.

### Undo

One `MoveNodesCommand(g, before, after)` on commit, via
`history.commitWithoutRun`. The UI has already moved nodes during
the drag; the command is only added to the undo stack, not
re-executed. Cancel (`<Esc>`) restores `scaleStartPositions`
directly and writes *no* history entry — matching rotate and
grab.

## Minimum-viable scope

1. `States.Scaling`, `scaleStartPositions`, `scaleCenter`,
   `scaleStartCursor` on `GraphMouseController`.
2. `startOrEndScale()`, `captureScaleStart()`,
   `commitScaleIfAny()`, `dragScale(pos)`, and a new branch in
   `cancelActiveModal()`.
3. `KeyMapper.mode` + `modeBindings` + lookup order in
   `feedKey`.
4. Register `scale`, `toggle-axis-lock-x`, `toggle-axis-lock-y`
   commands in `CommandRegistry`; add the default binding
   (`s` in `Mode.Normal`, and `x`/`y` in `Mode.Transform`).
5. Tests mirroring `MirrorRotateTest`: free scale, axis-locked
   scale, cancel reverts, undo/redo round-trip.

## Open questions

1. **Default key for scale.** `s` is nice and Blender-like, but
   currently unbound — does that collide with anything in the
   user's muscle memory for this editor? If `s` is reserved for
   something else, `S` or `<C-s>` as fallback.

2. **Axis-lock semantics.** Blender: `x` key means "lock to X
   axis" (Y becomes 1.0). That's what the spec assumes. Worth
   confirming — some users read `x` as "lock *out* the X axis".
   The spec's current wording matches Blender.

3. **Starting-distance degeneracy.** Ratio formula vs. pixel
   delta fallback (see "Scale factor" above).

4. **Cross-mode reuse.** Should `grab` and `rotate` also flip
   `KeyMapper.mode` to `Transform`, so `x`/`y` axis locks apply
   uniformly across all three transforms (again, Blender-like)?
   Cheap to add if yes; easy to defer if no.

5. **Key fall-through while in Transform mode.** Pass unbound
   keys through to normal dispatch, or swallow them entirely
   until the gesture ends? Swallowing is safer (no accidental
   `u` mid-gesture), but makes the mode feel heavier.

6. **Uniform-scale shortcut.** Blender has `shift+x` / `shift+y`
   to *exclude* an axis (scale in the plane minus X). Overkill
   for a 2D editor — mention and discard.

7. **Visual feedback.** Status bar text `-- SCALE (Y) --` (with
   axis-lock letter in parens) so the user knows the gesture is
   live. Not strictly required for the first cut; punt to a
   follow-up.
