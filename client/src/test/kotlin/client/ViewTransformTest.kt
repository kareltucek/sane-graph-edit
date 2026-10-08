package client

import utils.Vector2
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ViewTransform] took the place of an [AffineTransform]; it has to pan, zoom and persist the same way. */
class ViewTransformTest {
    private fun assertSame(awt: AffineTransform, view: ViewTransform) {
        val m = DoubleArray(6).also { awt.getMatrix(it) }
        m.zip(view.matrix).forEach { (a, b) -> assertEquals(a, b, 1e-9) }
        val p = Vector2(12.5, -40.0)
        val onScreen = awt.transform(Point2D.Double(p.x, p.y), null)
        assertEquals(onScreen.x, view.toScreen(p).x, 1e-9)
        assertEquals(onScreen.y, view.toScreen(p).y, 1e-9)
        val back = awt.inverseTransform(Point2D.Double(p.x, p.y), null)
        assertEquals(back.x, view.toWorkspace(p).x, 1e-9)
        assertEquals(back.y, view.toWorkspace(p).y, 1e-9)
    }

    @Test
    fun `a fresh view is the identity`() {
        assertTrue(ViewTransform().isIdentity)
        assertSame(AffineTransform(), ViewTransform())
    }

    @Test
    fun `panning and zooming match the affine transform, in any order`() {
        val random = Random(7)
        val awt = AffineTransform()
        val view = ViewTransform()
        repeat(200) {
            if (random.nextBoolean()) {
                val by = Vector2(random.nextDouble(-300.0, 300.0), random.nextDouble(-300.0, 300.0))
                awt.translate(by.x, by.y)
                view.translate(by)
            } else {
                val factor = random.nextDouble(0.8, 1.25)
                awt.scale(factor, factor)
                view.zoom(factor)
            }
            assertSame(awt, view)
        }
    }

    @Test
    fun `setToScale drops the pan`() {
        val awt = AffineTransform().also { it.translate(5.0, 6.0) }
        val view = ViewTransform().also { it.translate(Vector2(5.0, 6.0)) }
        awt.setToScale(3.0, 3.0)
        view.setToScale(3.0)
        assertSame(awt, view)
    }

    @Test
    fun `the matrix restores the view`() {
        val view = ViewTransform().also {
            it.zoom(2.5)
            it.translate(Vector2(-30.0, 80.0))
        }
        val restored = ViewTransform().also { it.matrix = view.matrix }
        assertEquals(view.matrix, restored.matrix)
        assertEquals(view.toScreen(Vector2(1.0, 2.0)), restored.toScreen(Vector2(1.0, 2.0)))
    }
}
