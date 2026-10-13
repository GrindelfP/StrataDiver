package to.grindelf.stratadiver

import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15.GL_STATIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glGenBuffers
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
 * The attribute slots are fixed by vertex.glsl: position at 0, UV at 1, normal at 2. So
 * the only thing a caller has to say is how many floats each of its vertices has.
 */
object Geometry {

    /** Position (3) + UV (2). Attributes 0 and 1. */
    const val POS_UV = 5

    /** Position (3) + UV (2) + normal (3). Attributes 0, 1 and 2 — what a Chunk mesh uses. */
    const val POS_UV_NORMAL = 8

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
        if (floatsPerVertex == POS_UV_NORMAL) {
            glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 20L)
            glEnableVertexAttribArray(2)
        }

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
 * The unit cube a Dropped Item is drawn from, with UVs in 0..1 so the shader can point
 * them at a Tile.
 */
val UNIT_CUBE = floatArrayOf(
    // Front face (Z+)
    0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f,
    1f, 1f, 1f, 1f, 1f, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 1f,
    0f, 1f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,

    // Back face (Z-)
    0f, 0f, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, -1f,
    1f, 1f, 0f, 1f, 1f, 0f, 0f, -1f, 1f, 1f, 0f, 1f, 1f, 0f, 0f, -1f,
    1f, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -1f,

    // Left face (X-)
    0f, 1f, 1f, 1f, 1f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, -1f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 0f,
    0f, 0f, 1f, 1f, 0f, -1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, -1f, 0f, 0f,

    // Right face (X+)
    1f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f, 0f,
    1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
    1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, 0f, 0f,

    // Top face (Y+)
    0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f,
    1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 0f, 0f, 0f, 1f, 0f,
    1f, 1f, 0f, 1f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f,

    // Bottom face (Y-)
    0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, -1f, 0f,
    1f, 0f, 1f, 1f, 0f, 0f, -1f, 0f, 1f, 0f, 1f, 1f, 0f, 0f, -1f, 0f,
    0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f
)

/**
 * Every vertex of the selection box samples the exact centre of [Atlas.LINE] — an opaque
 * white Tile — because the box is drawn unlit and has to survive the fragment shader's
 * alpha cutout. Sampling the wrong Tile here is what once made the wireframe vanish.
 */
private val LINE_U = 0.5f / Atlas.TILES
private val LINE_V = 3.5f / Atlas.TILES

/** The 12 edges of a unit cube, as position-only vertices. */
private val EDGES = floatArrayOf(
    0f, 0f, 0f, 1f, 0f, 0f,   1f, 0f, 0f, 1f, 1f, 0f,   1f, 1f, 0f, 0f, 1f, 0f,
    1f, 0f, 1f, 1f, 1f, 1f,   1f, 1f, 1f, 0f, 1f, 1f,   0f, 1f, 1f, 0f, 0f, 1f,
    0f, 0f, 1f, 0f, 0f, 0f,   0f, 0f, 0f, 0f, 1f, 0f,   0f, 1f, 0f, 0f, 1f, 0f,
    0f, 0f, 1f, 0f, 1f, 1f,   0f, 1f, 1f, 0f, 0f, 1f,   0f, 0f, 1f, 0f, 0f, 0f
)

/** The wireframe box drawn around the Cell the player is looking at. */
val SELECTION_BOX = FloatArray(EDGES.size / 3 * 5).also { box ->
    var o = 0
    var i = 0
    while (i < EDGES.size) {
        box[o++] = EDGES[i++]
        box[o++] = EDGES[i++]
        box[o++] = EDGES[i++]
        box[o++] = LINE_U
        box[o++] = LINE_V
    }
}

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
