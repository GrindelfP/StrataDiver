package to.grindelf.stratadiver

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.File

/**
 * Scratch verification tool. Renders what the game draws so the art, the Icon
 * compositing and the GUI contrast can be inspected without launching a window.
 */
object AtlasDump {
    private const val TS = Atlas.TILE_PX

    @JvmStatic
    fun main(args: Array<String>) {
        val size = Atlas.TILES * TS
        val buf = Art.buildAtlas()

        fun texel(tile: TileRef, u: Float, v: Float): Int = sample(buf, size, tile, u, v)

        outAtlas(args.getOrElse(0) { "target/atlas.png" }, ::texel)
        outInventory(args.getOrElse(1) { "target/inventory.png" }, ::texel)
        println("atlas ${size}x$size art ${Atlas.ART_PX}px upscale ${Atlas.UPSCALE} pad ${Atlas.PAD}")
    }

    /**
     * One texel lookup inside a tile, as a non-negative 0xRRGGBB. Returns -1 for a
     * cutout. Alpha is deliberately dropped: a packed ARGB with alpha 255 is a negative
     * Int, which would make every opaque texel look like a cutout to a `c < 0` test.
     */
    private fun sample(buf: java.nio.ByteBuffer, size: Int, tile: TileRef, u: Float, v: Float): Int {
        // Clamp to the tile's own pixel range: v is 0 at the bottom in GL space, and
        // v=0 exactly would otherwise address the next tile row (or off the buffer).
        val tx = ((tile.tx * TS) + (u.coerceIn(0f, 0.9999f) * TS).toInt()).coerceIn(0, size - 1)
        val ty = ((tile.ty * TS) + ((1f - v.coerceIn(0f, 0.9999f)) * TS).toInt()).coerceIn(0, size - 1)
        val i = (ty * size + tx) * 4
        val a = buf.get(i + 3).toInt() and 0xFF
        if (a < 128) return -1 // cutout
        val r = buf.get(i).toInt() and 0xFF
        val g = buf.get(i + 1).toInt() and 0xFF
        val b = buf.get(i + 2).toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    /** Full atlas, magnified, cutouts in magenta. */
    private fun outAtlas(path: String, probe: (TileRef, Float, Float) -> Int) {
        val size = Atlas.TILES * TS
        val s = 4
        val img = BufferedImage(size * s, size * s, BufferedImage.TYPE_INT_RGB)
        // sample() takes GL v (0 = bottom) and flips internally, so image row r maps to
        // v = 1 - r/TS. Tile ty = bufferRow / TS.
        var holes = 0; var solid = 0
        for (y in 0 until size * s) {
            val r = y / s
            val ty = r / TS
            for (x in 0 until size * s) {
                val c0 = r % TS
                val c1 = x / s % TS
                val tx = x / s / TS
                val u = c1.toFloat() / TS
                val v = 1f - c0.toFloat() / TS
                val c = probe(TileRef(tx, ty), u, v)
                if (c < 0) holes++ else solid++
                img.setRGB(x, y, if (c < 0) 0xFF00FF else c)
            }
        }
        ImageIO.write(img, "png", File(path))
        println("wrote $path  solid=$solid holes=$holes")
    }

    /**
     * Renders a mock inventory: panel, slot frames and composited Icons at the exact
     * values the game uses, so GUI contrast can be judged.
     */
    private fun outInventory(
        path: String,
        texel: (TileRef, Float, Float) -> Int
    ) {
        fun tileSampler(tile: TileRef): (Float, Float) -> Int = { u, v -> texel(tile, u, v) }

        val slot = 50
        val inset = 5
        val icon = slot - inset * 2
        val items = listOf(
            BlockType.GRASS, BlockType.DIRT, BlockType.STONE,
            BlockType.WOOD, BlockType.BEDROCK
        )
        val w = items.size * slot + 120
        val h = slot + 120
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, 0x202020)

        // Panel
        val panel = tileSampler(Atlas.PANEL)
        fill(img, 30, 30, w - 60, h - 60) { u, v ->
            val c = panel(u, v); if (c < 0) 0x808080 else c
        }

        for (t in listOf(Atlas.PANEL, Atlas.SLOT, Atlas.GRASS_TOP, Atlas.LEAVES, Atlas.LINE)) {
            println("tile(${t.tx},${t.ty}) centre=" + Integer.toHexString(tileSampler(t)(0.5f, 0.5f)) +
                    " edge=" + Integer.toHexString(tileSampler(t)(0.03f, 0.03f)))
        }
        items.forEachIndexed { i, type ->
            val sx = 60 + i * slot
            val sy = 60

            // Slot frame
            val frame = tileSampler(Atlas.SLOT)
            fill(img, sx, sy, slot, slot) { u, v ->
                val c = frame(u, v); if (c < 0) 0x808080 else c
            }

            // Icon: same parallelogram mapping as drawBlockIcon.
            val x = sx + inset; val y = sy + inset
            val iw = icon.toFloat()
            val ih = iw / 4f
            val sideH = iw * 0.36f
            val totalH = 2f * ih + sideH
            val cx = x + iw / 2f
            val cy = y + (iw - totalH) / 2f + ih
            val left = cx - iw / 2f; val right = cx + iw / 2f

            val top = tileSampler(type.tile(Face.TOP))
            val north = tileSampler(type.tile(Face.NORTH))
            val east = tileSampler(type.tile(Face.EAST))

            // Each face is an affine image of the unit square with corner p0 at (0,0),
            // p1 at (1,0) and p3 at (0,1). Inverting that map gives exact (u,v), and the
            // point is inside precisely when both are in [0,1] — no scanline test needed.
            fun face(
                p0x: Float, p0y: Float, p1x: Float, p1y: Float, p3x: Float, p3y: Float,
                smp: (Float, Float) -> Int, bright: Float
            ) {
                val ax = p1x - p0x; val ay = p1y - p0y
                val bx = p3x - p0x; val by = p3y - p0y
                val det = ax * by - ay * bx
                if (kotlin.math.abs(det) < 1e-6f) return
                for (py in 0 until img.height) {
                    val dy = (py + 0.5f) - p0y
                    for (px in 0 until img.width) {
                        val dx = (px + 0.5f) - p0x
                        val u = (dx * by - dy * bx) / det
                        val v = (ax * dy - ay * dx) / det
                        if (u < 0f || u >= 1f || v < 0f || v >= 1f) continue
                        val c = smp(u, v)
                        if (c < 0) continue
                        val r = (((c shr 16) and 0xFF) * bright).toInt().coerceIn(0, 255)
                        val g = (((c shr 8) and 0xFF) * bright).toInt().coerceIn(0, 255)
                        val b = ((c and 0xFF) * bright).toInt().coerceIn(0, 255)
                        img.setRGB(px, py, (r shl 16) or (g shl 8) or b)
                    }
                }
            }

            // Same order, corners and brightness as drawBlockIcon: north, east, then top.
            face(left, cy, cx, cy + ih, left, cy + sideH, north, 0.72f)
            face(cx, cy + ih, right, cy, cx, cy + ih + sideH, east, 0.55f)
            face(cx, cy + ih, right, cy, left, cy, top, 1.0f)
        }

        ImageIO.write(img, "png", File(path))
        println("wrote $path")
    }

    private fun fill(img: BufferedImage, x: Int, y: Int, w: Int, h: Int, s: (Float, Float) -> Int) {
        for (py in y until y + h) for (px in x until x + w) {
            img.setRGB(px, py, s((px - x).toFloat() / w, (py - y).toFloat() / h))
        }
    }
}