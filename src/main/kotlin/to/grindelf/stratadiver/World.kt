package to.grindelf.stratadiver

import kotlin.math.floor


// --- Atlas Layout ---
// The atlas is a 4x4 grid of 64px tiles. `ty` is the row index in the uploaded
// buffer, which OpenGL reads as v=0 at the first row.
object Atlas {
    const val TILES = 4
    const val TILE_PX = 64
    const val ART_PX = 16
    const val UPSCALE = TILE_PX / ART_PX // 4

    // Edge-bleed guard ring, in upscaled pixels. Must stay well under one art pixel
    // (4px), or it would eat the outermost art row — which is exactly what a 1px
    // bevel or border on a UI tile is.
    const val PAD = 2

    // Block tiles
    val GRASS_TOP = TileRef(0, 0)
    val GRASS_SIDE = TileRef(1, 0)
    val DIRT = TileRef(2, 0)
    val STONE = TileRef(3, 0)
    val WOOD_SIDE = TileRef(0, 1)
    val WOOD_TOP = TileRef(1, 1)
    val LEAVES = TileRef(2, 1)

    // UI tiles
    val SLOT = TileRef(3, 1)
    val SLOT_SELECTED = TileRef(0, 2)
    val BEDROCK = TileRef(1, 2)
    val PANEL = TileRef(2, 2)
    val CROSSHAIR = TileRef(3, 2)

    // Solid opaque white, for wireframe overlays that must not be discarded.
    val LINE = TileRef(0, 3)

    /**
     * The one emitting Tile. Last free slot in a 4x4 atlas: the next Block Type needing
     * art has to either take [LINE]'s row or the atlas grows to 8x8.
     */
    val GLOWSTONE = TileRef(1, 3)
}

/** A tile's position in the atlas grid. */
data class TileRef(val tx: Int, val ty: Int)

// --- Block Definitions ---
enum class Face { TOP, BOTTOM, NORTH, SOUTH, WEST, EAST }

/**
 * What a Block Type is made of. Decides how it Sounds, and nothing else about it — a
 * Material is not a Tile, and it is not a Block Type. See CONTEXT.md.
 */
enum class Material { EARTH, STONE, WOOD, FOLIAGE }

/** Every face of a block type draws the same tile. */
private fun allFaces(tile: TileRef) = Face.entries.associateWith { tile }

/**
 * How a Block Type behaves towards light.
 *
 * `emission` is the Daylight-or-Torchlight level the Block Type puts into its own Cell and
 * from there into the world; zero means it is a passive recipient. `attenuation` is how
 * much light a Block Type takes away *on top of* the one level every single step costs,
 * for light passing through it. A leaf costs one step plus one, so a forest floor is
 * dimmer than a clearing; a pane of glass would cost one step and nothing more.
 *
 * Only consulted for Block Types that are not [opaque]. An opaque Block Type blocks light
 * outright and has no attenuation value, because there is no light on the far side to
 * attenuate. That is a deliberate coupling rather than an oversight: every opaque Block
 * Type in this world happens to be a solid the sun cannot reach through, and inventing a
 * third property to say so independently would be a field nothing could yet disagree about.
 */
enum class BlockType(
    val id: Byte,
    val opaque: Boolean,
    private val faces: Map<Face, TileRef>,
    val emission: Int = 0,
    val attenuation: Int = 0
) {
    AIR(0, false, emptyMap()),
    GRASS(1, true, mapOf(
        Face.TOP to Atlas.GRASS_TOP,
        Face.BOTTOM to Atlas.DIRT,
        Face.NORTH to Atlas.GRASS_SIDE, Face.SOUTH to Atlas.GRASS_SIDE,
        Face.WEST to Atlas.GRASS_SIDE, Face.EAST to Atlas.GRASS_SIDE
    )),
    DIRT(2, true, allFaces(Atlas.DIRT)),
    STONE(3, true, allFaces(Atlas.STONE)),
    BEDROCK(4, true, allFaces(Atlas.BEDROCK)),
    WOOD(5, true, mapOf(
        Face.TOP to Atlas.WOOD_TOP,
        Face.BOTTOM to Atlas.WOOD_TOP,
        Face.NORTH to Atlas.WOOD_SIDE, Face.SOUTH to Atlas.WOOD_SIDE,
        Face.WEST to Atlas.WOOD_SIDE, Face.EAST to Atlas.WOOD_SIDE
    )),
    LEAVES(6, false, allFaces(Atlas.LEAVES), attenuation = 1),

    /**
     * Emits light, and is the reason the light engine has two channels.
     *
     * A full Cube rather than a Torch on a stem: a Block Type here occupies a whole Cell,
     * and teaching the mesher about half-Cell shapes is a separate piece of work from
     * teaching the world about light. GLOWSTONE is a real thing in the game this is
     * modelled on and it needs no new geometry, so it carries the feature for now and a
     * Torch can arrive later as a shape.
     */
    GLOWSTONE(7, true, allFaces(Atlas.GLOWSTONE), emission = 14);

    /** The tile this Block Type draws on the given face. */
    fun tile(face: Face): TileRef = faces[face] ?: faces.values.first()

    /** Side tile, used for Icons and dropped items. */
    val primary: TileRef get() = tile(Face.NORTH)

    /**
     * What this Block Type Drops when destroyed. Grass Drops Dirt, because a grass block
     * is a thin soil layer over dirt and the grass itself is not collected. Everything
     * else Drops itself; LEAVES Drops AIR, meaning nothing at all.
     */
    val drop: BlockType
        get() = when (this) {
            GRASS -> DIRT
            LEAVES -> AIR
            else -> this
        }

    /**
     * What this Block Type is made of, which decides how it Sounds. Null for AIR, which
     * has no substance and so cannot be walked on, struck, or set down.
     *
     * Many Block Types share one Material: grass and dirt are both earth, stone and
     * bedrock both stone. Keeping that grouping here — rather than giving every Block
     * Type its own Sound — is why adding a new Block Type is a one-line change and not a
     * new sound to design.
     */
    val material: Material?
        get() = when (this) {
            GRASS, DIRT -> Material.EARTH
            STONE, BEDROCK, GLOWSTONE -> Material.STONE
            WOOD -> Material.WOOD
            LEAVES -> Material.FOLIAGE
            AIR -> null
        }

    val texX: Int get() = primary.tx
    val texY: Int get() = primary.ty

    companion object {
        fun getById(id: Byte) = entries.find { it.id == id } ?: AIR
    }
}

