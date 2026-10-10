package utils

object Constants {
    val buttonGray = Rgb(220, 220, 220)
    val defaultFontSize = 12.0
    val fontSizeZoomCoef = 1.3
    val zoomOutStep = 0.95
    val stylePickerDimensions = Vector2(500, 300)
    val wantColorSampler = false
    val maxZoom = 50.0

    /** How far the viewer is from the plane a view speaks of, in screen pixels: what is that far behind the plane appears half its size. */
    val focalLength = 1000.0

    /** What is so near the viewer that it would appear more than this many times its size is not shown. */
    val maxPerspective = 50.0

    val gravitySpringStrength = 100.0
    val maxOptimizerMove = 10000.0
    val minOptimizeMove = 5.0

    val optimizeAt = 50.0


    val doubletapTimeout = 500
    val cursorDeadZone = 5
    val defaultUIScale = 2.0
    val arrowLength = 10.0
    val arrowheadRadius = 4.0

    val defaultNodeText = "New node"

    // make the node name edit field a bit bigger than the actual text
//    val nodeEditorXMargin = 4.0
//    val nodeEditorYMargin = 2.0
//    val textRenderYOffset = 10
    //or the same for text field
    val nodeEditorXMargin = 8.0
    val nodeEditorYMargin = 8.0
    val textRenderYOffset = 11.5 // magic constant :-/

    // global margin between window edges and drawing area
    val frameMargin = 0


    //some colors
    val selectedColor = Rgb(100, 150, 50)
    val defaultFgColor = Rgb(0, 0, 0)
    val defaultBgColor = Rgb(255, 255, 255)
    /**
     * Brightness level (0..255) that a fully-faded edge renders
     * at. Fade begins when the edge's on-screen length is a
     * quarter of the shorter canvas dimension and reaches this
     * brightness at the longer dimension. Picked to stay readable
     * without obscuring the graph the edge crosses.
     */
    val edgeFadedMaxBrightness: Int = 220

    val helpAttribution = """
        Created by Karel Tuček.

        Code is freely available at
        github.com/kareltucek/sane-graph-edit
    """.trimIndent()

    val helpTodo = """
        Todo
        ====
        - menu bar
        - port to compose multiplatform
        - markdown editing in nodes
        - cfg parser
        - styles (y,p)
        - folding?
        - highlight related to selection (s?)
    """.trimIndent()
}