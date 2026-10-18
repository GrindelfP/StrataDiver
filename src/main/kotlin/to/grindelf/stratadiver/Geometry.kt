package to.grindelf.stratadiver

import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15.GL_STATIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL20.glDisableVertexAttribArray
import org.lwjgl.opengl.GL20.glEnableVertexAttribArray
import org.lwjgl.opengl.GL20.glVertexAttribPointer
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteBuffers
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays

/**
 * A block of geometry living on the GPU: the VAO describing its layout, the VBO holding
 * its vertices, and how many vertices are actually there.
 *
 * These were three loose fields on Chunk (`vao`, `vbo`, `vertexCount`) and were kept in
 * step by hand, which is how "release the old mesh *before* computing the new vertex
 * count" became a bug that shipped and blanked the world. Bundled into one object they
 * are released together or not at all.
 */
class Mesh(val vao: Int, private val vbo: Int, val vertexCount: Int) {

    fun release() {
        glDeleteVertexArrays(vao)
        glDeleteBuffers(vbo)
    }
}

/**
 * Uploading vertex data to the GPU, and the handful of shapes the game draws over and
 * over.
 *
 * One vertex format now: position and UV. It is what the GUI and the presenter draw with,
 * and it is the whole of it — normals, per-vertex light and corner shade all belonged to
 * the mesher, and a path tracer computes what light a surface received one ray at a time
 * rather than reading it out of a vertex.
 */
object Geometry {

    /** Position (3) + UV (2). Attributes 0 and 1. */
    const val POS_UV = 5

    /**
     * Uploads [vertices] and returns the resulting mesh, or null if there is nothing to
     * draw.
     *
     * Null for empty input is not a micro-optimisation: most of the vertical band above
     * the terrain is all air, and an all-air Chunk should cost no GPU memory and no draw
     * call. Letting that case fall out of the upload itself is why no caller has to
     * remember the special case.
     */
    fun upload(vertices: FloatArray, floatsPerVertex: Int): Mesh? {
        if (vertices.isEmpty()) return null

        val vao = glGenVertexArrays()
        glBindVertexArray(vao)

        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        val stride = floatsPerVertex * 4
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12L)
        glEnableVertexAttribArray(1)
        // Attributes 2..4 held a normal, two light channels and a corner shade. Every one
        // of them belonged to the rasterizer, and they are disabled explicitly rather than
        // left alone: a disabled attribute reads the *generic* current value, which is
        // global state and not part of the VAO, so leaving them enabled would let a quad
        // quietly pick up whatever happened to be current.
        glDisableVertexAttribArray(2)
        glDisableVertexAttribArray(3)
        glDisableVertexAttribArray(4)

        return Mesh(vao, vbo, vertices.size / floatsPerVertex)
    }
}

// --- The shapes the game draws ---

/** A unit quad centred on the origin. The basis of every screen-space GUI element. */
val UNIT_QUAD = floatArrayOf(
    -0.5f, -0.5f, 0f, 0f, 0f,
    0.5f, -0.5f, 0f, 1f, 0f,
    0.5f, 0.5f, 0f, 1f, 1f,
    -0.5f, -0.5f, 0f, 0f, 0f,
    0.5f, 0.5f, 0f, 1f, 1f,
    -0.5f, 0.5f, 0f, 0f, 1f
)

/**
 * The Minimap's quad: a unit square, drawn in screen space and sized by the model matrix.
 *
 * The shader turns this square into a disc by discarding its corners, so the geometry is
 * deliberately just a square — and its UVs are bare 0..1, which the vertex shader passes
 * through untouched because the minimap's UVs are map coordinates, not atlas coordinates.
 *
 * This replaces a parked `MINIMAP_QUAD` that was expressed in NDC. That one had been
 * given hand-typed corner coordinates that no longer matched the aspect-corrected square
 * it was originally computed from — it described a strip three times too wide. Nothing
 * was ever drawn with it, so it was wrong in a file rather than on screen.
 */
val UNIT_SQUARE = floatArrayOf(
    -0.5f, -0.5f, 0f, 0f, 0f,
    0.5f, -0.5f, 0f, 1f, 0f,
    0.5f, 0.5f, 0f, 1f, 1f,
    -0.5f, -0.5f, 0f, 0f, 0f,
    0.5f, 0.5f, 0f, 1f, 1f,
    -0.5f, 0.5f, 0f, 0f, 1f
)
