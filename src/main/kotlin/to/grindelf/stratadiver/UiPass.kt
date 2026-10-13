package to.grindelf.stratadiver

import org.joml.Matrix4f
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.GL_TEXTURE1
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glBindVertexArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Drawing in screen space: flat quads, one orthographic projection, no lighting.
 *
 * Every GUI element in the game goes through here — the hotbar, the inventory, the
 * crosshair, the FPS readout. Centralising it is what stops each of them from quietly
 * disagreeing about depth testing, projection and which Tile of the atlas they sample.
 * It was five copies of the same seven lines of setup before.
 */
class UiPass(private val shader: ShaderProgram, val width: Float, val height: Float) {

    private val quad = Geometry.upload(UNIT_QUAD, Geometry.POS_UV)!!
    private val square = Geometry.upload(UNIT_SQUARE, Geometry.POS_UV)!!

    private val stack: FloatBuffer =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Top-left origin, y growing downwards — screen coordinates, not NDC. */
    private val projection = Matrix4f().ortho(0f, width, height, 0f, -1f, 1f)
    private val identity = Matrix4f()

    /** Starts a GUI layer: screen-space projection, no depth test, unlit. */
    fun begin() {
        shader.use()
        glDisable(GL_DEPTH_TEST)
        shader.setMatrix("projection", projection)
        shader.setMatrix("view", identity)
        // isEntity is what makes the vertex shader offset the quad's 0..1 UVs into the
        // Tile the uniform names. GUI art is atlas art, so it needs the same path the
        // world uses.
        shader.setInt("isEntity", 1)
        shader.setInt("isGui", 1)
    }

    /** Ends a GUI layer and hands depth testing back to the 3D pass. */
    fun end() {
        shader.setInt("isGui", 0)
        glEnable(GL_DEPTH_TEST)
    }

    /** A rectangle of one flat Tile of the Atlas, [brightness] shading it uniformly. */
    fun tile(x: Float, y: Float, w: Float, h: Float, tile: TileRef, brightness: Float = 1f) {
        val model = Matrix4f().translate(x + w / 2f, y + h / 2f, 0f).scale(w, h, 1f)
        shader.setMatrix("model", model)
        shader.setVec2("uvOffset", tile.tx.toFloat(), tile.ty.toFloat())
        shader.setTint(brightness)
        glBindVertexArray(quad.vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)
    }

    /**
     * The isometric Icon for [type], assembled at draw time from three of its own Tiles —
     * top, north and east — each shaded so the three faces stay distinguishable. No atlas
     * space is spent per Block Type: every Block Type gets an Icon for free.
     */
    fun icon(x: Float, y: Float, size: Float, type: BlockType) {
        /**
         * Maps the unit quad onto the parallelogram p0-p1-?-p3, where the fourth corner is
         * p1 + p3 - p0. The unit quad's vertices are (-.5,-.5) (.5,-.5) (.5,.5) (-.5,.5),
         * so p0/p1/p3 are the corners opposite that final diagonal vertex.
         */
        fun face(
            p0x: Float, p0y: Float, p1x: Float, p1y: Float, p3x: Float, p3y: Float,
            tile: TileRef, brightness: Float
        ) {
            val a = p1x - p0x; val b = p1y - p0y
            val c = p3x - p0x; val d = p3y - p0y
            val e = (p1x + p3x) / 2f
            val f = (p1y + p3y) / 2f

            // JOML's set(column, row, value): x' = m00*x + m10*y + m30, y' = m01*x + m11*y + m31.
            // So the x-row (column 0) carries (a, b) and the y-row (column 1) carries (c, d).
            // Putting (c, d) in column 0 transposes every face and shears it flat.
            val model = Matrix4f()
            model.set(0, 0, a); model.set(0, 1, b)
            model.set(1, 0, c); model.set(1, 1, d)
            model.set(3, 0, e); model.set(3, 1, f)

            shader.setMatrix("model", model)
            shader.setVec2("uvOffset", tile.tx.toFloat(), tile.ty.toFloat())
            shader.setTint(brightness)
            glBindVertexArray(quad.vao)
            glDrawArrays(GL_TRIANGLES, 0, 6)
        }

        // Classic 2:1 isometric cube. The silhouette is deliberately taller than the old
        // 2:1 face proportions: a cube's Icon should read as a cube at 40px, so the two
        // side faces are given roughly half the height each rather than a third.
        val halfHeight = size / 4f
        val sideHeight = size * 0.55f
        val totalHeight = 2f * halfHeight + sideHeight
        val cx = x + size / 2f
        val cy = y + (size - totalHeight) / 2f + halfHeight // centre the silhouette in its box
        val left = cx - size / 2f
        val right = cx + size / 2f

        // Left (north) face: top-left -> top-right, hanging down.
        face(left, cy, cx, cy + halfHeight, left, cy + sideHeight, type.tile(Face.NORTH), 0.72f)
        // Right (east) face, mirrored.
        face(cx, cy + halfHeight, right, cy, cx, cy + halfHeight + sideHeight, type.tile(Face.EAST), 0.55f)
        // Top face, the diamond: bottom corner first so the fourth vertex is the apex.
        face(cx, cy + halfHeight, right, cy, left, cy, type.tile(Face.TOP), 1.0f)
    }

    // --- Shared by every GUI layer that draws after the 3D pass ---

    /** Solid white bar, the primitive both the selection box and the FPS font are built from. */
    fun line(x: Float, y: Float, w: Float, h: Float, brightness: Float = 1f) =
        tile(x, y, w, h, Atlas.LINE, brightness)

    /**
     * The Minimap: a screen-space square drawn with `isMinimap` on, so its 0..1 UVs reach
     * the fragment shader as map coordinates rather than atlas coordinates, and its corners
     * are discarded into a disc.
     *
     * The map's *contents* were decided on the CPU — a fragment shader cannot read the
     * world's Cells, which is exactly what the sine-wave minimap this replaced got wrong.
     * What happens here is only the per-pixel work: the round clip, the rim, and the
     * player marker, all of which are circle and distance problems that belong in a
     * shader.
     *
     * [origin] is the player's position in 0..1 map space and [facing] their heading in
     * the same space, where +y is downward — the texture's own orientation, so no flip is
     * needed.
     */
    fun minimap(
        x: Float, y: Float, diameter: Float,
        texture: Int,
        originX: Float, originY: Float,
        facingX: Float, facingY: Float
    ) {
        // The map reads a texture of its own on unit 1. Unit 0 keeps the Atlas, which the
        // world pass bound and every HUD quad after this still depends on.
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, texture)
        glActiveTexture(GL_TEXTURE0)
        shader.setInt("minimapMap", 1)

        shader.setVec2("minimapOrigin", originX, originY)
        shader.setVec2("minimapFacing", facingX, facingY)

        // Entity mode off, or the vertex shader divides these UVs by the atlas size and
        // samples the wrong part of the wrong texture.
        shader.setInt("isEntity", 0)
        shader.setInt("isMinimap", 1)
        shader.setMatrix(
            "model",
            Matrix4f().translate(x + diameter / 2f, y + diameter / 2f, 0f).scale(diameter, diameter, 1f)
        )
        glBindVertexArray(square.vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)

        shader.setInt("isMinimap", 0)
        shader.setInt("isEntity", 1)
    }

    /** Releases the quads. The GL context must still be alive. */
    fun release() {
        quad.release()
        square.release()
    }
}
