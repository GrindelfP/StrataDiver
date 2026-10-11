package to.grindelf.stratadiver

import org.lwjgl.opengl.ARBVertexArrayObject.glDeleteVertexArrays
import kotlin.math.floor


// --- Block Definitions ---
enum class BlockType(val id: Byte, val texX: Int, val texY: Int) {
    AIR(0, 0, 0),
    GRASS(1, 0, 0),
    DIRT(2, 1, 0),
    STONE(3, 2, 0),
    BEDROCK(4, 3, 0),
    WOOD(5, 0, 1);

    companion object {
        fun getById(id: Byte) = entries.find { it.id == id } ?: AIR
    }
}

// --- The Chunk container ---
class Chunk(val xOffset: Int, val yOffset: Int, val zOffset: Int) {
    companion object {
        const val SIZE = 16
    }

    // 1D array to represent 3D space (x * size*size + y * size + z)
    private val blocks = ByteArray(SIZE * SIZE * SIZE)

    var vao: Int = -1
    var vertexCount: Int = 0
    var isMeshGenerated = false

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

        if (surfaceLocalY != -1 && Math.random() < 0.6) {
            addSimpleTree(8, surfaceLocalY + 1, 8)
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


    private fun addSimpleTree(tx: Int, ty: Int, tz: Int) {
        // --- FIX 1: CHANGE TRUNK FROM STONE TO WOOD ---
        for (i in 0..4) {
            if (ty + i < SIZE) {
                setBlock(tx, ty + i, tz, BlockType.WOOD) // Was BlockType.STONE
            }
        }

        // Leaves (a simple 3x3x3 cube of grass blocks)
        for (lx in -1..1) {
            // Leaves start higher up the trunk
            for (ly in 3..5) {
                for (lz in -1..1) {
                    if (tx + lx in 0 until SIZE && ty + ly in 0 until SIZE && tz + lz in 0 until SIZE) {
                        // Only place leaves if the space is empty
                        if (getBlock(tx + lx, ty + ly, tz + lz) == BlockType.AIR.id) {
                            setBlock(tx + lx, ty + ly, tz + lz, BlockType.GRASS)
                        }
                    }
                }
            }
        }
    }

    fun cleanup() {
        if (vao != -1) {
            glDeleteVertexArrays(vao)
            isMeshGenerated = false
        }
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
                    if (chunk.getBlock(x, y + 1, z) == BlockType.AIR.id) addFaceTop(vertices, gx, gy, gz, blockType)
                    if (chunk.getBlock(x, y - 1, z) == BlockType.AIR.id) addFaceBottom(vertices, gx, gy, gz, blockType)
                    if (chunk.getBlock(x, y, z - 1) == BlockType.AIR.id) addFaceNorth(vertices, gx, gy, gz, blockType)
                    if (chunk.getBlock(x, y, z + 1) == BlockType.AIR.id) addFaceSouth(vertices, gx, gy, gz, blockType)
                    if (chunk.getBlock(x - 1, y, z) == BlockType.AIR.id) addFaceWest(vertices, gx, gy, gz, blockType)
                    if (chunk.getBlock(x + 1, y, z) == BlockType.AIR.id) addFaceEast(vertices, gx, gy, gz, blockType)
                }
            }
        }
        return vertices.toFloatArray()
    }


    private fun getUVs(bt: BlockType): FloatArray {
        val uMin = bt.texX / ATLAS_SIZE
        val vMin = bt.texY / ATLAS_SIZE
        val uMax = (bt.texX + 1) / ATLAS_SIZE
        val vMax = (bt.texY + 1) / ATLAS_SIZE
        return floatArrayOf(uMin, vMin, uMax, vMax)
    }

    private fun addVertex(list: MutableList<Float>, x: Float, y: Float, z: Float, u: Float, v: Float, nx: Float, ny: Float, nz: Float) {
        list.add(x); list.add(y); list.add(z) // Position (3 floats)
        list.add(u); list.add(v)             // UV (2 floats)
        list.add(nx); list.add(ny); list.add(nz) // Normals (3 floats)
    }

    private fun addFaceTop(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt)
        val nx = 0f; val ny = 1f; val nz = 0f // Facing Up
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x, y + 1f, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, nx, ny, nz)
    }

    private fun addFaceBottom(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt)
        val nx = 0f; val ny = -1f; val nz = 0f // Facing Down
        addVertex(v, x, y, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x, y, z + 1f, uMin, vMax, nx, ny, nz)
        addVertex(v, x, y, z, uMin, vMin, nx, ny, nz)
    }

    private fun addFaceNorth(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt)
        val nx = 0f; val ny = 0f; val nz = -1f // Facing North (-Z)
        addVertex(v, x + 1f, y + 1f, z, uMin, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x, y, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x, y, z, uMax, vMin, nx, ny, nz)
        addVertex(v, x, y + 1f, z, uMax, vMax, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z, uMin, vMax, nx, ny, nz)
    }

    private fun addFaceSouth(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt)
        val nx = 0f; val ny = 0f; val nz = 1f // Facing South (+Z)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, nx, ny, nz)
        addVertex(v, x, y, z + 1f, uMin, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y, z + 1f, uMax, vMin, nx, ny, nz)
        addVertex(v, x + 1f, y + 1f, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x, y + 1f, z + 1f, uMin, vMax, nx, ny, nz)
    }

    private fun addFaceWest(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt)
        val nx = -1f; val ny = 0f; val nz = 0f // Facing West (-X)
        addVertex(v, x, y + 1f, z + 1f, uMax, vMax, nx, ny, nz)
        addVertex(v, x, y + 1f, z, uMin, vMax, nx, ny, nz)
        addVertex(v, x, y, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x, y, z, uMin, vMin, nx, ny, nz)
        addVertex(v, x, y, z + 1f, uMax, vMin, nx, ny, nz)
        addVertex(v, x, y + 1f, z + 1f, uMax, vMax, nx, ny, nz)
    }

    private fun addFaceEast(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        val (uMin, vMin, uMax, vMax) = getUVs(bt)
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
