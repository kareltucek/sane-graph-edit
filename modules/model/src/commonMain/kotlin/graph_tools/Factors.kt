package graph_tools

/**
 * The numbers that shape a layout ([docs/developer/springs.md]), to be tuned by hand.
 *
 * In the formulas:
 *  - `r_a`, `r_b`: how far nodes a and b reach from their middles to their outlines, each the way the other lies, in
 *    workspace units (pixels at zoom 1); a system's stand-in (levels ≥ 1) reaches R / max(COLLISION × s, 1)
 *  - `R_a`, `R_b`: the radius of a system's room (see systems below), in workspace units
 *  - `s`: [LayoutOptimizer.springScale], set with `:set spring-scale=<n>` (1.0)
 *  - `d`: the larger degree (count of edges) of the two ends of an edge
 *  - `× 3D`: times [SPARSER] in 3D mode, times 1 in the plane
 */
object Factors {
    // ---- which springs act on which levels of `o` on the whole graph ([Multilevel]) --------------------------------
    //
    //   level 0: the nodes; 1 and up: the systems; ALL: every level. `o` on a selection is not by levels: it has all of
    //   them but cohesion and separation by relation (collision only).

    val ALL = 0..Int.MAX_VALUE

    /** The edge spring. Not on systems: a path of them does not grow with its weight, and the graph shrank into a heap. */
    val EDGE_LEVELS = 0..0

    /** The rope: an edge pulls its ends back to no further apart than the path between them laid straight. Levels ≥ 1 only. */
    val ROPE_LEVELS = 1..Int.MAX_VALUE

    /** The equal siblings spring:the edges out of one parent as long as each other. Not on systems, as the edge spring. */
    val SIBLINGS_LEVELS = 0..0

    /** The root spring: a root's children go so that it is in their middle. */
    val ROOT_LEVELS = ALL

    /** Antigravity: a child pushed on along the way its parent came (only of parents with fewer than 3 children). */
    val ANTIGRAVITY_LEVELS = ALL

    /** The collision spring: never closer than touching, factor 1 below. */
    val COLLISION_LEVELS = 0..0

    /** The separation spring by relation (the factors below). */
    val SEPARATION_LEVELS = ALL

    /** The cohesion spring (the top level has no system above to be held to). */
    val COHESION_LEVELS = ALL

    // ---- edge spring: pulls the two ends of an edge to a distance, and pushes them out to it -----------------------
    //
    //   desiredDistance = (r_a + r_b) × s × degFactor(d) × 3D          (workspace units)
    //   degFactor(d)    = 1 + PER_DEGREE × (d > 2 ? d + 1 : d)          (a plain number)

    /** How much longer an edge is for each edge of its busier end, in the plane: a part of (r_a + r_b) × s. */
    const val PER_DEGREE = 0.2

    /** The same in 3D mode. */
    const val PER_DEGREE_IN_SPACE = 0.1

    // ---- rope (systems): pulls the suns of an edge's two systems back to its length, never pushes ---------------------
    //
    //   length = min over the edges x–y below, x in A, y in B: d_A(sun_A, x) + length(x–y) + d_B(y, sun_B)  (workspace units)
    //   d_A    = the shortest path from A's sun to its member, among A's members only, on the level below
    //   length(x–y) = level 0: the edge spring's desiredDistance (r from the node's area, the way it lies left out);
    //                 above: the rope of x–y
    //   pull   = (|sun_B - sun_A| - length) / 2 towards the other, while longer; balanced as an edge (see balance)

    // ---- root spring: a root's children around it ---------------------------------------------------------------------
    //
    //   root   = a node (system) with at least 3 children and no parent, among those shown
    //   v      = L × mean over children c of unit(c - root)   L: the shortest of its edges   (workspace units)
    //   the root moves  v × ROOT × M / (m_root + M), each child -v × ROOT × m_root / (m_root + M)
    //   M: the children's masses together; the middle of the masses stays; of two roots, both add up

    /** What part of the way from the children's middle to the root they go, per window ([LayoutOptimizer.WINDOW_MS]). */
    const val ROOT = 0.1

    // ---- collision and separation springs: push two that are too close ---------------------------------------------
    //
    //   nodes (level 0):      desiredDistance = (r_a + r_b) × max(COLLISION × s, 1) × factor × 3D   (workspace units)
    //   systems (levels ≥ 1): desiredDistance = (R_a + R_b) × factor                                (workspace units)
    //   factor = max(1 where the collision spring acts, the separation factor where that acts); 0: not pushed
    //   separation factor = by how a and b are joined, through paths of 1 or 2 edges:
    //     related:   a directed path leads from either to the other (parent, grandparent)  → SEPARATION_RELATED
    //     kin:       only a path that ignores direction does (shared parent, shared child) → SEPARATION_KIN
    //     unrelated: none                                                                  → SEPARATION_UNRELATED

