import java.awt.*
import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import javax.swing.*


object Clicker {
    fun Vector2.isInSquare(lt: Vector2, br: Vector2, tolerance: Float): Boolean {
        val xMatches = lt.x - tolerance < this.x && this.x < br.x + tolerance
        val yMatches = lt.y - tolerance < this.y && this.y < br.y + tolerance
        return xMatches && yMatches
    }

    fun selectClickedNode(g: Graph, clickCoordinates: Vector2): MutableSet<Node> {
        return g.nodes
            .minByOrNull { (it.position - clickCoordinates).distanceSquare() }
            ?.let {
                it.cachedBounds?.shapeBounds?.let { bounds ->
                    if (clickCoordinates.isInSquare(
                            it.position - bounds / 2,
                            it.position + bounds / 2,
                            10.0f / Plotter.t.scaleX.toFloat()
                        )
                    ) {
                        mutableSetOf(it)
                    } else {
                        null
                    }
                }
            }
            ?: mutableSetOf()
    }
}

object Utils {
    fun MouseEvent.toWorkspaceVector(): Vector2 {
        var pt = Point2D.Double(this.x.toDouble() + Constants.frameMargin, this.y.toDouble() + Constants.frameMargin)
        var res = Plotter.t.inverseTransform(pt, null)
        return Vector2(res.x.toFloat(), res.y.toFloat())
    }

    fun Vector2.toScreenVector(): Vector2 {
        var pt = Point2D.Double(this.x.toDouble(), this.y.toDouble())
        var res = Plotter.t.transform(pt, null)
        return Vector2(res.x.toFloat() - Constants.frameMargin, res.y.toFloat() - Constants.frameMargin)
    }

    fun Vector2.workspaceSizeTransform(): Vector2 {
        return this * Plotter.t.scaleX.toFloat()
    }

    fun <T> T?.orElse(t: T): T = this ?: t
}

class ShapesEx(title: String) : JFrame() {
    val pnl = JLayeredPane()
    val lyt = SpringLayout()
    val te = NodeEditor(this, lyt, pnl)
    val sf = GraphComponent(this, te)

    init {
        createUI(title)
    }

    fun setTextFieldVisible(visible: Boolean) {
        te.isVisible = visible
    }

    fun createUI(title: String) {
        te.text = "abcd"
        te.isVisible = false

        pnl.layout = lyt
        pnl.add(sf, 1)
        pnl.add(te, 2)

        lyt.putConstraint(SpringLayout.WEST, sf, Constants.frameMargin, SpringLayout.WEST, pnl);
        lyt.putConstraint(SpringLayout.NORTH, sf, Constants.frameMargin, SpringLayout.NORTH, pnl);
        lyt.putConstraint(SpringLayout.EAST, sf, -Constants.frameMargin, SpringLayout.EAST, pnl);
        lyt.putConstraint(SpringLayout.SOUTH, sf, -Constants.frameMargin, SpringLayout.SOUTH, pnl);

        pnl.setLayer(sf, 0)
        pnl.setLayer(te, 1)

        add(pnl)


        setTitle(title)

        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(400, 350)
        setLocationRelativeTo(null)
        pack()

        validate()
        repaint()
    }
}

fun createAndShowGUI() {

    val frame = ShapesEx("Shapes")
    frame.isVisible = true
}

fun main(args: Array<String>) {
    println("Program arguments: ${args.joinToString()}")
    EventQueue.invokeLater(::createAndShowGUI)
}