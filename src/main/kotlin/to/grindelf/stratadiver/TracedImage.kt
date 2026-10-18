package to.grindelf.stratadiver

import org.joml.Matrix4f
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_FALSE
import org.lwjgl.opengl.GL11.GL_LINEAR
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDeleteTextures
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glTexSubImage2D
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glBindVertexArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Puts a finished picture on the screen: one texture, one quad, one shader that does
 * nothing but copy it.
 *
 * Deliberately the simplest possible presenter. A progressive path tracer's output is an
 * ordinary image and the temptation, once there is a fullscreen quad in the project, is to
 * start putting things in this shader — a sharpen, a film curve, a vignette, a tone map.
 * Every one of those would then be a thing living in two places, because the tracer already
 * has to produce a displayable image for tools/Check.kt to look at. This draws what it is
 * given.
 *
 * Filtering is LINEAR, and that is a real choice rather than a default. The image is traced
 * at a fraction of the window and enlarged, and the enlargement has to smooth: nearest
 * filtering would give a path tracer's soft shadows and gentle gradients a hard-edged
 * staircase, which reads as broken rather than as retro. The chunkiness of the 16x16 art
 * survives anyway, because the tracer samples art stochastically and the chunk is in the
 * *data* — every ray lands on one of sixteen colours and the average is the art's own
 * average, not a blurred version of somebody else's idea of it.
 */
class TracedImage(
    width: Float,
    height: Float,
    shaderPathVertex: String = "src/main/resources/shaders/screen.vert",
    shaderPathFragment: String = "src/main/resources/shaders/screen.frag"
) {

    private val shader = ShaderProgram(shaderPathVertex, shaderPathFragment)
    private val quad = Geometry.upload(UNIT_QUAD, Geometry.POS_UV)!!

    private val projection = Matrix4f().ortho(0f, width, height, 0f, -1f, 1f)
    private val model = Matrix4f().translate(width / 2f, height / 2f, 0f).scale(width, height, 1f)

    private var texture = 0
    private var allocatedWidth = 0
    private var allocatedHeight = 0

    /**
     * The staging buffer the picture is copied through, kept between frames.
     *
     * Direct, because the driver takes an address out of it and a heap array has none; and
     * kept, because allocating a quarter of a megabyte every frame is a quarter of a
     * megabyte of garbage every frame, and the alternative is one branch.
     */
    private var staging: ByteBuffer? = null

    /**
     * The size the staging buffer starts at, so the adaptive resolution settling downwards
     * does not reallocate on its way.
     */
    private companion object {
        const val INITIAL_STAGING_BYTES = 1280 * 720
    }

    /** Uploads [rgba] as the whole image, reallocating only when the size has changed. */
    fun update(rgba: ByteArray, width: Int, height: Int) {
        val needed = width * height * 4
        val buffer = staging ?: ByteBuffer
            .allocateDirect(maxOf(needed, INITIAL_STAGING_BYTES))
            .order(ByteOrder.nativeOrder())
            .also { staging = it }
        buffer.clear()
        buffer.put(rgba, 0, needed)
        buffer.position(0)
        buffer.limit(needed)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)

        if (width != allocatedWidth || height != allocatedHeight) {
            // Reallocation rather than a sub-image upload: the size changes when the
            // tracer's own adaptive resolution decides the machine is struggling, which is
            // rare, and a sub-image into a buffer of the wrong shape is a picture of
            // somewhere else entirely.
            allocatedWidth = width
            allocatedHeight = height
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        }
    }

    /** Draws the image over the whole window. The GUI goes on top of it, as always. */
    fun draw() {
        glDisable(GL_DEPTH_TEST)
        shader.use()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        shader.setInt("image", 0)
        shader.setMatrix("projection", projection)
        shader.setMatrix("model", model)
        glBindVertexArray(quad.vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)
        glEnable(GL_DEPTH_TEST)
    }

    fun release() {
        quad.release()
        if (texture != 0) glDeleteTextures(texture)
    }

    init {
        texture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        // Clamp, not repeat. The quad's UVs run 0..1, and a repeat wrap would show the far
        // edge of the trace beside the near one if the quad were ever scaled unevenly.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }
}