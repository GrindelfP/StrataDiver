//package to.grindelf.minecraft
//
//class Chunk(val xOffset: Int, val yOffset: Int, val zOffset: Int) {
//    companion object {
//        const val SIZE = 16
//    }
//
//    private val blocks = ByteArray(SIZE * SIZE * SIZE)
//
//    init {
//        // Simple generation: Fill the bottom half with stone/grass
//        for (x in 0 until SIZE) {
//            for (z in 0 until SIZE) {
//                for (y in 0 until SIZE) {
//                    val index = getIndex(x, y, z)
//                    blocks[index] = when {
//                        y < 5 -> BlockType.STONE.id
//                        y < 8 -> BlockType.GRASS.id
//                        else -> BlockType.AIR.id
//                    }
//                }
//            }
//        }
//    }
//
//    private fun getIndex(x: Int, y: Int, z: Int) = x * SIZE * SIZE + y * SIZE + z
//
//    fun getBlock(x: Int, y: Int, z: Int): Byte {
//        if (x !in 0 until SIZE || y !in 0 until SIZE || z !in 0 until SIZE) return 0
//        return blocks[getIndex(x, y, z)]
//    }
//}
//
//
