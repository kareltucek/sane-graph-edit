package client

import utils.Vector2

/** A renderer that shows nothing: the client's editing needs none to run. */
object NoRenderer : Renderer {
    override fun repaint() {}
    override fun refreshStatus() {}
    override fun startNodeEdit(nodeId: String, screenClick: Vector2?) {}
    override fun endNodeEdit() {}
    override fun startStylePicker(screen: Vector2?) {}
    override fun endStylePicker() {}
}