// --- Light ---

/**
 * A Cell's light: two independent levels, Daylight and Torchlight, each 0..[MAX].
 *
 * Packed into a single byte per Cell — Torchlight in the low nibble, Daylight in the high
 * one — rather than kept as two arrays, because they are always read and written together
 * and because a Chunk that holds its light in a second 4096-byte array holds twice the
 * memory for the same information. Every read and write goes through here rather than
 * touching the raw byte, because the alternative is a `BlockType.getById` that silently
 * answers AIR for an id with light in its high bits, and that failure looks exactly like
 * missing terrain.
 *
 * The two are kept separate because they answer different questions and fade differently:
 * Daylight comes down a Column from the sky and does not travel, Torchlight travels out
 * from an emitter and stops. Merging them into one "brightness" would mean a torch in a
 * sealed room was brighter than the surface it is standing next to.
 */
object Light {
    /** Full brightness. Light loses one level per Cell travelled and nothing reaches [MAX]. */
    const val MAX = 15

    const val TORCH_SHIFT = 0
    const val DAY_SHIFT = 4

    fun torch(packed: Int): Int = (packed shr TORCH_SHIFT) and 0x0F
    fun day(packed: Int): Int = (packed shr DAY_SHIFT) and 0x0F

    fun pack(day: Int, torch: Int): Int =
        ((day and 0x0F) shl DAY_SHIFT) or (torch and 0x0F)

    const val DARK = 0
}

// --- Deterministic World Generation ---
//
// Terrain has always been reproducible: Perlin noise is a pure function of world
// position. Trees were not — they drew from Math.random(), so the same patch of
// ground grew a different forest every time its Chunk was constructed. Chunks are
// now released when the player walks away and rebuilt on return, which turns that
// into a visible bug: the world would rearrange itself behind you, and a tree
// could sprout in the middle of a house you built. Everything a Chunk's generation
// needs to decide is therefore derived from a hash of its own position.
object WorldGen {

    /**
     * A seeded xorshift32. Only used during generation, so it never needs to be
     * cryptographically anything — it just has to give the same sequence for the
     * same seed, every time, forever.
     */
    class Random(seed: Int) {
        private var state: Int = if (seed == 0) 0x9E3779B9.toInt() else seed

        private fun next(): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state
        }

        /** Uniform in [0, bound). Uses the high bits, which xorshift fills last. */
        fun nextInt(bound: Int): Int =
            if (bound <= 0) 0 else ((next().toLong() and 0xFFFFFFFFL) % bound).toInt()

        /** Uniform in [0, 1). */
        fun nextFloat(): Float = (next() ushr 8) / 16777216f

        /** True with probability [p]. */
        fun chance(p: Float): Boolean = nextFloat() < p
    }

    /** A seed derived from a Chunk's grid position. */
    fun seedFor(cx: Int, cy: Int, cz: Int): Int {
        // An integer avalanche mix. Kotlin Int arithmetic wraps, which is the point.
        var h = cx * -1640531527
        h = h xor (cy * -2048144789)
        h = h xor (cz * 1013904223)
        h = h xor (h ushr 16)
        h *= -2048144789
        h = h xor (h ushr 13)
        h *= -1028477387
        return h xor (h ushr 16)
    }
}


