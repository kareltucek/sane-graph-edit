# Spring-length scale (`-` / `=`)

> **Status: designed.** Ready to implement.

## Goal

Let the user dial in how spread-out the layout optimiser should
make the graph. A session-wide scalar controls the target edge
length used by the BB spring. Two unshifted keys — `-` to shrink,
`=` to grow — adjust the scalar by a small multiplicative step
AND run one optimize pass with the new value. The scalar
persists across further `o` calls on other subgraphs, so once
you've tuned the spacing on one piece of the graph, the rest
lays out at the same density.

## Behaviour

### Key bindings (new defaults)

| Key | Action              |
|-----|---------------------|
| `-` | `spring-shorter`    |
| `=` | `spring-longer`     |

Both currently unbound (verified via `:help keys`).

### `spring-shorter` / `spring-longer`

Both take a "per-second rate factor" — `0.9` for shorter,
`1/0.9 ≈ 1.111` for longer — and apply it as an **exponentially-
rate-limited** multiplier so held keys and single taps both
feel right regardless of the OS key-repeat interval.

Per call:
1. Compute `elapsed = now - lastTweakTime`, clamped to at most
   200 ms. (First call and long-gap calls → 200 ms.)
2. Multiply `springScale` by `perSecFactor^(elapsed / 1000)`.
3. Record `lastTweakTime = now`.
4. Run one `optimize` pass.

Consequences:
- A **single tap** after any pause applies 200 ms-worth of
  scaling. For `perSecFactor=0.9`: `0.9^0.2 ≈ 0.979` — about
  a 2% step, small enough that one accidental tap doesn't
  destroy the layout.
- A **held key** at N taps per second applies `1000/N` ms per
  tap. The product over one second is `perSecFactor^1.0 = 0.9`
  — a 10% shrink per second of holding, regardless of the
  repeat rate. Fast repeats produce many tiny steps, slow
  repeats produce fewer larger ones, both total the same.
- The scale persists across further `o`, `-`, `=` keys and
  across tab switches. Future `o` uses the dialled-in scale.

### `:set spring-scale=<n>`

Direct control. Typing `:set spring-scale=1` resets the scale to
default. No implicit optimize pass — this just updates the
scalar. The next time you press `o` or `-`/`=`, the value is
used.

## Storage

Session-only. The scalar lives as a `var` on `LayoutOptimizer`
(or a small singleton) — it does NOT get serialised to DOT.
Survives tab switches within a running editor. Resets to `1.0`
on app restart.

Rationale for not persisting:
- Spring scale is a workflow preference ("I like things spread
  out today"), not a property of the graph itself.
- Serialising it to DOT would force layout tuning into the file
  format, which already has `pos` attributes that capture the
  actual final positions.

## Where the scale enters the math

`LayoutOptimizer.impl.computeBBSpring` has this line:

```kotlin
val f = degFactor(g, node, othr) // degree factor
val d = 1.0 // distance factor
val desiredRelativeLocation = - (c1 + c2) * d * f
```

The `d = 1.0` becomes `d = LayoutOptimizer.springScale`. That's
the one-line change — the BB spring's target distance scales
uniformly.

**Not touched**: collision spring (`distanceCf = 2.0` in
`computeCollisionSpring`). Collision prevents overlap — an
absolute minimum distance regardless of desired spring length.
With a very small `springScale` the spring wants nodes close,
but collision keeps them from overlapping. Good.

## Selection behaviour

Same as the current `o` / `O` (post the anchor fix):
- **No selection** → optimise everything with the new scale.
- **Non-empty selection** → optimise only the selected nodes
  with the new scale. Cross-boundary edges still pull the
  selected endpoint toward the unselected one at the new
  target length.

The **global scale** still gets updated either way. Even if you
only optimise a subset with `-`, the next `o` on the rest of
the graph uses the same scale.

## Feedback

Silent. No status-line indicator in v1. If that turns out to be
confusing we can surface the current scale in the `:help init`
output and potentially add a small text hint somewhere.

## Touch points

- `graph_tools/LayoutOptimizer.kt`:
  - New `var springScale: Double = 1.0` on the object.
  - `computeBBSpring` uses it in the `d` variable.
- `ui/CommandRegistry.kt`: register `spring-shorter`,
  `spring-longer`, both calling a new
  `GraphKeyListener.impl.tweakSpringScale(graphView, factor)`
  helper that multiplies and then calls `optimize`.
- `ui/GraphKeyListener.kt` (`impl`): add the helper.
- `ui/KeyMapper.kt`:
  - Default bindings: `-` → `spring-shorter`, `=` → `spring-longer`.
  - Extend `applySetting` to handle `spring-scale=<n>`.
- `utils/Constants.kt` `helpCommands`: document the new keys.

## Testing

- `LayoutOptimizer.springScale` defaults to `1.0` (covered
  implicitly by existing tests, which assume baseline behaviour).
- `tweakSpringScale(factor)` multiplies the global and runs
  optimize — verify the scalar changed and that at least one
  node moved when the graph has something to relax.
- `:set spring-scale=<n>` sets the value without running
  optimize — verify the scalar updated and no position changed.
- Independence across calls: start with a 3-node graph, press
  `-` once (optimises part A), press `o` on part B — verify
  part B's BB spring used the reduced scale (can be checked by
  reading `LayoutOptimizer.springScale` directly after the
  operations).

## Open questions (none pressing)

- Should the status line / message area show the current scale
  when it's not 1.0? Deferred.
- Per-edge scales (different subgraphs at different densities):
  follow-up if anyone asks for it. The global scale is the
  correct v1.
