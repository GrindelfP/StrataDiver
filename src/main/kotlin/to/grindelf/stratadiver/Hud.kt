package to.grindelf.stratadiver

/**
 * The always-on and on-demand screen furniture: the crosshair, the hotbar, and the
 * inventory layer that opens above it.
 *
 * Pure drawing. Where the slots are is [SlotLayout]'s question and what a click does is
 * the game's — this only has to know how to put an Icon in a box.
 */
object Hud {

    /**
     * Screen-centre crosshair.
     *
     * Drawn in the orthographic GUI pass, not in the world pass: a world-space quad
     * pinned to the camera sits exactly on the near plane and gets clipped, which is why
     * an earlier version of this never appeared at all. The Tile is a cutout, so only the
     * cross itself is opaque and the rest of the quad is discarded.
     */
    fun crosshair(ui: UiPass) {
        ui.begin()
        ui.tile(ui.width / 2f - 10f, ui.height / 2f - 10f, 20f, 20f, Atlas.CROSSHAIR)
        ui.end()
    }

    /** The hotbar, always on screen. Only the selected slot wears the highlight. */
    fun hotbar(ui: UiPass, layout: SlotLayout, inventory: Inventory, selectedSlot: Int) {
        ui.begin()
        for (i in 0 until layout.hotbarColumns) {
            val slot = layout.hotbarSlot(i)
            ui.tile(slot.x, slot.y, slot.width, slot.height, Atlas.SLOT)
            if (i == selectedSlot) {
                ui.tile(slot.x, slot.y, slot.width, slot.height, Atlas.SLOT_SELECTED)
            }
            inventory.hotbar[i]?.let { ui.icon(slot.x + 5f, slot.y + 5f, slot.width - 10f, it.type) }
        }
        ui.end()
    }

    /**
     * The inventory grid, on a Panel behind it.
     *
     * The Panel is lighter than the slots, which are lighter than a stone Icon, so every
     * Block Type reads as a distinct shape instead of dissolving into the background. It
     * stops short of the hotbar, which stays visible and usable underneath.
     */
    fun inventory(ui: UiPass, layout: SlotLayout, inventory: Inventory) {
        val panel = layout.mainPanel
        ui.begin()
        ui.tile(panel.x, panel.y, panel.width, panel.height, Atlas.PANEL)

        for (i in 0 until layout.mainRows * layout.mainColumns) {
            val slot = layout.mainSlot(i % layout.mainColumns, i / layout.mainColumns)
            ui.tile(slot.x, slot.y, slot.width, slot.height, Atlas.SLOT)
            inventory.main[i]?.let { ui.icon(slot.x + 5f, slot.y + 5f, slot.width - 10f, it.type) }
        }
        ui.end()
    }

    /** The stack the cursor is carrying, drawn under the pointer. */
    fun heldItem(ui: UiPass, item: ItemStack, cursorX: Float, cursorY: Float) {
        ui.begin()
        ui.icon(cursorX - 20f, cursorY - 20f, 40f, item.type)
        ui.end()
    }
}