// --- The Chunk container ---
class Chunk(val xOffset: Int, val yOffset: Int, val zOffset: Int) {
    companion object {
        const val SIZE = 16

        /** How many Chunks the world stacks vertically, from Y=0 to Y=256. */
        const val HEIGHT_LAYERS = 16

        // Shortest trunk worth growing: below this a tree reads as a stump.
        const val MIN_TREE_HEIGHT = 3

        /**
         * How likely a Chunk is to hold a GLOWSTONE vein at all, and how many Cells one
         * runs to. Rare on purpose: it is the only light source in the game, so a world
         * thick with them would make the Torchlight channel decoration rather than a
         * thing you dig for.
         */
        const val GLOWSTONE_VEIN_CHANCE = 0.15f
        const val GLOWSTONE_VEIN_MIN = 3
        const val GLOWSTONE_VEIN_MAX = 7

        /** Sentinel in the Surface cache for a column that is entirely air. */
        private const val NO_SURFACE = -1
    }

    // 1D array to represent 3D space (x * size*size + y * size + z)
    private val blocks = ByteArray(SIZE * SIZE * SIZE)

    /**
     * This Chunk's light, one packed byte per Cell. See [Light].
     *
     * Lives here rather than in the light engine because light is a property of a Cell and
     * has to outlive the Chunk's triangles: `chunks` is never discarded, so a room you lit
     * is still lit when you walk away and come back, without being recomputed to be so.
     */
    private val lightBytes = ByteArray(SIZE * SIZE * SIZE)

    /** This Chunk's place in the world grid. Identity of the *place*, not of the mesh. */
    val position: Triple<Int, Int, Int> = Triple(xOffset, yOffset, zOffset)

    /**
     * Its triangles, while it has any. Null means not meshed — or meshed and correctly
     * empty, since an all-air Chunk has no geometry worth a GPU object. The draw loop
     * skips a null mesh, which is why most of the world costs nothing to have.
     *
     * No GL happens in this class's constructor, so a Chunk can still be generated and
     * inspected without a context.
     */
    var mesh: Mesh? = null

    /**
     * Per-column topmost non-air local Y, computed on demand and dropped whenever a Cell
     * changes. Deliberately not persisted: a Column's Surface is derived state, and the
     * only thing that can invalidate it is a write this class already sees.
     */
    private var topmostSolidCache: IntArray? = null

    init {
        val seaLevel = 128
        var surfaceLocalY = -1 // Track if the surface is IN THIS chunk

        for (x in 0 until SIZE) {
            for (z in 0 until SIZE) {
                val worldX = (this.xOffset * SIZE + x).toDouble()
                val worldZ = (this.zOffset * SIZE + z).toDouble()

                val noise1 = PerlinNoise.noise(worldX * 0.02, 0.0, worldZ * 0.02) * 20.0
                val noise2 = PerlinNoise.noise(worldX * 0.1, 11.0, worldZ * 0.1) * 5.0
                val terrainHeight = (seaLevel + noise1 + noise2).toInt()

                // Check if this specific column's surface is inside this chunk
                if (x == 8 && z == 8) {
                    // Convert world terrain height to local chunk Y
                    val localY = terrainHeight - (this.yOffset * SIZE)
                    if (localY in 0 until SIZE) {
                        surfaceLocalY = localY
                    }
                }

                for (y in 0 until SIZE) {
                    val worldY = (this.yOffset * SIZE + y)
                    val block = when {
                        worldY == 0 -> BlockType.BEDROCK
                        worldY > terrainHeight -> BlockType.AIR
                        worldY == terrainHeight -> BlockType.GRASS
                        worldY > terrainHeight - 3 -> BlockType.DIRT
                        else -> BlockType.STONE
                    }
                    setBlock(x, y, z, block)
                }
            }
        }

        // Seeded from this Chunk's own position, so the forest here is the same
        // forest every time the player walks back into it.
        val random = WorldGen.Random(WorldGen.seedFor(xOffset, yOffset, zOffset))
        if (surfaceLocalY != -1 && random.chance(0.6f)) {
            addSimpleTree(8, surfaceLocalY + 1, 8, random)
        }
        if (random.chance(GLOWSTONE_VEIN_CHANCE)) addGlowstoneVein(random)
    }

    /**
     * A small blob of GLOWSTONE buried in the stone.
     *
     * Placed only where there is already stone to replace, so a vein never floats in a
     * cave the generator did not make and never pokes into a hillside as a glowing
     * outcrop. Drawn from this Chunk's seeded [random], like the trees, because a Chunk is
     * released and regenerated as the player walks and anything drawn from a shared
     * stream would rearrange the world behind them.
     */
    private fun addGlowstoneVein(random: WorldGen.Random) {
        val wanted = GLOWSTONE_VEIN_MIN + random.nextInt(GLOWSTONE_VEIN_MAX - GLOWSTONE_VEIN_MIN + 1)
        var placed = 0
        // Bounded attempts rather than an unbounded search: a Chunk above the terrain is
        // all air and holds no stone at all, and must give up rather than spin.
        var attempts = 0
        while (placed < wanted && attempts < 64) {
            attempts++
            val x = random.nextInt(SIZE)
            val y = random.nextInt(SIZE)
            val z = random.nextInt(SIZE)
            if (getBlock(x, y, z) != BlockType.STONE.id) continue
            setBlock(x, y, z, BlockType.GLOWSTONE)
            placed++
        }
    }

