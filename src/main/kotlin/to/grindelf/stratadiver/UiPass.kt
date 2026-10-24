package to.grindelf.stratadiver

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector3i
import org.joml.Vector4f
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_LINES
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
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Drawing in screen space: flat quads, one orthographic projection, no lighting.
 *
 * Every GUI element in the game goes through here — the hotbar, the inventory, the
 * crosshair, the FPS readout. Centralising it is what stops each of them from quietly
 * disagreeing about depth testing, projection and which Tile of the atlas they sample.
 * It was five copies of the same seven lines of setup before.
 */
class UiPass(
    private val shader: ShaderProgram,
    /** The Atlas every HUD quad samples. Bound by [begin], on this pass's own terms. */
    private val atlas: Int,
    val width: Float,
    val height: Float
) {

    private val quad = Geometry.upload(UNIT_QUAD, Geometry.POS_UV)!!
    private val square = Geometry.upload(UNIT_SQUARE, Geometry.POS_UV)!!

    private val stack: FloatBuffer =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Top-left origin, y growing downwards — screen coordinates, not NDC. */
    private val projection = Matrix4f().ortho(0f, width, height, 0f, -1f, 1f)
    private val identity = Matrix4f()

    /**
     * Starts a GUI layer: screen-space projection, no depth test, unlit.
     *
     * The Atlas is bound here rather than by whoever drew last. Texture unit 0 is global
     * state that outlives every pass: the world pass used to bind the Atlas and leave it,
     * which was true right up until the path tracer replaced that pass and left a picture
     * there instead. Nothing complained. The HUD sampled it, the crosshair turned into a
     * little window onto the world, and the FPS readout changed colour with the average
     * while keeping its shape — which is exactly the kind of bug that survives a playtest
     * because every part of it is individually plausible.
     *
     * So a pass binds what it reads, every time it begins, and never inherits.
     */
    fun begin() {
        shader.use()
        glDisable(GL_DEPTH_TEST)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, atlas)
        shader.setInt("textureAtlas", 0)
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

    // --- Selection box (wireframe cube in screen space) ---

    /**
     * Projects a 3D world point to 2D screen coordinates using the same view/projection
     * as the path tracer's Camera/Basis.
     */
    private fun projectToScreen(
        worldX: Float, worldY: Float, worldZ: Float,
        eye: Vector3f, front: Vector3f, up: Vector3f,
        fovDegrees: Float, aspect: Float,
        screenWidth: Float, screenHeight: Float
    ): Vector3f {
        // Build view matrix (same as PathTracer.Basis)
        val forward = Vector3f(front).normalize()
        var right = Vector3f().cross(forward, up)
        val rightLen = right.length()
        if (rightLen < 1e-4f) {
            // Degenerate: looking straight up/down, use a substitute up
            right = Vector3f().cross(forward, Vector3f(0f, 0f, 1f))
        }
        right.normalize()
        val trueUp = Vector3f().cross(right, forward)

        // View matrix: world -> view space
        val view = Matrix4f()
        view.set(
            right.x, trueUp.x, -forward.x, 0f,
            right.y, trueUp.y, -forward.y, 0f,
            right.z, trueUp.z, -forward.z, 0f,
            -right.dot(eye), -trueUp.dot(eye), forward.dot(eye), 1f
        )

        // Projection matrix: view -> clip space
        val tanHalf = tan((fovDegrees / 2f) * Math.PI / 180f).toFloat()
        val proj = Matrix4f().perspective(
            2f * atan(tanHalf).toFloat(), // vertical FOV
            aspect,
            0.01f, // near
            1000f  // far
        )

        // View-projection matrix
        val viewProj = Matrix4f(proj).mul(view)

        // Transform world point to clip space (vec4)
        val world = Vector4f(worldX, worldY, worldZ, 1f)
        val clip = Vector4f()
        viewProj.transform(world, clip)

        // Perspective divide + viewport transform
        val invW = 1f / clip.w
        val ndcX = clip.x * invW
        val ndcY = clip.y * invW
        val screenX = (ndcX + 1f) * 0.5f * screenWidth
        val screenY = (1f - ndcY) * 0.5f * screenHeight // flip Y for screen coords (0 at top)
        val depth = clip.z * invW

        return Vector3f(screenX, screenY, depth)
    }

    /**
     * Draws the wireframe selection box around the aimed block in screen space.
     * Projects the 8 corners of the block and draws the 12 edges.
     */
    fun drawSelectionBox(
        eye: Vector3f, front: Vector3f, up: Vector3f,
        fovDegrees: Float, aspect: Float,
        aimedBlock: Vector3i?
    ) {
        aimedBlock ?: return

        // 8 corners of the block (min to max)
        val corners = arrayOf(
            Vector3f(aimedBlock.x.toFloat(), aimedBlock.y.toFloat(), aimedBlock.z.toFloat()),
            Vector3f(aimedBlock.x.toFloat() + 1f, aimedBlock.y.toFloat(), aimedBlock.z.toFloat()),
            Vector3f(aimedBlock.x.toFloat() + 1f, aimedBlock.y.toFloat() + 1f, aimedBlock.z.toFloat()),
            Vector3f(aimedBlock.x.toFloat(), aimedBlock.y.toFloat() + 1f, aimedBlock.z.toFloat()),
            Vector3f(aimedBlock.x.toFloat(), aimedBlock.y.toFloat(), aimedBlock.z.toFloat() + 1f),
            Vector3f(aimedBlock.x.toFloat() + 1f, aimedBlock.y.toFloat(), aimedBlock.z.toFloat() + 1f),
            Vector3f(aimedBlock.x.toFloat() + 1f, aimedBlock.y.toFloat() + 1f, aimedBlock.z.toFloat() + 1f),
            Vector3f(aimedBlock.x.toFloat(), aimedBlock.y.toFloat() + 1f, aimedBlock.z.toFloat() + 1f)
        )

        // Project all corners to screen space
        val screenCorners = corners.map { c ->
            projectToScreen(c.x, c.y, c.z, eye, front, up, fovDegrees, aspect, width, height)
        }

        // 12 edges of a cube (pairs of corner indices)
        val edges = intArrayOf(
            0, 1,  1, 2,  2, 3,  3, 0,  // bottom face
            4, 5,  5, 6,  6, 7,  7, 4,  // top face
            0, 4,  1, 5,  2, 6,  3, 7   // vertical edges
        )

        // Draw each edge as a line using the LINE tile (white)
        // We need to draw thin quads along each edge since we only have triangle drawing
        // For a proper wireframe, we'd use GL_LINES, but we can approximate with thin quads
        begin()
        for (i in 0 until edges.size step 2) {
            val a = screenCorners[edges[i]]
            val b = screenCorners[edges[i + 1]]
            drawLine(a.x, a.y, b.x, b.y, 1.5f) // 1.5px thick line
        }
        end()
    }

    /**
     * Draws a line from (x1,y1) to (x2,y2) with given thickness using the LINE tile.
     * Approximates a line with a thin rotated quad.
     */
    private fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.5f) return

        val cx = (x1 + x2) * 0.5f
        val cy = (y1 + y2) * 0.5f
        val angle = atan2(dy, dx).toFloat()

        val model = Matrix4f()
            .translate(cx, cy, 0f)
            .rotateZ(angle)
            .scale(len, thickness, 1f)

        shader.setMatrix("model", model)
        shader.setVec2("uvOffset", Atlas.LINE.tx.toFloat(), Atlas.LINE.ty.toFloat())
        shader.setTint(1f)
        glBindVertexArray(quad.vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)
    }
}
