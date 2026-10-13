package to.grindelf.stratadiver

import org.joml.Vector3f
import kotlin.math.floor

/**
 * The one question anything moving through the world needs answered: is this Cell Solid?
 *
 * The Player and every Entity used to reach for the whole game object to ask it, which
 * meant a physics test could not be exercised without a window, a shader program and a
 * chunk streamer. This is the whole of what they depend on instead.
 */
interface SolidWorld {
    fun isSolid(x: Float, y: Float, z: Float): Boolean
}

/**
 * Decides which Chunks exist, which of them hold GPU memory, and what is in them.
 *
 * Two lifetimes are deliberately kept apart. [chunks] is the world's Cells, created on
 * first touch and never discarded — that is what makes "walk away and come back" find
 * your house still standing. [meshed] is only the subset currently holding triangles;
 * leaving releases the mesh and keeps the Cells, returning re-meshes from the same data.
 * Collapsing the two is what made frame cost grow with distance explored.
 */
class ChunkStreamer(
    private val renderDistance: Int = 4,
    private val verticalRenderDistance: Int = 3,
    private val maxMeshesPerFrame: Int = 8
) : SolidWorld {

    private val chunks = mutableMapOf<Triple<Int, Int, Int>, Chunk>()

    /** The subset of [chunks] that currently holds GPU memory. Never grows without bound. */
    val meshed = mutableSetOf<Chunk>()

    // --- The world as the game sees it ---

    /**
     * The Chunk containing this Cell, generated on first touch. Null means only "outside
     * the world" — never "not loaded yet".
     *
     * That distinction matters. With a finite vertical band, a Chunk that was missing
     * used to read as open air, which would let physics and the raycast see straight
     * through ground the streamer simply had not reached yet. Generation is
     * deterministic, so a Chunk born here is identical to the one the streamer builds a
     * moment later — the player can never stand on terrain that reshapes underneath them.
     */
    fun chunkAt(x: Int, y: Int, z: Int): Chunk? {
        val cy = Math.floorDiv(y, Chunk.SIZE)
        if (cy < 0 || cy >= Chunk.HEIGHT_LAYERS) return null
        val cx = Math.floorDiv(x, Chunk.SIZE)
        val cz = Math.floorDiv(z, Chunk.SIZE)
        return chunks.getOrPut(Triple(cx, cy, cz)) { Chunk(cx, cy, cz) }
    }

    override fun isSolid(x: Float, y: Float, z: Float): Boolean =
        blockAt(floor(x).toInt(), floor(y).toInt(), floor(z).toInt()) != BlockType.AIR

    /** The Block Type in this Cell, or AIR outside the world. */
    fun blockAt(x: Int, y: Int, z: Int): BlockType {
        val chunk = chunkAt(x, y, z) ?: return BlockType.AIR
        return BlockType.getById(
            chunk.getBlock(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE), Math.floorMod(z, Chunk.SIZE))
        )
    }

    /**
     * Writes [type] into the world and re-meshes the affected Chunk. Returns false if
     * the Cell is outside the world, in which case nothing happened.
     */
    fun setBlock(x: Int, y: Int, z: Int, type: BlockType): Boolean {
        val chunk = chunkAt(x, y, z) ?: return false
        chunk.setBlock(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE), Math.floorMod(z, Chunk.SIZE), type)
        // Registered here rather than at the call site: a mesh nobody tracks would be
        // drawn as nothing and never freed.
        mesh(chunk)
        return true
    }

    // --- Streaming ---

    /**
     * Brings the meshed neighbourhood back in line with where the player is standing.
     *
     * Releasing is unconditional and immediate; building is rationed to [maxMeshesPerFrame].
     * Together they keep both the GPU footprint and the per-frame draw list bounded by
     * the render distance rather than by how far the player has ever walked.
     */
    fun update(centre: Vector3f) {
        val playerCX = Math.floorDiv(floor(centre.x).toInt(), Chunk.SIZE)
        val playerCY = Math.floorDiv(floor(centre.y).toInt(), Chunk.SIZE)
        val playerCZ = Math.floorDiv(floor(centre.z).toInt(), Chunk.SIZE)

        // Everything within reach right now.
        val wanted = HashSet<Triple<Int, Int, Int>>()
        for (x in -renderDistance..renderDistance) {
            for (z in -renderDistance..renderDistance) {
                for (y in -verticalRenderDistance..verticalRenderDistance) {
                    val cy = playerCY + y
                    if (cy < 0 || cy >= Chunk.HEIGHT_LAYERS) continue
                    wanted.add(Triple(playerCX + x, cy, playerCZ + z))
                }
            }
        }

        // Release what fell out of reach. Its Cells stay in `chunks`.
        val iterator = meshed.iterator()
        while (iterator.hasNext()) {
            val chunk = iterator.next()
            if (chunk.position !in wanted) {
                chunk.releaseMesh()
                iterator.remove()
            }
        }

        // Build what came into reach, nearest first so the world closes in around the
        // player rather than filling in from the far edge inward.
        //
        // `meshedPositions` is snapshotted rather than querying the set per wanted Chunk:
        // with ~570 in reach, scanning the set each time would be a few hundred thousand
        // comparisons every single frame.
        val meshedPositions = HashSet<Triple<Int, Int, Int>>(meshed.size * 2)
        for (chunk in meshed) meshedPositions.add(chunk.position)

        var built = 0
        for (pos in wanted.filter { it !in meshedPositions }.sortedBy { (cx, cy, cz) ->
            val dx = cx - playerCX
            val dy = cy - playerCY
            val dz = cz - playerCZ
            dx * dx + dy * dy + dz * dz
        }) {
            if (built >= maxMeshesPerFrame) break
            mesh(chunks.getOrPut(pos) { Chunk(pos.first, pos.second, pos.third) })
            built++
        }
    }

    /** Meshes [chunk], replacing whatever it had on the GPU before. */
    fun mesh(chunk: Chunk) {
        // Release first. This frees the old mesh and drops the old vertex count, so it
        // has to happen before the new count is computed — getting that order wrong
        // zeroes every Chunk the instant it is built and the world disappears.
        chunk.releaseMesh()

        chunk.mesh = Geometry.upload(
            MeshGenerator.generateChunkMesh(chunk),
            Geometry.POS_UV_NORMAL
        )

        meshed.add(chunk)
    }

    /** Frees every live mesh, keeping the Cells. Called before the GL context dies. */
    fun releaseAll() {
        meshed.forEach { it.releaseMesh() }
        meshed.clear()
    }
}
