package to.grindelf.minecraft


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

    init {
        // Temporary generation: A flat layer of grass over dirt over stone
        for (x in 0 until SIZE) {
            for (z in 0 until SIZE) {
                for (y in 0 until SIZE) {
                    val blockType = when {
                        y == 7 -> BlockType.GRASS
                        y in 4..6 -> BlockType.DIRT
                        y < 4 -> BlockType.STONE
                        else -> BlockType.AIR
                    }
                    setBlock(x, y, z, blockType)
                }
            }
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
