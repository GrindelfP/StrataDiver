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

    /**
     * The Minimap, top-right. Round, north-up, and always showing the world's current
     * Surface — a structure the player has just built is on the map before the next frame
     * is drawn, under exactly the rules natural terrain is.
     *
     * Only the round clip, the rim and the player marker happen here in the shader; what
     * each Cell actually *is* was decided by the CPU, because a fragment shader cannot
     * read the world. That split is the whole reason this works, and the reason the
     * version of it that invented sine-wave terrain looked convincing and was fiction.
     *
     * The quad is drawn with `isMinimap` on, which routes its 0..1 UVs through as map
     * coordinates instead of atlas coordinates. Leaving `isEntity` on would divide them
     * by the atlas size and sample the wrong part of the wrong texture.
     */
    fun minimap(
        ui: UiPass,
        texture: Int,
        map: Minimap,
        player: Player,
        diameter: Float,
        margin: Float
    ) {
        ui.begin()

        // The player's position within the map, in 0..1.
        //
        // This is a constant, not a moving value, and the reason is worth stating: the map
        // is rebuilt centred on the player every time, so the player is always at the same
        // place in it — half a Cell in from the middle, because the map is quantised to
        // whole Cells and the player stands between them. It is computed from the same
        // radius the map was built with rather than written as 0.5, so that if the map is
        // ever rebuilt off-centre this follows instead of quietly drawing the marker on
        // the wrong Cell.
        val cells = map.cells
        val origin = (map.radius + 0.5f) / cells

        // Where the player is facing on a plan view. Owned by the map because deciding
        // what to do about looking straight up needs state — the last usable heading.
        val heading = map.heading(player.front)

        ui.minimap(
            x = ui.width - diameter - margin,
            y = margin,
            diameter = diameter,
            texture = texture,
            originX = origin,
            originY = origin,
            // The heading, normalised and pitch-free. Not `player.front` directly: the
            // shader's tick is sized in units of this vector's length, so an un-normalised
            // one makes the marker grow as the player looks up and down.
            facingX = heading.x,
            facingY = heading.y
        )

        ui.end()
    }
}