    fun getBlock(x: Int, y: Int, z: Int): Byte {
        // If outside the bounds of this chunk, return AIR
        if (x !in 0 until SIZE || y !in 0 until SIZE || z !in 0 until SIZE) {
            return BlockType.AIR.id
        }
        return blocks[x * SIZE * SIZE + y * SIZE + z]
    }

    fun setBlock(x: Int, y: Int, z: Int, type: BlockType) {
        if (x in 0 until SIZE && y in 0 until SIZE && z in 0 until SIZE) {
            blocks[x * SIZE * SIZE + y * SIZE + z] = type.id
            // The cached Surface for this column is now a lie. Invalidating here rather
            // than in the Minimap is what makes "build something, see it on the map" a
            // property of the data instead of a thing the map has to remember to do.
            topmostSolidCache = null
            // And so is this Cell's light, for the same reason and with more force: a
            // wall you have just built has to stop letting light through, and a hole you
            // have just dug has to start. Whether the levels around it are right is the
            // light engine's business; what is certain here is that *these two numbers*
            // describe a world that no longer exists.
            lightBytes[x * SIZE * SIZE + y * SIZE + z] = Light.DARK.toByte()
        }
    }

    /**
     * This Cell's packed light, or [Light.DARK] outside this Chunk.
     *
     * Answers dark rather than throwing past its own bounds, deliberately the same choice
     * [getBlock] makes with AIR: both are reached from a flood fill that walks in world
     * coordinates, and a boundary mistake should cost one Cell of light rather than the
     * frame. The engine's own arithmetic is checked separately for exactly this.
     */
    fun packedLightAt(x: Int, y: Int, z: Int): Int {
        if (x !in 0 until SIZE || y !in 0 until SIZE || z !in 0 until SIZE) return Light.DARK
        return lightBytes[x * SIZE * SIZE + y * SIZE + z].toInt() and 0xFF
    }

    /** Writes this Cell's packed light. Outside this Chunk, nothing happens. */
    fun setPackedLight(x: Int, y: Int, z: Int, packed: Int) {
        if (x in 0 until SIZE && y in 0 until SIZE && z in 0 until SIZE) {
            lightBytes[x * SIZE * SIZE + y * SIZE + z] = packed.toByte()
        }
    }

    /**
     * Puts the whole Chunk back to dark.
     *
     * A whole-array fill rather than a per-Cell loop because it is exactly what the light
     * engine does before re-deriving a Chunk, and doing it through [setBlock] would mean
     * 4096 bounds checks and 4096 cache-hostile writes to make a change that is one memset.
     */
    fun clearAllLight() {
        lightBytes.fill(Light.DARK.toByte())
    }

    /**
     * The topmost non-air Cell in this column, by local Y, or null if the column is
     * entirely air.
     *
     * Computed once and cached, because scanning it is what the Minimap does 4096 times
     * per refresh. Invalidated by [setBlock] and by nothing else, which is the whole
     * reason the Minimap can trust it.
     */
    fun topmostSolid(lx: Int, lz: Int): Int? {
        if (lx !in 0 until SIZE || lz !in 0 until SIZE) return null
        var cache = topmostSolidCache
        if (cache == null) {
            cache = IntArray(SIZE * SIZE) { NO_SURFACE }
            for (z in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    // Downward from this chunk's ceiling; the first non-air Cell wins.
                    for (y in SIZE - 1 downTo 0) {
                        if (blocks[x * SIZE * SIZE + y * SIZE + z] != BlockType.AIR.id) {
                            cache[z * SIZE + x] = y
                            break
                        }
                    }
                }
            }
            topmostSolidCache = cache
        }
        val y = cache[lz * SIZE + lx]
        return if (y == NO_SURFACE) null else y
    }


    private fun addSimpleTree(tx: Int, ty: Int, tz: Int, random: WorldGen.Random) {
        // How many blocks of vertical room the tree can actually use in this chunk.
        // Growing into the top edge would produce a flat-topped trunk that looks broken
        // from above, so a tree that cannot fit whole is not grown at all.
        val headroom = SIZE - ty
        if (headroom < MIN_TREE_HEIGHT) return

        // Trunk: 4 or 5 blocks, but never more than the chunk has room for, leaving
        // space above the trunk for the canopy cap.
        val trunkHeight = minOf(4 + random.nextInt(2), headroom - 2)
        if (trunkHeight < MIN_TREE_HEIGHT) return

        for (i in 0..trunkHeight) {
            setBlock(tx, ty + i, tz, BlockType.WOOD)
        }

        // Oak canopy: two wide layers over the trunk top, capped by a narrow one.
        // Corners are trimmed at random, which is what stops it reading as a box.
        val top = ty + trunkHeight
        for (ly in (top - 2)..(top + 1)) {
            val radius = if (ly == top + 1) 1 else 2
            for (lx in -radius..radius) {
                for (lz in -radius..radius) {
                    val distSq = lx * lx + lz * lz
                    if (distSq > radius * radius) continue
                    // Trim the outermost ring's corners.
                    if (distSq >= radius * radius && random.chance(0.6f)) continue

                    val bx = tx + lx; val by = ly; val bz = tz + lz
                    if (bx !in 0 until SIZE || by !in 0 until SIZE || bz !in 0 until SIZE) continue
                    if (getBlock(bx, by, bz) != BlockType.AIR.id) continue
                    setBlock(bx, by, bz, BlockType.LEAVES)
                }
            }
        }

        // Cap the trunk. A bare trunk top is visible when you look down on the tree, so
        // the very last trunk block always gets leaves directly above it.
        val capY = ty + trunkHeight + 1
        if (capY < SIZE) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val bx = tx + dx; val bz = tz + dz
                    if (bx !in 0 until SIZE || bz !in 0 until SIZE) continue
                    if (getBlock(bx, capY, bz) == BlockType.AIR.id) {
                        setBlock(bx, capY, bz, BlockType.LEAVES)
                    }
                }
            }
        }
    }

    /**
     * Frees the GPU mesh and keeps the block data.
     *
     * This split is the whole trick behind the streaming: the Cells — and anything the
     * player built in them — outlive their triangles, so walking away discards only what
     * can be cheaply rebuilt. The Chunk becomes an ordinary resident of the world again
     * until something wants it drawn.
     */
    fun releaseMesh() {
        mesh?.release()
        mesh = null
    }
}


