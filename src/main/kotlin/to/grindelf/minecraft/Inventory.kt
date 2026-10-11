package to.grindelf.minecraft

data class ItemStack(val type: BlockType, var count: Int)

class Inventory {
    val hotbar = Array<ItemStack?>(9) { null }
    val main = Array<ItemStack?>(27) { null } // 9x3 grid

    // Helper to add items automatically
    fun addItem(type: BlockType): Boolean {
        // 1. Try to stack in existing slots
        (hotbar + main).filter { it?.type == type && it.count < 64 }.firstOrNull()?.let {
            it.count++
            return true
        }
        // 2. Try to find empty slot (Hotbar first, then Main)
        for (i in hotbar.indices) if (hotbar[i] == null) { hotbar[i] = ItemStack(type, 1); return true }
        for (i in main.indices) if (main[i] == null) { main[i] = ItemStack(type, 1); return true }
        return false
    }
}
