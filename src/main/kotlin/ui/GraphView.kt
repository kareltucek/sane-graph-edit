package ui

import Graph
import graph_tools.GraphTools
import graph_tools.Node
import graph_tools.Plotter
import utils.Utils.orElse
import utils.Utils.toWorkspaceVector
import utils.Constants
import utils.Constants.stylePickerDimensions
import utils.Rectangle
import utils.Vector2
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLayeredPane
import javax.swing.SpringLayout

class GraphView() : JLayeredPane() {
    val graphCanvas = GraphCanvas(this)
    val nodeEditor = NodeEditor(this)
    val stylePicker = StylePicker(this)
    val springLayout = SpringLayout()
    val mouseListener = GraphMouseListener(this)
    val keyListener = GraphKeyListener(this)
    var lastCursorPosition: Vector2 = Vector2.Zero
    var optimizeOnDrag: Boolean = false
    var defaultNodeStyle: NodeStyle = NodeStyle()
    var g: Graph = Graph.testGraph()

    fun placeMeAt(me: JComponent, ul: Vector2, br: Vector2) {
        springLayout.putConstraint(
            SpringLayout.WEST,
            me,
            ul.x.toInt() + Constants.frameMargin,
            SpringLayout.WEST,
            this
        );
        springLayout.putConstraint(
            SpringLayout.NORTH,
            me,
            ul.y.toInt() + Constants.frameMargin,
            SpringLayout.NORTH,
            this
        );
        springLayout.putConstraint(
            SpringLayout.EAST,
            me,
            br.x.toInt() + Constants.frameMargin,
            SpringLayout.WEST,
            this
        );
        springLayout.putConstraint(
            SpringLayout.SOUTH,
            me,
            br.y.toInt() + Constants.frameMargin,
            SpringLayout.NORTH,
            this
        );
    }

    fun setTextFieldVisible(visible: Boolean) {
        nodeEditor.isVisible = visible
    }

    fun startNodeEdit(n: Node, screenClickCoordinates: Vector2?) {
        endNodeEdit()
        nodeEditor.startNodeEdit(n, screenClickCoordinates)
        nodeEditor.isVisible = true
        nodeEditor.requestFocus()
        parent.repaint()
    }

    fun endNodeEdit() {
        nodeEditor.endNodeEdit()
        nodeEditor.isVisible = false
        graphCanvas.requestFocus()
        parent.repaint()
    }

    fun startStylePicker(screenCoordinatesPosition: Vector2? = null) {
        val center = screenCoordinatesPosition.orElse(lastCursorPosition.toWorkspaceVector())
        val ul = center - stylePickerDimensions / 2
        val br = center + stylePickerDimensions / 2
        placeMeAt(stylePicker, ul, br)
        stylePicker.isVisible = true
        stylePicker.requestFocus()
        parent.repaint()
    }

    fun endStylePicker() {
        stylePicker.isVisible = false
        graphCanvas.requestFocus()
        parent.repaint()
    }

    fun saveFile() {
        DotGraphLoader.saveToFile(g, "dot.dot")
    }

    fun loadFile() {
        g = DotGraphLoader.loadFromFile("dot.dot")
        boundScreen()
    }

    init {
        nodeEditor.text = "abcd"

        this.layout = springLayout
        this.add(graphCanvas)
        this.add(nodeEditor)
        this.add(stylePicker)

        springLayout.putConstraint(SpringLayout.WEST, graphCanvas, Constants.frameMargin, SpringLayout.WEST, this);
        springLayout.putConstraint(SpringLayout.NORTH, graphCanvas, Constants.frameMargin, SpringLayout.NORTH, this);
        springLayout.putConstraint(SpringLayout.EAST, graphCanvas, -Constants.frameMargin, SpringLayout.EAST, this);
        springLayout.putConstraint(SpringLayout.SOUTH, graphCanvas, -Constants.frameMargin, SpringLayout.SOUTH, this);

        this.setLayer(graphCanvas, 0)
        this.setLayer(nodeEditor, 1)
        this.setLayer(stylePicker, 1)

        graphCanvas.addMouseListener(mouseListener)
        graphCanvas.addMouseMotionListener(mouseListener)
        graphCanvas.addMouseWheelListener(mouseListener)
        graphCanvas.addKeyListener(keyListener)
        nodeEditor.addMouseWheelListener(mouseListener)

        stylePicker.isVisible = false
        nodeEditor.isVisible = false
        graphCanvas.isVisible = true
        this.isVisible = true

        validate()
        repaint()
        graphCanvas.repaint()
    }

    fun setViewTo(pos: Vector2, scale: Double = 1.0) {
        Plotter.t.setToScale(scale, scale)
        val viewCenter = Vector2(this.width, this.height).let { it / 2 }.toWorkspaceVector()
        val diff = viewCenter - pos
        Plotter.t.translate(diff.x.toDouble(), diff.y.toDouble())
        repaint()
    }

    fun centerScreen() {
        val center =
            g.selectedNodes
                .takeIf { it.isNotEmpty() }
                .orElse(g.nodes)
                .let { GraphTools.computeCenterOfMass(it) }

        setViewTo(center, 1.0)
    }

    fun boundScreen() {
        val box =
            g.selectedNodes
                .takeIf { it.isNotEmpty() }
                .orElse(g.nodes)
                .let { GraphTools.computeBoundingBox(it) }

        box?.let { r ->
            val ul = r.ul
            val br = r.br

            val screenSize = Vector2(this.width, this.height)
            val size = br - ul
            val scale = screenSize / size
            val targetScale = Math.min(scale.x, scale.y)
                .let { it / 1.1 }
                .coerceIn(0.0001, 1.0)

            setViewTo((box.ul + box.br)/2, targetScale)
        }
    }

    public override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        graphCanvas.doDrawing(g)
    }

    public override fun repaint() {
        super.repaint()
    }
}


class Window(title: String) : JFrame() {
    val sh: GraphView = GraphView()

    init {
        createUI(title)
    }

    fun createUI(title: String) {
        setTitle(title)
        add(sh)

        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(400, 350)
        setLocationRelativeTo(null)
        pack()

        validate()
        repaint()
    }
}