// --- The Face Culling Mesh Generator ---
object MeshGenerator {

    private const val ATLAS_SIZE = 4.0f // 4x4 tiles in our generated texture

    // Which axis of a Cell a position is measured along.
    private const val AXIS_X = 0
    private const val AXIS_Y = 1
    private const val AXIS_Z = 2

    // Base offsets into a face's shading block. Each corner occupies three floats —
    // shade, daylight, torchlight — so a corner's data starts at a multiple of three, and
    // naming those offsets beats writing `s[9], s[10], s[11]` and hoping.
    private const val CORNER_00 = 0   // (-1, -1) along the face's two axes
    private const val CORNER_10 = 3   // (+1, -1)
    private const val CORNER_11 = 6   // (+1, +1)
    private const val CORNER_01 = 9   // (-1, +1)

    /** Light divided up by [Light.MAX], so the shader does not repeat the division. */
    private const val LIGHT_SCALE = 1f / Light.MAX

    /**
     * The triangles of [chunk], culling every face whose neighbour hides it.
     *
     * [world] is required rather than optional because a Chunk cannot answer the only
     * question that matters at its own border. `Chunk.getBlock` returns AIR outside its own
     * 16x16x16, so asking it about the next Chunk's Cells answered "exposed" for every
     * boundary face in the world and the mesher drew faces buried inside solid rock.
     * Reading across the boundary is also where light comes from: a vertex on a face wants
     * the light of the Cells around it, and the Cells around it are frequently in a
     * different Chunk.
     *
     * Every vertex carries the light and corner occlusion of its own position, not the
     * light of the Cell its face belongs to. That is what makes the corner where two walls
     * meet darker than the middle of either wall, and it is most of what stops a block
     * reading as a flat sticker.
     */
    fun generateChunkMesh(chunk: Chunk, world: LitWorld): FloatArray {
        val vertices = mutableListOf<Float>()
        val size = Chunk.SIZE

        for (x in 0 until size) {
            for (y in 0 until size) {
                for (z in 0 until size) {
                    val blockId = chunk.getBlock(x, y, z)
                    if (blockId == BlockType.AIR.id) continue

                    val blockType = BlockType.getById(blockId)
                    val gx = (chunk.xOffset * size + x).toInt()
                    val gy = (chunk.yOffset * size + y).toInt()
                    val gz = (chunk.zOffset * size + z).toInt()
                    val fx = gx.toFloat()
                    val fy = gy.toFloat()
                    val fz = gz.toFloat()

                    // --- FACE CULLING ---
                    // A face is drawn unless its neighbour hides it. AIR and LEAVES are both
                    // non-opaque, so faces against open air and against leaves are kept;
                    // faces buried in stone are not. Asked in world coordinates, so the
                    // neighbour of a Cell on a Chunk's border is read from the Chunk it
                    // actually lives in.
                    if (exposed(world, gx, gy + 1, gz)) addFaceTop(vertices, world, fx, fy, fz, gx, gy + 1, gz, blockType)
                    if (exposed(world, gx, gy - 1, gz)) addFaceBottom(vertices, world, fx, fy, fz, gx, gy - 1, gz, blockType)
                    if (exposed(world, gx, gy, gz - 1)) addFaceNorth(vertices, world, fx, fy, fz, gx, gy, gz - 1, blockType)
                    if (exposed(world, gx, gy, gz + 1)) addFaceSouth(vertices, world, fx, fy, fz, gx, gy, gz + 1, blockType)
                    if (exposed(world, gx - 1, gy, gz)) addFaceWest(vertices, world, fx, fy, fz, gx - 1, gy, gz, blockType)
                    if (exposed(world, gx + 1, gy, gz)) addFaceEast(vertices, world, fx, fy, fz, gx + 1, gy, gz, blockType)
                }
            }
        }
        return vertices.toFloatArray()
    }

