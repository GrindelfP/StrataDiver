package shot

import to.grindelf.stratadiver.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders a UI Pass over nothing, so what each HUD element draws can be looked at on its
 * own.
 *
 * The minimap, the crosshair and the frame breakdown were all absent from a full frame
 * while the hotbar drew correctly, and the difference between them was not obvious from
 * the code. This removes the world and every other layer from the question: a bare UiPass,
 * a cleared background, and the same calls the game makes. Whatever is missing here is
 * missing for a reason that is about the element rather than about what is underneath it.
 *
 *   tools/uishot.sh
 */
fun main() {
    val size = Atlas.TILES * Atlas.TILE_PX
    val art = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val pixels = Art.buildAtlasPixels()
    for (y in 0 until size) {
        for (x in 0 until size) {
            val p = pixels[y * size + x]
            art.setRGB(x, y, p)
        }
    }
    ImageIO.write(art, "png", File("target/atlas.png"))
    println("wrote target/atlas.png")

    // Per-tile opacity, which is the question a discard-based GUI cannot answer on its own:
    // a Tile that is entirely transparent discards every fragment it draws and is
    // indistinguishable from one that was never asked for.
    println("\ntile                opaque px   holes")
    for ((name, ref) in listOf(
        "SLOT" to Atlas.SLOT,
        "SLOT_SELECTED" to Atlas.SLOT_SELECTED,
        "PANEL" to Atlas.PANEL,
        "CROSSHAIR" to Atlas.CROSSHAIR,
        "LINE" to Atlas.LINE
    )) {
        var solid = 0
        var holes = 0
        for (ay in 0 until Atlas.ART_PX) {
            for (ax in 0 until Atlas.ART_PX) {
                val px = ref.tx * Atlas.TILE_PX + Atlas.PAD + ax * Atlas.UPSCALE
                val py = ref.ty * Atlas.TILE_PX + Atlas.PAD + ay * Atlas.UPSCALE
                val a = (pixels[py * size + px] ushr 24) and 0xFF
                if (a >= 26) solid++ else holes++
            }
        }
        println("  %-16s %6d %8d".format(name, solid, holes))
    }
}