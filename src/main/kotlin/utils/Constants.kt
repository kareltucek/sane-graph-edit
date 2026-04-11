package utils

import java.awt.Color

object Constants {
    val buttonGray = Color(220, 220, 220)
    val fontSizeZoomCoef = 1.3
    val zoomOutStep = 0.95
    val stylePickerDimensions = Vector2(500, 300)
    val wantColorSampler = false
    val maxZoom = 50.0

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

    const val `😍` = "😍"

    // global margin between window edges and drawing area
    val frameMargin = 0


    //some colors
    val selectedColor = Color(100, 150, 50)
    val defaultFgColor = Color(0, 0, 0)
    val defaultBgColor = Color(255, 255, 255)

    val helpCommands = """
            Graph manipulation
            ==================
            v/V + mouseover - create new vertex under cursor and connect it to current selection 
            e/E + mouseover - connect selected vertex(ices) to vertex under cursor
            c - clear edges
            d - delete selected nodes
            Doubleclick - create new vertex and edit it straight away, or edit vertex under cursor
            Ctrl + click - multiselect 
            Drag - move vertex, or pan view
        """.trimIndent()

    val helpAttribution = """
            Created by Karel Tuček. 
            
            Code is freely available at github.com/kareltucek/saneGraphEdit.
        """.trimIndent()

    val helpFile = """
        File manipulation
        =================
        Ctrl+o
        Ctrl+s
    """.trimIndent()

    val helpTodo = """
        Todo
        ====
        - cfg parser
        - copy paste (ctrl+cv)
        - styles (y,p)
        - folding?
        - layouting?
        - highlight related to selection (s?)
    """.trimIndent()
}