    /**
     * True when the neighbour at this position does not hide the adjoining face.
     *
     * A neighbour the world has not produced counts as exposed, so the frontier is drawn
     * rather than the player looking at a hole in the world. See [Neighbourhood] for why
     * that is the opposite of what Light wants from the same question.
     */
    private fun exposed(n: Neighbourhood, x: Int, y: Int, z: Int): Boolean =
        n.blockAtOrNull(x, y, z)?.opaque != true

    /** Whether this Cell would stand in a vertex's way. A Cell the world lacks does not. */
    private fun occludes(n: Neighbourhood, cell: IntArray): Boolean =
        n.blockAtOrNull(cell[0], cell[1], cell[2])?.opaque == true

    /**
     * Four corners' worth of shading for one face, into [into] as twelve floats.
     *
     * [base] is the Cell in front of the face, which is by definition not Opaque or the
     * face would not have been drawn. [bAxis] and [cAxis] are the two axes the face lies
     * in. Corners are ordered (-1,-1), (+1,-1), (+1,+1), (-1,+1) along those axes.
     *
     * The scratch arrays are reused across all four corners and allocated per face, because
     * a face is 6 vertices and the arithmetic is not worth six temporary objects per
     * corner.
     */
    private fun faceShading(
        world: LitWorld, base: IntArray, bAxis: Int, cAxis: Int, into: FloatArray
    ) {
        val side1 = IntArray(3)
        val side2 = IntArray(3)
        val corner = IntArray(3)

        for (index in 0 until 4) {
            val ob = if (index == 0 || index == 3) -1 else 1
            val oc = if (index < 2) -1 else 1

            for (a in 0..2) {
                side1[a] = base[a]; side2[a] = base[a]; corner[a] = base[a]
            }
            side1[bAxis] += ob
            side2[cAxis] += oc
            corner[bAxis] += ob
            corner[cAxis] += oc

            // --- Occlusion ---
            // A corner touching two solid sides is hidden however open the diagonal is,
            // which is why this tests both first rather than summing. Summing lets one
            // open diagonal cancel a solid side and leaves a bright seam running down the
            // corner of every wall in the world.
            val s1 = if (occludes(world, side1)) 1 else 0
            val s2 = if (occludes(world, side2)) 1 else 0
            val co = if (occludes(world, corner)) 1 else 0
            val shade = if (s1 == 1 && s2 == 1) 0 else 3 - (s1 + s2 + co)

            // --- Light ---
            // Averaged over the open Cells this corner touches on the near side, which is
            // what makes brightness run smoothly out of a doorway instead of stepping once
            // and then staying flat. Opaque Cells are skipped rather than counted as dark:
            // a corner tucked against a wall should be as bright as the room it is in, not
            // as dark as the wall.
            var day = 0
            var torch = 0
            var open = 0

            val inFront = world.lightAt(base[0], base[1], base[2])
            day += Light.day(inFront); torch += Light.torch(inFront); open++

            if (s1 == 0) {
                val l = world.lightAt(side1[0], side1[1], side1[2])
                day += Light.day(l); torch += Light.torch(l); open++
            }
            if (s2 == 0) {
                val l = world.lightAt(side2[0], side2[1], side2[2])
                day += Light.day(l); torch += Light.torch(l); open++
            }
            if (co == 0) {
                val l = world.lightAt(corner[0], corner[1], corner[2])
                day += Light.day(l); torch += Light.torch(l); open++
            }

            into[index * 3] = shade.toFloat()
            into[index * 3 + 1] = day * LIGHT_SCALE / open
            into[index * 3 + 2] = torch * LIGHT_SCALE / open
        }
    }

    private fun getUVs(bt: BlockType, face: Face): FloatArray {
        val tile = bt.tile(face)
        val uMin = tile.tx / ATLAS_SIZE
        val vMin = tile.ty / ATLAS_SIZE
        val uMax = (tile.tx + 1) / ATLAS_SIZE
        val vMax = (tile.ty + 1) / ATLAS_SIZE
        return floatArrayOf(uMin, vMin, uMax, vMax)
    }

    /** Appends one vertex: position, UV, normal, light, shade. */
    private fun addVertex(
        list: MutableList<Float>, x: Float, y: Float, z: Float,
        u: Float, v: Float, nx: Float, ny: Float, nz: Float,
        s: FloatArray, corner: Int
    ) {
        list.add(x); list.add(y); list.add(z)        // Position (3 floats)
        list.add(u); list.add(v)                     // UV (2 floats)
        list.add(nx); list.add(ny); list.add(nz)      // Normal (3 floats)
        list.add(s[corner + 1]); list.add(s[corner + 2])  // Daylight, Torchlight (2 floats)
        list.add(s[corner])                         // Corner occlusion (1 float)
    }

