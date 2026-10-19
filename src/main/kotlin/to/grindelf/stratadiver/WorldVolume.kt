package to.grindelf.stratadiver

import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_RED
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL12.GL_TEXTURE_3D
import org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R
import org.lwjgl.opengl.GL12.glTexImage3D
import org.lwjgl.opengl.GL12.glTexSubImage3D
import org.joml.Vector3f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * The world's Cells, on the GPU, as one 3D texture the shader can walk.
 *
 * **Why a flat volume rather than a texture per Chunk.** A ray asks "what is in this Cell?"
 * hundreds of times, and the whole cost of the renderer is how many times it can ask that.
 * With one texture per Chunk every question needs two lookups — find the Chunk, then find
 * the Cell in it — and the second is an index the first has to produce. With one flat
 * volume a question is `floor(worldPos - origin)` and a single fetch. On a CPU that
 * difference was a map lookup worth avoiding; on a GPU it is the difference between a walk
 * that keeps up with the frame rate and one that does not.
 *
 * **Why it moves.** The window is anchored to the player, so it slides as they walk. Rather
 * than resampling on every step, the anchor only moves in whole Chunks and a whole-Chunk
 * move re-uploads everything: about 2.3 MB, which is under a millisecond of bandwidth and
 * happens once every sixteen blocks walked. Between moves, only the Chunks that have
 * actually changed are uploaded, so the cost while standing still is zero and the cost
 * while the world streams in is one sub-image per new Chunk.
 *
 * **Where the edge is.** A Cell outside the volume reads as AIR, which is what a Cell in a
 * Chunk the world has not generated also reads as on the CPU side. The tracer's aerial
 * perspective is what turns that edge into haze rather than a cliff, and it is why the
 * volume can be smaller than [PathTracer.MAX_DISTANCE] without a hard line appearing in the
 * sky.
 */
