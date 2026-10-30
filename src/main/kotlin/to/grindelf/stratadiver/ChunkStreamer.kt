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
 * What is in the Cell next door, when the next door is in a different Chunk.
 *
 * A [Chunk] can only answer for its own 16x16x16, and `Chunk.getBlock` answers AIR
 * outside those bounds — which is a lie the mesher used to believe. Every face on a
 * Chunk's border therefore looked exposed no matter what was actually against it, so the
 * mesher drew faces buried inside the neighbouring solid. Reading across the boundary is
 * what makes border culling possible at all.
 *
 * **Null is not air.** It means the world has not produced that Cell. The mesher treats it
 * as "not Opaque" and draws the face, because the frontier has to be visible; a daylight
 * ray passes through it as open sky, and a torchlight ray stops at it. Both readings are
 * correct and they are opposite, which is exactly why they are two explicitly named
 * methods rather than one method with a flag.
 */
interface Neighbourhood {
    /** The Block Type in this Cell, or null where no Chunk covers it. */
    fun blockAtOrNull(x: Int, y: Int, z: Int): BlockType?
}

/**
 * The world addressed the way a ray addresses it: by grid position, not by Cell.
 *
 * A path tracer walks one Cell at a time and so asks the same question hundreds of times
 * per ray — "which Chunk am I in?" — and the answer does not change for sixteen steps at a
 * time. Handing out the Chunk rather than the Block Type lets it hold on to one, and the
 * walk turns into a bounds check and an array read per Cell instead of a map lookup.
 *
 * Never generates. See [Neighbourhood] for why looking must not bring the world into being.
 */
interface GridWorld {
    /** The Chunk at this grid position, or null where there is none. */
    fun existingChunk(cx: Int, cy: Int, cz: Int): Chunk?
}

/**
 * Decides which Chunks exist, generates them, and lights them.
 *
 * One lifetime now. [chunks] is the world's Cells, created on first touch and never
 * discarded — that is what makes "walk away and come back" find your house still standing.
 *
 * There used to be a second: a `meshed` set holding the subset with triangles on the GPU,
 * released on leaving and rebuilt on return. The path tracer reads no vertices at all, so
 * that set was pure cost — a ~570-entry `HashSet<Triple>` rebuilt and sorted every frame,
 * eight Chunks lit and meshed per frame, seven neighbours re-meshed per block broken — paid
 * every frame to produce geometry nothing ever drew.
 */