    /** How far apart nodes keep: in times (r_a + r_b), the distance at which they touch (at least 1: touching). */
    const val COLLISION = 2.0

    /** How far apart related ones keep: in times their collision distance (nodes) or (R_a + R_b) (systems). */
    const val SEPARATION_RELATED = 1.0

    /** How far apart kin keep: in times their collision distance (nodes) or (R_a + R_b) (systems). */
    const val SEPARATION_KIN = 1.2

    /** How far apart unrelated ones keep: in times their collision distance (nodes) or (R_a + R_b) (systems). */
    const val SEPARATION_UNRELATED = 1.5

    // ---- cohesion spring: pulls a node (or system) back into its system, one level up --------------------------------
    //
    //   pull = (|sun - n| - R) × COHESION towards the sun, while |sun - n| > R; else none     (workspace units)
    //   sun, R = the sun and room radius of the system n is in, one level up
    //   n moves pull × m_sun / (m_n + m_sun); the sun the other way, pull × m_n / (m_n + m_sun) (m: room)
    //   not lessened while n is pushed (unlike edges: PULL_WHEN_PUSHED)

    /** What part of the way back into its system's room a node is pulled, per window ([LayoutOptimizer.WINDOW_MS]). */
    const val COHESION = 0.0

    // ---- 3D mode: applies to the edge, collision and separation springs --------------------------------------------

    /** In space nodes in front hide those behind: all their distances are this many times longer. */
    const val SPARSER = 2.0

    // ---- systems ([Multilevel]): their size, and how many levels ----------------------------------------------------
    //
    //   node:   ρ    = reach × max(max(COLLISION × s, 1), s × degFactor(own degree)) × 3D   (workspace units)
    //           reach = √(w/2 × h/2); in 3D ∛(w/2 × h/2 × THICKNESS × min(w/2, h/2)), w, h the node's size
    //           room  = π ρ²; in 3D 4/3 π ρ³ (at least 1)                                       (square / cubic units)
    //   system: room  = PACKING × Σ rooms of its members
    //           R     = √(room / π); in 3D ∛(3 room / 4π)                                       (workspace units)
    //   a system sits on its sun; the room is also the weight (see balance)

    /** A system wants this many times the room (area; in 3D volume) of its members together; on each level again. */
    const val PACKING = 2.0

    /** Levels are made until one has no more systems than this. */
    const val SMALL = 10

    /** A level is not made when it keeps more than this part of the systems of the one below: the graph does not contract. */
    const val SHRINKS = 0.8

    // ---- balance (`o` on the whole graph): how the pushes and pulls on one node add up ------------------------------
    //
    //   what two do to each other is shared by their weights (rooms): each takes the other's part, m_o / (m_n + m_o),
    //   so the heavier moves less and the weighted middle of the graph stays where it is
    //   move   = part × (mean(pushes) + Σ over edges e: pull_e × (either end pushed ? PULL_WHEN_PUSHED : 1) / load)
    //   pull_e = the edge spring's way × 2 × m_o / (m_n + m_o) × count_e   (count_e: real edges e stands for; 1 on level 0)
    //   load   = the larger of the two ends' Σ count over their edges, the same from both ends

    /** How much of what pulls a node counts while something pushes it: a part of the pull. */
    const val PULL_WHEN_PUSHED = 0.6

    // ---- step size (`o`): each node's `part` of what the springs ask its step moves --------------------------------
    //
    //   part starts at min(0.6 (Settling's DAMPING), MOST)
    //   turn = cos of the angle between the node's step and its step before
    //   part ← part × ADAPT            when turn < 0       (it turned back: it swings)
    //   part ← min(part / ADAPT, MOST) when turn > STEADY  (it keeps its way)
    //   `o` goes on while held, however little the nodes move

    /** By how much a node's part shrinks when it turns back, and grows (1 / ADAPT) while it keeps its way; 1: never. */
    const val ADAPT = 1.0

    /** A node keeps its way while the cos of the angle between its step and the one before is more than this (0.7: it turns by less than 45°). */
    const val STEADY = 0.7

    /** A step moves at most this part of what the springs ask. */
    const val MOST = 0.3

    /**
     * How fast a level of `o` on the whole graph settles: its steps, all its springs together, times this (a plain number).
     * The higher slower: the lower ones settle under them. Not just slower: at rest the levels' moves add up to none, so a
     * level's pace is also its say in the shape.
     */
    val LEVEL_PACE: (Int) -> Double = { level -> 1.0 / (level + 1) }

    /** Not `o` (Shift-drag, placing): the nodes rest when none moved further than this in a round, in workspace units. */
    const val SETTLED = 0.5
}