class WorldVolume(
    private val world: GridWorld,
    /** How many Chunks across and up the window reaches. */
    private val across: Int = 9,
    private val high: Int = 7
) {

    companion object {
        /**
         * GL_RG8: the Block Type's id in R, the Cell's packed light byte in G.
         *
         * Two channels rather than one because both are read in the same place by the same
         * ray and they answer different questions. The id decides whether the ray stops; the
         * light decides whether the air in front of the Cell has any sky to scatter, which
         * is the difference between a cave reading as dark and a valley reading as hazy.
         * Four megabytes for the window, which is nothing next to what it replaced.
         */
        private const val INTERNAL_FORMAT = 0x822B
        private const val FORMAT = 0x8227
    }

    val sizeX = across * Chunk.SIZE
    val sizeY = high * Chunk.SIZE
    val sizeZ = across * Chunk.SIZE

    /** The world Cell coordinates of the volume's (0,0,0). Uniforms for the shader. */
    var originX = 0
        private set
    var originY = 0
        private set
    var originZ = 0
        private set

    /** The Chunk grid positions already uploaded at the current origin. */
    private val uploaded = HashSet<Long>()

    private val id = glGenTextures()

    /** One Chunk's worth of Cells, staged for upload. Reused; 4 KB. */
    private val staging = ByteBuffer
        .allocateDirect(Chunk.SIZE * Chunk.SIZE * Chunk.SIZE * 2)
        .order(ByteOrder.nativeOrder())

    init {
        glBindTexture(GL_TEXTURE_3D, id)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        // Clamp, not repeat. A ray that walks off the edge must stop finding terrain at
        // the boundary; a repeat wrap would make the world's last row of Cells fold back on
        // itself, which is a wall of duplicate terrain rather than the open air outside.
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE)
        glTexImage3D(
            GL_TEXTURE_3D, 0, INTERNAL_FORMAT, sizeX, sizeY, sizeZ, 0,
            FORMAT, GL_UNSIGNED_BYTE, null as ByteBuffer?
        )
    }

    val texture: Int get() = id

    /**
     * Re-anchors the window on [position] and uploads whatever is now missing or stale.
     *
     * Called every frame. Almost always it does nothing at all, which is the point: the
     * expensive case is bounded by how often the player crosses a Chunk boundary rather
     * than by how often they move.
     */
    fun update(position: Vector3f) {
        val cx = Math.floorDiv(floor(position.x).toInt(), Chunk.SIZE)
        val cy = Math.floorDiv(floor(position.y).toInt(), Chunk.SIZE)
        val cz = Math.floorDiv(floor(position.z).toInt(), Chunk.SIZE)

        val anchorX = cx - across / 2
        val anchorY = cy - high / 2
        val anchorZ = cz - across / 2

        if (anchorX != originX || anchorY != originY || anchorZ != originZ) {
            originX = anchorX * Chunk.SIZE
            originY = anchorY * Chunk.SIZE
            originZ = anchorZ * Chunk.SIZE
            // Every Cell's position within the volume just changed, so the record of what
            // was uploaded is no longer about anything.
            uploaded.clear()
        }

        val cx0 = anchorX
        val cy0 = anchorY
        val cz0 = anchorZ

        for (gx in cx0 until cx0 + across) {
            for (gy in cy0 until cy0 + high) {
                if (gy < 0 || gy >= Chunk.HEIGHT_LAYERS) continue
                for (gz in cz0 until cz0 + across) {
                    val key = key(gx, gy, gz)
                    if (key in uploaded) continue
                    // Only Chunks that already exist are uploaded. A Chunk the streamer has
                    // not generated yet leaves the volume's cell as AIR, which is what the
                    // CPU tracer reads for it too — and the streamer will call back when it
                    // has one.
                    if (world.existingChunk(gx, gy, gz) == null) continue
                    uploadChunk(gx, gy, gz)
                    uploaded.add(key)
                }
            }
        }
    }

    private fun key(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x1FFFFF) shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)

    private fun uploadChunk(cx: Int, cy: Int, cz: Int) {
        val chunk = world.existingChunk(cx, cy, cz) ?: return
        val base = cx * Chunk.SIZE - originX
        val baseY = cy * Chunk.SIZE - originY
        val baseZ = cz * Chunk.SIZE - originZ
        if (base < 0 || baseY < 0 || baseZ < 0) return
        if (base + Chunk.SIZE > sizeX || baseY + Chunk.SIZE > sizeY || baseZ + Chunk.SIZE > sizeZ) return

        // Chunk's own indexing is x-major, then y, then z; the texture wants the same
        // order, so this is a straight walk rather than a shuffle.
        staging.clear()
        for (lx in 0 until Chunk.SIZE) {
            for (ly in 0 until Chunk.SIZE) {
                for (lz in 0 until Chunk.SIZE) {
                    staging.put(chunk.getBlock(lx, ly, lz))
                    staging.put(chunk.packedLightAt(lx, ly, lz).toByte())
                }
            }
        }
        staging.flip()

        glBindTexture(GL_TEXTURE_3D, id)
        glTexSubImage3D(
            GL_TEXTURE_3D, 0, base, baseY, baseZ,
            Chunk.SIZE, Chunk.SIZE, Chunk.SIZE,
            FORMAT, GL_UNSIGNED_BYTE, staging
        )
    }

    /**
     * Uploads a single Cell that the player just changed.
     *
     * A block broken or placed is a one-byte change, and re-uploading its whole Chunk
     * would move a sixteenth of a megabyte to fix a texel. The volume has to be told
     * rather than left describing a world that no longer exists — which is the same reason
     * `Chunk.setBlock` clears the Cell's light and the Surface cache rather than trusting
     * whoever reads them next.
     */
    fun markCell(x: Int, y: Int, z: Int) {
        val gx = Math.floorDiv(x, Chunk.SIZE)
        val gy = Math.floorDiv(y, Chunk.SIZE)
        val gz = Math.floorDiv(z, Chunk.SIZE)
        uploaded.remove(key(gx, gy, gz))

        val lx = x - gx * Chunk.SIZE
        val ly = y - gy * Chunk.SIZE
        val lz = z - gz * Chunk.SIZE
        val bx = x - originX
        val by = y - originY
        val bz = z - originZ
        if (bx !in 0 until sizeX || by !in 0 until sizeY || bz !in 0 until sizeZ) return

        val chunk = world.existingChunk(gx, gy, gz) ?: return
        val cell = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder())
        cell.put(chunk.getBlock(lx, ly, lz))
        cell.put(chunk.packedLightAt(lx, ly, lz).toByte())
        cell.flip()

        glBindTexture(GL_TEXTURE_3D, id)
        glTexSubImage3D(GL_TEXTURE_3D, 0, bx, by, bz, 1, 1, 1, FORMAT, GL_UNSIGNED_BYTE, cell)
        uploaded.add(key(gx, gy, gz))
    }

    fun release() {
        org.lwjgl.opengl.GL11.glDeleteTextures(id)
    }
}