class ChunkStreamer(
    private val renderDistance: Int = 4,
    private val verticalRenderDistance: Int = 3,
    private val maxChunksPerFrame: Int = 12
) : SolidWorld, LitWorld, GridWorld {

    /** Invoked after block data and derived light are current, for GPU-side consumers. */
    var onBlockChanged: (() -> Unit)? = null

    private val chunks = mutableMapOf<Triple<Int, Int, Int>, Chunk>()

    /**
     * How many times the world's *visible* content has changed since the game started.
     *
     * A progressive renderer has to be told when the picture it has been averaging is no
     * longer the picture. Break a block and it is obvious; a Chunk appearing at the edge of
     * the streamed band is not, and without this the tracer would quietly keep averaging in
     * terrain that has since been generated and converge on a world that is half a minute
     * out of date.
     *
     * Only Chunks the *streamer* makes count. The Minimap generates its own, several hundred
     * Cells away and never drawn in 3D, and counting those would reset the accumulation a
     * dozen times a second and the image would never get sharper than one sample.
     */
    var version: Int = 0
        private set

    /** [chunks] lookup by grid position, counting a Chunk born here as a visible change. */
    private fun chunkAtGrid(cx: Int, cy: Int, cz: Int): Chunk {
        val key = Triple(cx, cy, cz)
        return chunks.getOrPut(key) {
            version++
            Chunk(cx, cy, cz)
        }
    }

    /**
 * Fills Chunks with light. Owned here rather than passed in, because a Chunk is not
 * lit until something asks for it and the streamer is what asks.
 */
val light = LightEngine(this)

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
        return chunkAtGrid(cx, cy, cz)
    }

    override fun existingChunk(cx: Int, cy: Int, cz: Int): Chunk? {
        if (cy < 0 || cy >= Chunk.HEIGHT_LAYERS) return null
        return chunks[Triple(cx, cy, cz)]
    }

    /**
     * The Chunk containing this Cell **only if it already exists**. Never generates.
     *
     * The difference from [chunkAt] is the whole point, and the two are deliberately
     * separate methods rather than one with a flag. Anything that reaches across a border
     * — a light ray, border culling — must not bring the world into existence as a side
     * effect of looking at it: a ray that generated a neighbour mid-trace would read a
     * Chunk whose own light has not been computed, and meshing it there and then would
     * upload a Chunk lit from nothing.
     */
    override fun existingChunkAt(x: Int, y: Int, z: Int): Chunk? {
        val cy = Math.floorDiv(y, Chunk.SIZE)
        if (cy < 0 || cy >= Chunk.HEIGHT_LAYERS) return null
        return chunks[Triple(Math.floorDiv(x, Chunk.SIZE), cy, Math.floorDiv(z, Chunk.SIZE))]
    }

    override fun isSolid(x: Float, y: Float, z: Float): Boolean =
        blockAt(floor(x).toInt(), floor(y).toInt(), floor(z).toInt()) != BlockType.AIR

    /** The Block Type in this Cell, or AIR outside the world. Generates on demand. */
    fun blockAt(x: Int, y: Int, z: Int): BlockType {
        val chunk = chunkAt(x, y, z) ?: return BlockType.AIR
        return BlockType.getById(
            chunk.getBlock(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE), Math.floorMod(z, Chunk.SIZE))
        )
    }

    /**
     * The Block Type in this Cell, or null where no Chunk covers it.
     *
     * Not the same answer as [blockAt], which says AIR. See [Neighbourhood].
     */
    override fun blockAtOrNull(x: Int, y: Int, z: Int): BlockType? {
        val chunk = existingChunkAt(x, y, z) ?: return null
        return BlockType.getById(
            chunk.getBlock(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE), Math.floorMod(z, Chunk.SIZE))
        )
    }

    override fun lightAt(x: Int, y: Int, z: Int): Int {
        val chunk = existingChunkAt(x, y, z) ?: return Light.DARK
        return chunk.packedLightAt(
            Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE), Math.floorMod(z, Chunk.SIZE)
        )
    }

    override fun setLight(x: Int, y: Int, z: Int, packed: Int): Chunk? {
        val chunk = existingChunkAt(x, y, z) ?: return null
        chunk.setPackedLight(
            Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE), Math.floorMod(z, Chunk.SIZE), packed
        )
        return chunk
    }

    /**
     * Writes [type] into the world and brings the light around it up to date. No GL.
     *
     * This is the whole of what a block change means to the *data*, separated from
     * re-uploading triangles so that it can be exercised without a context. Returns false
     * if the Cell is outside the world, in which case nothing happened.
     */
    fun setBlockData(x: Int, y: Int, z: Int, type: BlockType): Boolean {
        val chunk = chunkAt(x, y, z) ?: return false
        val lx = Math.floorMod(x, Chunk.SIZE)
        val ly = Math.floorMod(y, Chunk.SIZE)
        val lz = Math.floorMod(z, Chunk.SIZE)

        // Read the Cell's light before the write, because the write clears it. The engine
        // needs the old level to know how much light the change was letting through, and
        // there is no way to infer it afterwards.
        val wasPacked = chunk.packedLightAt(lx, ly, lz)

        chunk.setBlock(lx, ly, lz, type)
        version++
        light.onBlockChanged(x, y, z, wasPacked)
        onBlockChanged?.invoke()
        return true
    }

    /**
     * [setBlockData]. This is the whole of a block change now.
     *
     * It used to be followed by re-meshing this Chunk, every Chunk sharing a face with the
     * changed Cell, and every Chunk whose light had moved — up to seven meshes per broken
     * block, each one walking its neighbours' Cells to decide which faces are exposed. All
     * of it existed to keep triangles in step with Cell data, and the path tracer reads no
     * triangles: it walks the Cells itself, every ray, and so is always describing the world
     * as it is rather than as it was when it was last meshed.
     */
    fun setBlock(x: Int, y: Int, z: Int, type: BlockType): Boolean {
        if (!setBlockData(x, y, z, type)) return false
        return true
    }

    // --- Streaming ---

    /**
     * Brings the neighbourhood within reach into existence, nearest first.
     *
     * There is nothing to release any more. Cells are never discarded, so leaving a Chunk
     * costs nothing at all and coming back finds it already made — which is the whole
     * reason the two lifetimes were worth keeping apart while there were triangles to hold.
     * What is left is the filling-in, rationed to [maxChunksPerFrame] so crossing a
     * boundary closes the world in over a few frames instead of stalling on one.
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

        // Release what fell out of reach. There is nothing to release: Cells are never
        // discarded, so leaving a Chunk costs nothing and returning finds it already made.

        // Build what came into reach, nearest first so the world closes in around the
        // player rather than filling in from the far edge inward.
        val existing = HashSet<Triple<Int, Int, Int>>()
        for (pos in wanted) if (pos in chunks) existing.add(pos)

        var built = 0
        for (pos in wanted.filter { it !in existing }.sortedBy { (cx, cy, cz) ->
            val dx = cx - playerCX
            val dy = cy - playerCY
            val dz = cz - playerCZ
            dx * dx + dy * dy + dz * dz
        }) {
            if (built >= maxChunksPerFrame) break
            // Light before anything reads it, always. A Chunk whose light has not been
            // computed would be walked by a ray that would find it dark for no reason
            // anyone could name.
            light.lightChunk(chunkAtGrid(pos.first, pos.second, pos.third))
            built++
        }
    }

    /** Nothing to release. The Cells are never discarded, so nothing is ever held. */

    // --- Aboveground reading, for the Minimap ---

    /**
     * The Surface of the world at this horizontal position, or null if the world has not
     * produced that place yet.
     *
     * Scanning walks Chunk layers downward, skipping ones this world has never made.
     * That is the one place in this codebase where "missing" and "air" are deliberately
     * different answers, and the Minimap is the only caller that depends on the
     * difference: a Column the streamer has not reached is Unknown, not empty, and a map
     * that conflated them would be confidently wrong about the frontier.
     */
    fun surfaceAt(x: Int, z: Int): Surface? {
        val cx = Math.floorDiv(x, Chunk.SIZE)
        val cz = Math.floorDiv(z, Chunk.SIZE)
        val lx = Math.floorMod(x, Chunk.SIZE)
        val lz = Math.floorMod(z, Chunk.SIZE)

        for (cy in Chunk.HEIGHT_LAYERS - 1 downTo 0) {
            val chunk = chunks[Triple(cx, cy, cz)] ?: continue
            val ly = chunk.topmostSolid(lx, lz) ?: continue
            return Surface(
                BlockType.getById(chunk.getBlock(lx, ly, lz)),
                ly + cy * Chunk.SIZE
            )
        }
        return null
    }

    /**
     * Generates up to [budget] Chunks that a top-down read of the given area would need,
     * nearest first, and reports whether that made the whole area known.
     *
     * The Minimap covers far more Chunks than the streamer ever holds — a 64-block disc is
     * five Chunks across and sixteen layers deep, and the streamer keeps only a band
     * around the player — so reading it honestly means making the world. Left unthrottled
     * that is hundreds of Chunks of Perlin noise on one frame, a visible hitch at spawn.
     * Spending a few per frame instead turns it into something the player cannot see.
     *
     * The area is treated as known once every Chunk it needs exists, whether this call
     * made them or an earlier one did.
     */
    fun ensureSurfaceKnown(
        centreX: Int, centreZ: Int,
        radius: Int,
        budget: Int,
        onCreated: (Chunk) -> Unit = {}
    ): Boolean {
        val minCX = Math.floorDiv(centreX - radius, Chunk.SIZE)
        val maxCX = Math.floorDiv(centreX + radius, Chunk.SIZE)
        val minCZ = Math.floorDiv(centreZ - radius, Chunk.SIZE)
        val maxCZ = Math.floorDiv(centreZ + radius, Chunk.SIZE)

        val missing = ArrayList<Triple<Int, Int, Int>>()
        for (cx in minCX..maxCX) {
            for (cz in minCZ..maxCZ) {
                for (cy in 0 until Chunk.HEIGHT_LAYERS) {
                    if (Triple(cx, cy, cz) !in chunks) missing.add(Triple(cx, cy, cz))
                }
            }
        }

        // Nearest first: the player is standing in the middle, and the middle is what
        // they are trying to read.
        val centreCX = Math.floorDiv(centreX, Chunk.SIZE)
        val centreCZ = Math.floorDiv(centreZ, Chunk.SIZE)
        missing.sortWith(
            compareBy<Triple<Int, Int, Int>> {
                val dx = it.first - centreCX
                val dz = it.third - centreCZ
                dx * dx + dz * dz
            }.thenBy { it.second }
        )

        var made = 0
        for ((cx, cy, cz) in missing) {
            if (made >= budget) break
            onCreated(chunks.getOrPut(Triple(cx, cy, cz)) { Chunk(cx, cy, cz) })
            made++
        }
        return missing.size <= made
    }
}

/** What the top of a Column is made of, and how high up it is. */
data class Surface(val type: BlockType, val worldY: Int)