    private fun addFaceTop(v: MutableList<Float>, w: LitWorld, x: Float, y: Float, z: Float, nx: Int, ny: Int, nz: Int, bt: BlockType) {
        val s = shadingFor(w, nx, ny, nz, AXIS_X, AXIS_Z)
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.TOP)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, 0f, 1f, 0f, s, CORNER_01)
        addVertex(v, x + 1f, y + 1f, z + 1f, uMax, vMax, 0f, 1f, 0f, s, CORNER_11)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMin, 0f, 1f, 0f, s, CORNER_10)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMin, 0f, 1f, 0f, s, CORNER_10)
        addVertex(v, x, y + 1f, z, uMin, vMin, 0f, 1f, 0f, s, CORNER_00)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, 0f, 1f, 0f, s, CORNER_01)
    }

    private fun addFaceBottom(v: MutableList<Float>, w: LitWorld, x: Float, y: Float, z: Float, nx: Int, ny: Int, nz: Int, bt: BlockType) {
        val s = shadingFor(w, nx, ny, nz, AXIS_X, AXIS_Z)
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.BOTTOM)
        addVertex(v, x, y, z, uMin, vMin, 0f, -1f, 0f, s, CORNER_00)
        addVertex(v, x + 1f, y, z, uMax, vMin, 0f, -1f, 0f, s, CORNER_10)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMax, 0f, -1f, 0f, s, CORNER_11)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMax, 0f, -1f, 0f, s, CORNER_11)
        addVertex(v, x, y, z + 1f, uMin, vMax, 0f, -1f, 0f, s, CORNER_01)
        addVertex(v, x, y, z, uMin, vMin, 0f, -1f, 0f, s, CORNER_00)
    }

    private fun addFaceNorth(v: MutableList<Float>, w: LitWorld, x: Float, y: Float, z: Float, nx: Int, ny: Int, nz: Int, bt: BlockType) {
        val s = shadingFor(w, nx, ny, nz, AXIS_X, AXIS_Y)
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.NORTH)
        addVertex(v, x + 1f, y + 1f, z, uMin, vMax, 0f, 0f, -1f, s, CORNER_11)
        addVertex(v, x + 1f, y, z, uMin, vMin, 0f, 0f, -1f, s, CORNER_10)
        addVertex(v, x, y, z, uMax, vMin, 0f, 0f, -1f, s, CORNER_00)
        addVertex(v, x, y, z, uMax, vMin, 0f, 0f, -1f, s, CORNER_00)
        addVertex(v, x, y + 1f, z, uMax, vMax, 0f, 0f, -1f, s, CORNER_01)
        addVertex(v, x + 1f, y + 1f, z, uMin, vMax, 0f, 0f, -1f, s, CORNER_11)
    }

    private fun addFaceSouth(v: MutableList<Float>, w: LitWorld, x: Float, y: Float, z: Float, nx: Int, ny: Int, nz: Int, bt: BlockType) {
        val s = shadingFor(w, nx, ny, nz, AXIS_X, AXIS_Y)
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.SOUTH)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, 0f, 0f, 1f, s, CORNER_01)
        addVertex(v, x, y, z + 1f, uMin, vMin, 0f, 0f, 1f, s, CORNER_00)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMin, 0f, 0f, 1f, s, CORNER_10)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMin, 0f, 0f, 1f, s, CORNER_10)
        addVertex(v, x + 1f, y + 1f, z + 1f, uMax, vMax, 0f, 0f, 1f, s, CORNER_11)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, 0f, 0f, 1f, s, CORNER_01)
    }

    private fun addFaceWest(v: MutableList<Float>, w: LitWorld, x: Float, y: Float, z: Float, nx: Int, ny: Int, nz: Int, bt: BlockType) {
        val s = shadingFor(w, nx, ny, nz, AXIS_Z, AXIS_Y)
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.WEST)
        addVertex(v, x, y + 1f, z + 1f, uMax, vMax, -1f, 0f, 0f, s, CORNER_11)
        addVertex(v, x, y + 1f, z, uMin, vMax, -1f, 0f, 0f, s, CORNER_01)
        addVertex(v, x, y, z, uMin, vMin, -1f, 0f, 0f, s, CORNER_00)
        addVertex(v, x, y, z, uMin, vMin, -1f, 0f, 0f, s, CORNER_00)
        addVertex(v, x, y, z + 1f, uMax, vMin, -1f, 0f, 0f, s, CORNER_10)
        addVertex(v, x, y + 1f, z + 1f, uMax, vMax, -1f, 0f, 0f, s, CORNER_11)
    }

    private fun addFaceEast(v: MutableList<Float>, w: LitWorld, x: Float, y: Float, z: Float, nx: Int, ny: Int, nz: Int, bt: BlockType) {
        val s = shadingFor(w, nx, ny, nz, AXIS_Z, AXIS_Y)
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.EAST)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMax, 1f, 0f, 0f, s, CORNER_01)
        addVertex(v, x + 1f, y + 1f, z + 1f, uMin, vMax, 1f, 0f, 0f, s, CORNER_11)
        addVertex(v, x + 1f, y, z + 1f, uMin, vMin, 1f, 0f, 0f, s, CORNER_10)
        addVertex(v, x + 1f, y, z, uMax, vMin, 1f, 0f, 0f, s, CORNER_00)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMax, 1f, 0f, 0f, s, CORNER_01)
        addVertex(v, x + 1f, y + 1f, z + 1f, uMin, vMax, 1f, 0f, 0f, s, CORNER_11)
    }

    /**
     * The shading block for one face, at the Cell in front of it.
     *
     * Twelve floats per face, and a fresh array each time, because a face's six vertices
     * are appended to a list that is already the expensive part of meshing and reusing a
     * shared buffer here would only save an allocation against that.
     */
    private fun shadingFor(
        world: LitWorld, nx: Int, ny: Int, nz: Int, bAxis: Int, cAxis: Int
    ): FloatArray {
        val out = FloatArray(12)
        faceShading(world, intArrayOf(nx, ny, nz), bAxis, cAxis, out)
        return out
    }
}

