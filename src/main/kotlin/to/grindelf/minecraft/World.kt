package to.grindelf.minecraft

import org.lwjgl.opengl.ARBVertexArrayObject.glDeleteVertexArrays
import kotlin.math.floor


// --- Block Definitions ---
enum class BlockType(val id: Byte, val texX: Int, val texY: Int) {
    AIR(0, 0, 0),
    GRASS(1, 0, 0),
    DIRT(2, 1, 0),
    STONE(3, 2, 0);

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


//    init {
//        // Temporary generation: A flat layer of grass over dirt over stone
//        for (x in 0 until SIZE) {
//            for (z in 0 until SIZE) {
//                for (y in 0 until SIZE) {
//                    val blockType = when {
//                        y == 7 -> BlockType.GRASS
//                        y in 4..6 -> BlockType.DIRT
//                        y < 4 -> BlockType.STONE
//                        else -> BlockType.AIR
//                    }
//                    setBlock(x, y, z, blockType)
//                }
//            }
//        }
//    }

    init {
        for (x in 0 until SIZE) {
            for (z in 0 until SIZE) {
                // Global coordinates (needed for noise continuity across chunks later)
                val worldX = (xOffset * SIZE + x).toFloat()
                val worldZ = (zOffset * SIZE + z).toFloat()

                // Generate a height between 5 and 12
                // We divide world coords by a "frequency" (20.0f) to make the hills wider
                val noiseValue = FastNoise.getSmoothNoise(worldX / 20.0f, worldZ / 20.0f)
                val surfaceHeight = 5 + ((noiseValue + 1f) * 4f).toInt()

                for (y in 0 until SIZE) {
                    val blockType = when {
                        y > surfaceHeight -> BlockType.AIR
                        y == surfaceHeight -> BlockType.GRASS
                        y > surfaceHeight - 3 -> BlockType.DIRT
                        else -> BlockType.STONE
                    }
                    setBlock(x, y, z, blockType)
                }
            }
        }
        // After generating land, let's try to add a tree!
        if (Math.random() > 0.5) { // 5% chance per chunk for now
            addSimpleTree(8, 8, 8) // Stick a tree in the middle if it's clear
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
        // Trunk
        for (i in 0..4) {
            if (ty + i < SIZE) setBlock(tx, ty + i, tz, BlockType.STONE) // Using stone as "wood" for now
        }
        // Leaves (a simple 3x3x3 cube of grass blocks)
        for (lx in -1..1) {
            for (ly in 3..5) {
                for (lz in -1..1) {
                    if (tx + lx in 0 until SIZE && ty + ly in 0 until SIZE && tz + lz in 0 until SIZE) {
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
