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
 * as "not Opaque" and draws the face, because the frontier has to be visible; Light treats
 * it as Unknown and refuses to propagate across it. Both readings are correct and they are
 * opposite, which is exactly why they are two explicitly named methods rather than one
 * method with a flag.
 */
interface Neighbourhood {
    /** The Block Type in this Cell, or null where no Chunk covers it. */
    fun blockAtOrNull(x: Int, y: Int, z: Int): BlockType?
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
) : SolidWorld, LitWorld {

    private val chunks = mutableMapOf<Triple<Int, Int, Int>, Chunk>()

    /** The subset of [chunks] that currently holds GPU memory. Never grows without bound. */
    val meshed = mutableSetOf<Chunk>()

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
        return chunks.getOrPut(Triple(cx, cy, cz)) { Chunk(cx, cy, cz) }
    }

    /**
     * The Chunk containing this Cell **only if it already exists**. Never generates.
     *
     * The difference from [chunkAt] is the whole point, and the two are deliberately
     * separate methods rather than one with a flag. Anything that floods outward — light
     * propagation, border culling — must not bring the world into existence as a side
     * effect of looking at it: a flood that generated a neighbour mid-walk would be
     * reading a Chunk whose own light has not been computed, and meshing it there and
     * then would upload a Chunk lit from nothing.
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
        light.onBlockChanged(x, y, z, wasPacked)
        return true
    }

    /**
     * [setBlockData], plus re-meshing the Chunk and any neighbour whose light changed.
     *
     * The neighbours matter: light that crossed into another Chunk has made that Chunk's
     * triangles wrong. They are only re-meshed if they are actually being drawn, so
     * building far from the view costs nothing extra.
     *
     * A Chunk sharing a face with the changed Cell is re-meshed regardless of whether its
     * light changed: the mesh depends on block data too, and a break or place at a Chunk
     * border exposes a face whose neighbour's mesh was culled against the old state. If
     * that neighbour's light happens to be unchanged (both sides at full daylight, for
     * instance), the dirty-set alone would never re-mesh it and the player would see a
     * hole through the world until some later change happened to touch that Chunk's light.
     */
    fun setBlock(x: Int, y: Int, z: Int, type: BlockType): Boolean {
        if (!setBlockData(x, y, z, type)) return false
        // Registered here rather than at the call site: a mesh nobody tracks would be
        // drawn as nothing and never freed.
        mesh(chunkAt(x, y, z)!!)

        // Re-mesh any Chunk sharing a face with the changed Cell, whether or not its
        // light changed. The changed Cell may sit on a Chunk border, and the face that
        // was culled against the old neighbour state is in the *other* Chunk.
        val size = Chunk.SIZE
        val lx = Math.floorMod(x, size)
        val ly = Math.floorMod(y, size)
        val lz = Math.floorMod(z, size)
        if (lx == 0) existingChunkAt(x - 1, y, z)?.let { if (it in meshed) mesh(it) }
        if (lx == size - 1) existingChunkAt(x + 1, y, z)?.let { if (it in meshed) mesh(it) }
        if (ly == 0) existingChunkAt(x, y - 1, z)?.let { if (it in meshed) mesh(it) }
        if (ly == size - 1) existingChunkAt(x, y + 1, z)?.let { if (it in meshed) mesh(it) }
        if (lz == 0) existingChunkAt(x, y, z - 1)?.let { if (it in meshed) mesh(it) }
        if (lz == size - 1) existingChunkAt(x, y, z + 1)?.let { if (it in meshed) mesh(it) }

        for (touched in light.takeDirty()) if (touched in meshed) mesh(touched)
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
            val chunk = chunks.getOrPut(pos) { Chunk(pos.first, pos.second, pos.third) }
            // Light before triangles, always. A mesh built from a Chunk whose light has not
            // been computed would upload a Chunk lit from nothing and draw it, and the only
            // symptom would be a patch of the world that is the wrong brightness for no
            // reason anyone could name.
            light.lightChunk(chunk)
            mesh(chunk)
            built++
        }

        // Light stepped over a border while filling, so some Chunks already meshed may now
        // hold triangles built from light that has since changed.
        for (touched in light.takeDirty()) if (touched in meshed) mesh(touched)
    }

    /** Meshes [chunk], replacing whatever it had on the GPU before. */
    fun mesh(chunk: Chunk) {
        // Release first. This frees the old mesh and drops the old vertex count, so it
        // has to happen before the new count is computed — getting that order wrong
        // zeroes every Chunk the instant it is built and the world disappears.
        chunk.releaseMesh()

        chunk.mesh = Geometry.upload(
            MeshGenerator.generateChunkMesh(chunk, this),
            Geometry.POS_UV_NORMAL_LIGHT
        )

        meshed.add(chunk)
    }

    /** Frees every live mesh, keeping the Cells. Called before the GL context dies. */
    fun releaseAll() {
        meshed.forEach { it.releaseMesh() }
        meshed.clear()
    }

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