object FastNoise {
    fun getNoise(x: Int, z: Int): Float {
        // A very simple pseudo-random noise function for demo purposes
        // In a real project, you'd use a dedicated library like JoNoise or Simplex
        val n = x * 45 + z * 13
        val res = (n shl 13) xor n
        return (1.0f - ((res * (res * res * 15731 + 789221) + 1376312589) and 0x7fffffff).toFloat() / 1073741824.0f)
    }

    fun getSmoothNoise(x: Float, z: Float): Float {
        // Simple bilinear interpolation between noise points
        val xi = floor(x).toInt()
        val zi = floor(z).toInt()
        val xf = x - xi
        val zf = z - zi

        val v1 = getNoise(xi, zi)
        val v2 = getNoise(xi + 1, zi)
        val v3 = getNoise(xi, zi + 1)
        val v4 = getNoise(xi + 1, zi + 1)

        val i1 = v1 * (1 - xf) + v2 * xf
        val i2 = v3 * (1 - xf) + v4 * xf
        return i1 * (1 - zf) + i2 * zf
    }
}


object PerlinNoise {
    private val P = IntArray(512)
    private val PERMUTATION = intArrayOf(
        151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,8,99,37,240,21,10,23,
        190,6,148,247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,125,
        136,171,168,68,175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,122,60,211,133,230,220,105,92,41,55,
        46,245,40,244,102,143,54,65,25,63,161,1,216,80,73,209,76,132,187,208,89,18,169,200,196,135,130,116,188,159,
        86,164,100,109,198,173,186,3,64,52,217,226,250,124,123,5,202,38,147,118,126,255,82,85,212,207,206,59,227,
        47,16,58,17,182,189,28,42,223,183,170,213,119,248,152,2,44,154,163,70,221,153,101,155,167,43,172,9,
        129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,218,246,97,228,251,34,242,193,238,210,144,12,
        191,179,162,241,81,51,145,235,249,14,239,107,49,192,214,31,181,199,106,157,184,84,204,176,115,121,50,45,
        127,4,150,254,138,236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180
    )

    init {
        for (i in 0..255) P[i] = PERMUTATION[i].also { P[i + 256] = it }
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)
    private fun grad(hash: Int, x: Double, y: Double, z: Double): Double {
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if (h and 1 != 0) -u else u) + (if (h and 2 != 0) -v else v)
    }

    fun noise(x: Double, y: Double, z: Double): Double {
        val xi = Math.floor(x).toInt() and 255
        val yi = Math.floor(y).toInt() and 255
        val zi = Math.floor(z).toInt() and 255
        val xf = x - Math.floor(x)
        val yf = y - Math.floor(y)
        val zf = z - Math.floor(z)
        val u = fade(xf); val v = fade(yf); val w = fade(zf)

        val a = P[xi] + yi; val aa = P[a] + zi; val ab = P[a + 1] + zi
        val b = P[xi + 1] + yi; val ba = P[b] + zi; val bb = P[b + 1] + zi

        return lerp(w, lerp(v, lerp(u, grad(P[aa], xf, yf, zf), grad(P[ba], xf - 1, yf, zf)),
            lerp(u, grad(P[ab], xf, yf - 1, zf), grad(P[bb], xf - 1, yf - 1, zf))),
            lerp(v, lerp(u, grad(P[aa + 1], xf, yf, zf - 1), grad(P[ba + 1], xf - 1, yf, zf - 1)),
                lerp(u, grad(P[ab + 1], xf, yf - 1, zf - 1), grad(P[bb + 1], xf - 1, yf - 1, zf - 1))))
    }
}
