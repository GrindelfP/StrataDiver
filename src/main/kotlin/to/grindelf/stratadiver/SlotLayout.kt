package to.grindelf.stratadiver

/** Which of the two grids a slot belongs to. */
enum class SlotContainer { MAIN, HOTBAR }

/** A slot on screen: which grid, and its index within it. */
data class SlotRef(val container: SlotContainer, val index: Int)

/** An axis-aligned rectangle in screen pixels. */
data class ScreenRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right get() = x + width
    val bottom get() = y + height

    /** Whether the point is inside this rectangle. */
    fun holds(px: Float, py: Float) = px >= x && px <= right && py >= y && py <= bottom
}

/**
 * Where the hotbar and the main grid sit on screen.
 *
 * This arithmetic used to be written out three times — once to draw the hotbar, once to
 * draw the inventory, once to hit-test a click — and the grid has to clear the
 * always-on hotbar in all three or the two layers overlap and the hotbar cannot be used
 * while the inventory is open. Written once, that cannot drift.
 *
 * It is also pure arithmetic with no GL in it, which makes it checkable without a window.
 */
class SlotLayout(val screenWidth: Float, val screenHeight: Float) {

    val slotSize = 50f
    val mainRows = 3
    val mainColumns = 9
    val hotbarColumns = 9

    private val gridWidth = mainColumns * slotSize
    private val gridLeft = (screenWidth - gridWidth) / 2f

    /** Top edge of the hotbar, which is always on screen. */
    val hotbarTop = screenHeight - slotSize - 10f

    /** Top edge of the main grid, above the hotbar, the way Minecraft stacks them. */
    val mainGridTop = hotbarTop - mainRows * slotSize - 20f

    /** The Panel drawn behind the main grid, stopping short of the hotbar. */
    val mainPanel = ScreenRect(
        gridLeft - 30f,
        mainGridTop - 20f,
        gridWidth + 60f,
        hotbarTop - 10f - (mainGridTop - 20f)
    )

    fun mainSlot(column: Int, row: Int) = ScreenRect(gridLeft + column * slotSize, mainGridTop + row * slotSize, slotSize, slotSize)

    fun hotbarSlot(column: Int) = ScreenRect(gridLeft + column * slotSize, hotbarTop, slotSize, slotSize)

    /** The slot under the cursor, or null if the cursor is between them. */
    fun slotAt(px: Float, py: Float): SlotRef? {
        for (row in 0 until mainRows) {
            for (column in 0 until mainColumns) {
                if (mainSlot(column, row).holds(px, py)) {
                    return SlotRef(SlotContainer.MAIN, row * mainColumns + column)
                }
            }
        }
        for (column in 0 until hotbarColumns) {
            if (hotbarSlot(column).holds(px, py)) return SlotRef(SlotContainer.HOTBAR, column)
        }
        return null
    }
}
