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
    /**
     * Colour an edge fades to once its on-screen length reaches
     * the longer canvas dimension. Edges shorter than half that
     * dimension render in [defaultFgColor]; lengths in between
     * interpolate linearly. Picked to stay readable without
     * obscuring the graph it crosses.
     */
    val edgeFadedColor = Color(180, 180, 180)

    val helpCommands = """
        Graph manipulation
        ==================

        Mouse
          Double-click empty  - new node
          Double-click node   - edit text
          Drag a node         - move the selection
          Drag empty space    - pan view
          Mouse wheel         - zoom (centred on cursor)
          Right-click         - colour / shape / size picker

        Keys
          v/V + mouseover - new node + edge from selection (fwd/bwd)
          e/E + mouseover - connect selection to node under cursor (fwd/bwd)
          a/A             - append new node + edge from last active (fwd/bwd)
          c               - clear edges (within or incident to selection)
          d               - delete selected nodes
          D               - delete + reconnect predecessors to successors
          o/O             - layout optimiser (free / restricted)
          - / =           - optimize + shrink / grow spring target
                            distance (session-wide multiplier)
          g               - toggle grab-and-move

        Style
          F - copy style from selected node
          f - paste style onto selected nodes

        History
          u / Ctrl+Z       - undo
          U / Ctrl+Shift+Z - redo (Ctrl+R also works)
    """.trimIndent()

    val helpSelection = """
        Selection & navigation
        ======================

        Mouse
          Click node        - select (replaces current selection)
          Ctrl + click node - toggle in / out of multi-selection
          Ctrl + drag empty - rubber-band selection box

        Keys
          Ctrl+A      - select all / deselect all (toggle)
          Escape      - clear selection
          t/T         - add forward/backward reachable closure
          l/L         - add one generation of neighbours
          w/W         - remove the "oldest" generation of selection
          Space       - edit text of last active node
          Shift+Space - replace selection with last active, then edit

        Filtering
          i - invert selection (among visible nodes)
          h - hide the selected nodes
          H - unhide one level (undo the last h)
              (use 'ih' = invert then hide to keep only selection)

        Marks
          ma..mz - save selection under mark a..z (session only)
          mA..mZ - save selection under mark A..Z (persisted to file)
          'a..'z - recall mark a..z
          'A..'Z - recall mark A..Z

        View
          0 - fit view to all visible (or to selection)
          1 - centre view on selection
    """.trimIndent()

    val helpAttribution = """
        Created by Karel Tuček.

        Code is freely available at
        github.com/kareltucek/sane-graph-edit
    """.trimIndent()

    val helpFile = """
        Files, tabs & clipboard
        =======================

        Files
          Ctrl+O       - open
          Ctrl+S       - save
          Ctrl+Shift+S - save as
          Ctrl+E       - export SVG
          Ctrl+Shift+E - export selection as SVG

        Tabs
          Ctrl+N / Ctrl+T           - new tab
          Ctrl+W                    - close tab (prompt if dirty)
          Ctrl+Tab / Ctrl+Shift+Tab - next / previous tab
          Ctrl+PgDn / Ctrl+PgUp     - next / previous tab (alias)

        Clipboard
          Ctrl+C / Ctrl+X / Ctrl+V  - copy / cut / paste
                                      (works across tabs)
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