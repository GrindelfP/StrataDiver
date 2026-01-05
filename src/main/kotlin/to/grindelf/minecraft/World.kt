package to.grindelf.minecraft

import org.lwjgl.opengl.ARBVertexArrayObject.glDeleteVertexArrays
import kotlin.math.floor


// --- Block Definitions ---
enum class BlockType(val id: Byte, val colorR: Float, val colorG: Float, val colorB: Float) {
    AIR(0, 0f, 0f, 0f),
    GRASS(1, 0.2f, 0.7f, 0.2f),
    DIRT(2, 0.5f, 0.3f, 0.2f),
    STONE(3, 0.4f, 0.4f, 0.4f);

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
                    if (tx+lx in 0 until SIZE && ty+ly in 0 until SIZE && tz+lz in 0 until SIZE) {
                        if (getBlock(tx+lx, ty+ly, tz+lz) == BlockType.AIR.id) {
                            setBlock(tx+lx, ty+ly, tz+lz, BlockType.GRASS)
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

    fun generateChunkMesh(chunk: Chunk): FloatArray {
        val vertices = mutableListOf<Float>()
        val size = Chunk.SIZE

        for (x in 0 until size) {
            for (y in 0 until size) {
                for (z in 0 until size) {
                    val blockId = chunk.getBlock(x, y, z)
                    if (blockId == BlockType.AIR.id) continue

                    val blockType = BlockType.getById(blockId)
                    // Calculate global coordinates for the block
                    val gx = (chunk.xOffset * size + x).toFloat()
                    val gy = (chunk.yOffset * size + y).toFloat()
                    val gz = (chunk.zOffset * size + z).toFloat()

                    // --- FACE CULLING LOGIC ---
                    // Check neighbors. If neighbor is AIR, draw the face.

                    // Top (+Y)
                    if (chunk.getBlock(x, y + 1, z) == BlockType.AIR.id) {
                        addFaceTop(vertices, gx, gy, gz, blockType)
                    }
                    // Bottom (-Y)
                    if (chunk.getBlock(x, y - 1, z) == BlockType.AIR.id) {
                        addFaceBottom(vertices, gx, gy, gz, blockType)
                    }
                    // North (-Z)
                    if (chunk.getBlock(x, y, z - 1) == BlockType.AIR.id) {
                        addFaceNorth(vertices, gx, gy, gz, blockType)
                    }
                    // South (+Z)
                    if (chunk.getBlock(x, y, z + 1) == BlockType.AIR.id) {
                        addFaceSouth(vertices, gx, gy, gz, blockType)
                    }
                    // West (-X)
                    if (chunk.getBlock(x - 1, y, z) == BlockType.AIR.id) {
                        addFaceWest(vertices, gx, gy, gz, blockType)
                    }
                    // East (+X)
                    if (chunk.getBlock(x + 1, y, z) == BlockType.AIR.id) {
                        addFaceEast(vertices, gx, gy, gz, blockType)
                    }
                }
            }
        }
        return vertices.toFloatArray()
    }

    // Helper to add a single vertex (Position + Color)
    private fun addVertex(list: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        // Position
        list.add(x); list.add(y); list.add(z)
        // Color (a little randomized for texture)
        val shade = (Math.random() * 0.1f).toFloat()
        list.add(bt.colorR + shade); list.add(bt.colorG + shade); list.add(bt.colorB + shade)
    }

    // --- The 6 Face implementations (2 triangles each) ---

    private fun addFaceTop(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        addVertex(v, x, y + 1f, z + 1f, bt)
        addVertex(v, x + 1f, y + 1f, z + 1f, bt)
        addVertex(v, x + 1f, y + 1f, z, bt)
        addVertex(v, x + 1f, y + 1f, z, bt)
        addVertex(v, x, y + 1f, z, bt)
        addVertex(v, x, y + 1f, z + 1f, bt)
    }

    private fun addFaceBottom(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        addVertex(v, x, y, z, bt)
        addVertex(v, x + 1f, y, z, bt)
        addVertex(v, x + 1f, y, z + 1f, bt)
        addVertex(v, x + 1f, y, z + 1f, bt)
        addVertex(v, x, y, z + 1f, bt)
        addVertex(v, x, y, z, bt)
    }

    private fun addFaceNorth(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        addVertex(v, x + 1f, y + 1f, z, bt)
        addVertex(v, x + 1f, y, z, bt)
        addVertex(v, x, y, z, bt)
        addVertex(v, x, y, z, bt)
        addVertex(v, x, y + 1f, z, bt)
        addVertex(v, x + 1f, y + 1f, z, bt)
    }

    private fun addFaceSouth(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        addVertex(v, x, y + 1f, z + 1f, bt)
        addVertex(v, x, y, z + 1f, bt)
        addVertex(v, x + 1f, y, z + 1f, bt)
        addVertex(v, x + 1f, y, z + 1f, bt)
        addVertex(v, x + 1f, y + 1f, z + 1f, bt)
        addVertex(v, x, y + 1f, z + 1f, bt)
    }

    private fun addFaceWest(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        addVertex(v, x, y + 1f, z + 1f, bt)
        addVertex(v, x, y + 1f, z, bt)
        addVertex(v, x, y, z, bt)
        addVertex(v, x, y, z, bt)
        addVertex(v, x, y, z + 1f, bt)
        addVertex(v, x, y + 1f, z + 1f, bt)
    }

    private fun addFaceEast(v: MutableList<Float>, x: Float, y: Float, z: Float, bt: BlockType) {
        addVertex(v, x + 1f, y + 1f, z, bt)
        addVertex(v, x + 1f, y + 1f, z + 1f, bt)
        addVertex(v, x + 1f, y, z + 1f, bt)
        addVertex(v, x + 1f, y, z + 1f, bt)
        addVertex(v, x + 1f, y, z, bt)
        addVertex(v, x + 1f, y + 1f, z, bt)
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
