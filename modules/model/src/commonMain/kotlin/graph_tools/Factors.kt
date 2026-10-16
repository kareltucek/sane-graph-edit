package graph_tools

/** The numbers that shape a layout ([docs/developer/springs.md]), to be tuned by hand. */
object Factors {
    // distances: all in times the reach of the two nodes (middle to outline), times [LayoutOptimizer.springScale]

    /** The collision spring keeps nodes this many times as far apart as they touch (never less than touching). */
    const val COLLISION = 2.0

    /** How much longer an edge is for each edge of its end with fewer: in the plane, and in space (3D mode). */
    const val PER_DEGREE = 0.2
    const val PER_DEGREE_IN_SPACE = 0.2

    /** In space nodes in front hide those behind: they are kept this many times further apart, by collisions and by edges. */
    const val SPARSER = 3.0

    // systems ([Multilevel])

    /** A system wants this many times the room of its members together; on each level again. */
    const val PACKING = 1.5

    /** Two systems no path of two edges leads between keep this many times as far apart as their rooms reach. */
    const val SPARSITY = 3.0

    /** Levels are made until one has no more nodes than this. */
    const val SMALL = 50

    /** A level that keeps more than this part of the nodes of the one below is not made: the graph does not contract. */
    const val SHRINKS = 0.8

    // steps ([Settling])

    /** How much of what pulls a node counts while something pushes it. */
    const val PULL_WHEN_PUSHED = 0.3

    /**
     * Adaptive: after this many rounds in a row in which the springs asked for less than in the one before, the part
     * moved grows by 1 / [ADAPT]; after one in which they asked for more (the nodes swing), it shrinks by [ADAPT]
     * (after Hu, 2005).
     */
    const val ADAPT = 0.9
    const val ADAPT_AFTER = 5

    /** Adaptive, a step moves at most all of what the springs ask. */
    const val MOST = 1.0

    /** Nothing moved further than this in a step: the nodes rest. */
    const val SETTLED = 0.5
}
