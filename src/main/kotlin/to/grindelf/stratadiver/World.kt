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
}

/** A tile's position in the atlas grid. */
data class TileRef(val tx: Int, val ty: Int)

// --- Block Definitions ---
enum class Face { TOP, BOTTOM, NORTH, SOUTH, WEST, EAST }

/** Every face of a block type draws the same tile. */
private fun allFaces(tile: TileRef) = Face.entries.associateWith { tile }

/**
 * A Block Type. `opaque` drives face culling: a face is drawn whenever its
 * neighbour is not opaque. AIR and LEAVES are both non-opaque, so faces against
 * air and against leaves are all kept.
 */
enum class BlockType(val id: Byte, val opaque: Boolean, private val faces: Map<Face, TileRef>) {
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
    LEAVES(6, false, allFaces(Atlas.LEAVES));

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

    val texX: Int get() = primary.tx
    val texY: Int get() = primary.ty

    companion object {
        fun getById(id: Byte) = entries.find { it.id == id } ?: AIR
    }
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
    }

    // 1D array to represent 3D space (x * size*size + y * size + z)
    private val blocks = ByteArray(SIZE * SIZE * SIZE)

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
        }
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

    fun generateChunkMesh(chunk: Chunk): FloatArray {
        val vertices = mutableListOf<Float>()
        val size = Chunk.SIZE

        for (x in 0 until size) {
            for (y in 0 until size) {
                for (z in 0 until size) {
                    val blockId = chunk.getBlock(x, y, z)
                    if (blockId == BlockType.AIR.id) continue

                    val blockType = BlockType.getById(blockId)
                    val gx = (chunk.xOffset * size + x).toFloat()
                    val gy = (chunk.yOffset * size + y).toFloat()
                    val gz = (chunk.zOffset * size + z).toFloat()

                    // --- FACE CULLING ---
                    // A face is drawn unless its neighbour hides it. AIR and
                    // LEAVES are both non-opaque, so faces against open air and
                    // against leaves are kept; faces buried in stone are not.
                    if (exposed(chunk, x, y + 1, z)) addFaceTop(vertices, gx, gy, gz, blockType)
                    if (exposed(chunk, x, y - 1, z)) addFaceBottom(vertices, gx, gy, gz, blockType)
                    if (exposed(chunk, x, y, z - 1)) addFaceNorth(vertices, gx, gy, gz, blockType)
                    if (exposed(chunk, x, y, z + 1)) addFaceSouth(vertices, gx, gy, gz, blockType)
                    if (exposed(chunk, x - 1, y, z)) addFaceWest(vertices, gx, gy, gz, blockType)
                    if (exposed(chunk, x + 1, y, z)) addFaceEast(vertices, gx, gy, gz, blockType)
                }
            }
        }
        return vertices.toFloatArray()
    }


    /** True when the neighbour at this position does not hide the adjoining face. */
    private fun exposed(chunk: Chunk, x: Int, y: Int, z: Int): Boolean {
        val neighbour = BlockType.getById(chunk.getBlock(x, y, z))
        return !neighbour.opaque
    }

    private fun getUVs(bt: BlockType, face: Face): FloatArray {
        val tile = bt.tile(face)
        val uMin = tile.tx / ATLAS_SIZE
        val vMin = tile.ty / ATLAS_SIZE
        val uMax = (tile.tx + 1) / ATLAS_SIZE
        val vMax = (tile.ty + 1) / ATLAS_SIZE
        return floatArrayOf(uMin, vMin, uMax, vMax)
    }

    private fun addVertex(list: MutableList<Float>, x: Float, y: Float, z: Float, u: Float, v: Float, nx: Float, ny: Float, nz: Float) {
        list.add(x); list.add(y); list.add(z) // Position (3 floats)
        list.add(u); list.add(v)             // UV (2 floats)
        list.add(nx); list.add(ny); list.add(nz) // Normals (3 floats)
    }

    private fun addFaceTop(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.TOP)
        val nx = 0f; val ny = 1f; val nz = 0f // Facing Up
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x, y + 1f, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, nx, ny, nz)
    }

    private fun addFaceBottom(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.BOTTOM)
        val nx = 0f; val ny = -1f; val nz = 0f // Facing Down
        addVertex(v, x, y, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x, y, z + 1f, uMin, vMax, nx, ny, nz)
        addVertex(v, x, y, z, uMin, vMin, nx, ny, nz)
    }

    private fun addFaceNorth(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.NORTH)
        val nx = 0f; val ny = 0f; val nz = -1f // Facing North (-Z)
        addVertex(v, x + 1f, y + 1f, z, uMin, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x, y, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x, y, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x, y + 1f, z, uMax, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z, uMin, vMax, nx, ny, nz)
    }

    private fun addFaceSouth(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.SOUTH)
        val nx = 0f; val ny = 0f; val nz = 1f // Facing South (+Z)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, nx, ny, nz)
        addVertex(v, x, y, z + 1f, uMin, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, nx, ny, nz)
    }

    private fun addFaceWest(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.WEST)
        val nx = -1f; val ny = 0f; val nz = 0f // Facing West (-X)
        addVertex(v, x, y + 1f, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x, y + 1f, z, uMin, vMax, nx, ny, nz)
        addVertex(v, x, y, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x, y, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x, y, z + 1f, uMax, vMin, nx, ny, nz)
        addVertex(v, x, y + 1f, z + 1f, uMax, vMax, nx, ny, nz)
    }

    private fun addFaceEast(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt, Face.EAST)
        val nx = 1f; val ny = 0f; val nz = 0f // Facing East (+X)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z + 1f, uMin, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMin, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMin, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMax, nx, ny, nz)
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
