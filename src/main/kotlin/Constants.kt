import java.awt.Color

object Constants {
    val optimizeAt = 30

    val doubletapTimeout = 500
    val shapeSizeCf = 1.3f
    val shapeSizeMargin = 15.0f
    val cursorDeadZone = 5
    val defaultUIScale = 2.0f
    val arrowLength = 10.0f
    val connectionDotRadius = 4

    val defaultNodeText = "New node"

    // make the node name edit field a bit bigger than the actual text
//    val nodeEditorXMargin = 4.0f
//    val nodeEditorYMargin = 2.0f
//    val textRenderYOffset = 10
    //or the same for text field
    val nodeEditorXMargin = 8.0f
    val nodeEditorYMargin = 8.0f
    val textRenderYOffset = 11.5f // magic constant :-/

    const val `😍` = "😍"

    // global margin between window edges and drawing area
    val frameMargin = 0


    //some colors
    val selectedColor = Color(100, 150, 50)
    val defaultColor = Color(0, 0, 0)

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
        - undo / redo (ur)
        - cfg parser
        - copy paste (ctrl+cv)
        - styles (y,p)
        - folding?
        - layouting? 
        - highlight related to selection (s?)
    """.trimIndent()
}