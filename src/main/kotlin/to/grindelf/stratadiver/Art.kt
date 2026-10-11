package to.grindelf.stratadiver

import java.nio.ByteBuffer

/**
 * Source art for one atlas tile: 16 rows of 16 characters, one character per art pixel.
 *
 * Characters that are shape-bearing (a grass fringe outline, log rings, a cutout hole)
 * are authored explicitly here. Characters that are only surface grain are modulated at
 * generation time by a seeded hash so the atlas is byte-identical on every launch.
 *
 * Characters are resolved through the tile's own palette, so the same letter can mean
 * "grass" in one tile and "dirt" in another.
 */
class TileArt(
    val palette: Map<Char, Int>,
    val rows: List<String>,
    val grain: Int = 0,
    val transparent: Char? = null,
) {
    init {
        require(rows.size == Atlas.ART_PX) { "expected ${Atlas.ART_PX} rows, got ${rows.size}" }
        rows.forEach {
            require(it.length == Atlas.ART_PX) { "expected ${Atlas.ART_PX} columns, got ${it.length}" }
        }
    }
}

/** Generates the 256x256 RGBA atlas from authored art. */
object Art {

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    /**
     * Deterministic per-pixel grain. Same art in, same atlas out, every run.
     */
    private fun grainAt(px: Int, py: Int, seed: Int, amplitude: Int): Int {
        if (amplitude == 0) return 0
        var h = px * 374761393 + py * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0x7FFFFFFF) % (amplitude * 2 + 1)) - amplitude
    }

    // --- Palettes ---

    private val grassPal = mapOf(
        'b' to rgb(104, 158, 62),
        'd' to rgb(86, 138, 50),
        'l' to rgb(126, 180, 78),
    )

    private val dirtPal = mapOf(
        'b' to rgb(134, 96, 67),
        'd' to rgb(112, 79, 55),
        'l' to rgb(156, 116, 84),
    )

    /** Grass over dirt: 'g' family is grass, the rest is dirt. */
    private val grassSidePal = mapOf(
        'g' to rgb(104, 158, 62),
        'G' to rgb(86, 138, 50),
        'b' to rgb(134, 96, 67),
        'd' to rgb(112, 79, 55),
        'l' to rgb(156, 116, 84),
    )

    private val stonePal = mapOf(
        'b' to rgb(132, 132, 134),
        'd' to rgb(112, 112, 115),
        'l' to rgb(152, 152, 154),
        'x' to rgb(92, 92, 96),
    )

    private val bedrockPal = mapOf(
        'b' to rgb(84, 84, 88),
        'd' to rgb(62, 62, 66),
        'l' to rgb(108, 108, 112),
        'k' to rgb(42, 42, 46),
    )

    private val barkPal = mapOf(
        'b' to rgb(104, 80, 46),
        'd' to rgb(86, 64, 36),
        'l' to rgb(124, 98, 60),
        'k' to rgb(66, 49, 28),
    )

    private val logTopPal = mapOf(
        'b' to rgb(176, 145, 95),
        'l' to rgb(196, 166, 116),
        'k' to rgb(132, 103, 64),
    )

    /** '.' is a cutout: alpha 0, so the shader discards it and you see through. */
    private val leafPal = mapOf(
        'b' to rgb(58, 122, 42),
        'd' to rgb(44, 100, 33),
        'l' to rgb(78, 146, 56),
        '.' to rgb(0, 0, 0),
    )

    private val slotPal = mapOf(
        'k' to rgb(55, 55, 55),
        'l' to rgb(139, 139, 139),
        'd' to rgb(100, 100, 100),
        'i' to rgb(139, 139, 139),
        'w' to rgb(255, 255, 255),
    )

    private val panelPal = mapOf('b' to rgb(198, 198, 198))

    // --- Art ---
    //
    // Row 0 is the top row of the tile as drawn on screen.

    private val GRASS_TOP = TileArt(grassPal, listOf(
        "bdbldbbdlbdlbbdl",
        "dbdlbdblbdblbdbl",
        "lbdlbdbdlbdlbdbd",
        "bdblbdblbdblbdbl",
        "dlbdbldblbdlbdbl",
        "bldbdlbdlbdblbdb",
        "dbdlbdblbdblbdbl",
        "lbdlbdbdlbdlbdbd",
        "bdblbdblbdblbdbl",
        "dlbdbldblbdlbdbl",
        "bldbdlbdlbdblbdb",
        "dbdlbdblbdblbdbl",
        "lbdlbdbdlbdlbdbd",
        "bdblbdblbdblbdbl",
        "dlbdbldblbdlbdbl",
        "bldbdlbdlbdblbdb",
    ), grain = 9)

    private val GRASS_SIDE = TileArt(grassSidePal, listOf(
        "gGggGgGggGggGgGg",
        "GggGggGggGggGggG",
        "ggGggGggGggGggGg",
        "gGggGgGggGggGgGg",
        "GggGggGggGggGggG",
        "gGdGgGbGdgGgGgdg",
        "GdgGdgGdgGdgGdgG",
        "bdbdlbdbdlbdbldb",
        "dbdldbdbdlbdbdlb",
        "bdlbdbdlbdbdlbdb",
        "dbdlbdbdlbdbdlbd",
        "lbdbdlbdbdlbdbdl",
        "bdlbdbdbdlbdbdld",
        "dbdlbdbdlbdbdlbd",
        "blbdlbdbdlbdbdll",
        "dlbdbddbdbdlbdbd",
    ), grain = 9)

    private val DIRT = TileArt(dirtPal, listOf(
        "bdbdlbdbdlbdbldb",
        "dbdldbdbdlbdbdlb",
        "bdlbdbdlbdbdlbdb",
        "dbdlbdbdlbdbdlbd",
        "lbdbdlbdbdlbdbdl",
        "bdlbdbdbdlbdbdld",
        "dbdlbdbdlbdbdlbd",
        "blbdlbdbdlbdbdll",
        "dlbdbddbdbdlbdbd",
        "bdbdlbdbdlbdbdld",
        "dbdlbdbdlbdbdlbd",
        "dlbdbdlbdbdldbdl",
        "lbdbdlbdbdlbdbdl",
        "bdbdlbddbdbdlbdd",
        "dbdlbdbdlbdbdlbd",
        "dlbdbdbdlbdbdldb",
    ), grain = 10)

    private val STONE = TileArt(stonePal, listOf(
        "bdbxdbdlbdbxdbdl",
        "dbdlbdblxdbdlbdb",
        "bdlbdbxdlbdbxdbd",
        "dbxdbdlbdbdlbdbl",
        "lbdbdlbdbxdbdlbd",
        "bdlbdbdlbdbxdbdl",
        "dbdlbdbxdlbdbdlb",
        "bdbxdbdlbdbdlbdb",
        "xdbdlbdbdlbdbdlb",
        "dbdlbdbxdlbdbdld",
        "bdlbdbdlbdbxdbdl",
        "dbdlbdblbdbdlbdb",
        "bdbxdbdlbdbxdbdl",
        "dbdlbdbxdlbdbdlb",
        "lbdbdlbdbdlbdbdl",
        "bdlbdbdlbdbdldbl",
    ), grain = 8)

    private val BEDROCK = TileArt(bedrockPal, listOf(
        "dkbdkbdldkbdkbdb",
        "bdlbkdbkdlbkdbkl",
        "kdklbdkbdkdbkldb",
        "dlbkdbkdlbkdbkdl",
        "bkdklbkdkblbkdkd",
        "kdlbkdbkdlbkdbkb",
        "dkbkdlbkdbkdlbkd",
        "lbkdbkdlbkdbkdlb",
        "bkdlbkdbkdlbkdbk",
        "kdbkdlbkdbkdlbkd",
        "dlbkdbkdlbkdbklb",
        "bkdbkdlbkdbkdlbk",
        "kdlbkdbklbkdbkdb",
        "dlbkdbkdlbkdbkdl",
        "bkdbkdlbkdbkdlbk",
        "kdlbkdbkdlbkdbkd",
    ), grain = 6)

    /** Bark: vertical grooves, the 'k' column being the deepest cut. */
    private val WOOD_SIDE = TileArt(barkPal, listOf(
        "bdlbdkbdlbdlbdkb",
        "bdlbdkbdlbdlbdkd",
        "bdlbdkbdlbdlbdkb",
        "bdlbkdbdlbdlbdkb",
        "bdlbdkbdlbdlbdkd",
        "bdlbdkbdlbdkbdkb",
        "bdlbdkbdlbdlbdkb",
        "bdlbkdbdlbdlbdkd",
        "bdlbdkbdlbdlbdkb",
        "bdlbdkbdlbdlbdkb",
        "bdlbkdbdlbdkbdkd",
        "bdlbdkbdlbdlbdkb",
        "bdlbdkbdlbdlbdkd",
        "bdlbkdbdlbdlbdkb",
        "bdlbdkbdlbdkbdkd",
        "bdlbdkbdlbdlbdkb",
    ), grain = 6)

    /** Log end grain: concentric square rings around a dark pith. */
    private val WOOD_TOP = TileArt(logTopPal, listOf(
        "bbbbbbbbbbbbbbbb",
        "bllllllllllllllb",
        "blbbbbbbbbbbbblb",
        "blbllllllllllblb",
        "blblbbbbbbbblblb",
        "blblbllllllblblb",
        "blblblbbbblblblb",
        "blblblbkkblblblb",
        "blblblbkkblblblb",
        "blblblbbbblblblb",
        "blblbllllllblblb",
        "blblbbbbbbbblblb",
        "blbllllllllllblb",
        "blbbbbbbbbbbbblb",
        "bllllllllllllllb",
        "bbbbbbbbbbbbbbbb",
    ), grain = 6)

    /** Dense cutout leaves: mostly solid, a scatter of holes to see through. */
    private val LEAVES = TileArt(
        leafPal,
        listOf(
            "bbdbbdbbdbbdbbdb",
            "bdbbbdbbd.bdbbdb",
            "dbbdbbdbbdbbbdb.",
            "bbdbbdbbbdbdbbd.",
            "bdbbdbbdbbbdbbdb",
            "bd.bdbdbbdbbdbbd",
            "dbbdbbdbbdbdbbb.",
            "bbdbbdbbdbbdbbdb",
            "bdbbdbbdbbd.bdbb",
            "dbbdbbdbbdbdbbd.",
            "bbdbbdbbd.bbdbdb",
            "bdbbd.bdbbdbbdbb",
            "dbbdbbdbbdbbdbb.",
            "bbdbbdbbdbbdbbdb",
            "bdbbdbbdbbd.bdbb",
            "dbbdbbdbbdbdbbd.",
        ),
        grain = 8,
        transparent = '.',
    )

    // Slot frames use a 2px border so the edge-bleed guard ring never eats it.
    private val SLOT = TileArt(slotPal, List(16) { y ->
        when (y) {
            0, 1, 14, 15 -> "kkkkkkkkkkkkkkkk"
            2, 13 -> "kllllllllllllldk"
            else -> "kliiiiiiiiiiiidk"
        }
    })

    private val SLOT_SELECTED = TileArt(slotPal, List(16) { y ->
        when (y) {
            0, 1, 14, 15 -> "wwwwwwwwwwwwwwww"
            2, 13 -> "wwlllllllllllldw"
            else -> "wliiiiiiiiiiiidw"
        }
    })

    private val PANEL = TileArt(panelPal, List(16) { "bbbbbbbbbbbbbbbb" }, grain = 3)

    /** Cutout crosshair, drawn over the world. */
    private val CROSSHAIR = TileArt(
        mapOf('.' to rgb(0, 0, 0), 'w' to rgb(255, 255, 255)),
        List(16) { y ->
            buildString {
                for (x in 0 until 16) {
                    val onV = x in 7..8 && y in 2..13
                    val onH = y in 7..8 && x in 2..13
                    append(if (onV || onH) 'w' else '.')
                }
            }
        },
        transparent = '.',
    )

    // --- Assembly ---

    private val tiles: Map<TileRef, TileArt> = mapOf(
        Atlas.GRASS_TOP to GRASS_TOP,
        Atlas.GRASS_SIDE to GRASS_SIDE,
        Atlas.DIRT to DIRT,
        Atlas.STONE to STONE,
        Atlas.WOOD_SIDE to WOOD_SIDE,
        Atlas.WOOD_TOP to WOOD_TOP,
        Atlas.LEAVES to LEAVES,
        Atlas.SLOT to SLOT,
        Atlas.SLOT_SELECTED to SLOT_SELECTED,
        Atlas.BEDROCK to BEDROCK,
        Atlas.PANEL to PANEL,
        Atlas.CROSSHAIR to CROSSHAIR,
    )

    /**
     * Renders every tile into the atlas buffer, upscaling art to fill its tile and then
     * bleeding the outermost pixels outward so mipmaps have guard pixels to average
     * against instead of reaching into the neighbouring tile.
     */
    fun buildAtlas(): ByteBuffer {
        val size = Atlas.TILES * Atlas.TILE_PX
        val pixels = IntArray(size * size) // 0xAARRGGBB

        for ((ref, art) in tiles) {
            val originX = ref.tx * Atlas.TILE_PX
            val originY = ref.ty * Atlas.TILE_PX
            renderTile(pixels, size, originX, originY, art)
        }

        val buffer = java.nio.ByteBuffer.allocateDirect(size * size * 4)
        for (p in pixels) {
            buffer.put(((p ushr 16) and 0xFF).toByte()) // R
            buffer.put(((p ushr 8) and 0xFF).toByte())  // G
            buffer.put((p and 0xFF).toByte())          // B
            buffer.put(((p ushr 24) and 0xFF).toByte()) // A
        }
        buffer.flip()
        return buffer
    }

    private fun renderTile(pixels: IntArray, size: Int, ox: Int, oy: Int, art: TileArt) {
        val t = Atlas.TILE_PX
        val up = Atlas.UPSCALE
        val pad = Atlas.PAD

        // 1. Art -> upscaled tile.
        for (ay in 0 until Atlas.ART_PX) {
            val row = art.rows[ay]
            for (ax in 0 until Atlas.ART_PX) {
                val ch = row[ax]
                val isHole = ch == art.transparent
                val color = art.palette.getValue(ch)
                val delta = if (isHole) 0 else grainAt(ax, ay, art.grain, art.grain)
                val r = (((color ushr 16) and 0xFF) + delta).coerceIn(0, 255)
                val g = (((color ushr 8) and 0xFF) + delta).coerceIn(0, 255)
                val b = ((color and 0xFF) + delta).coerceIn(0, 255)
                val alpha = if (isHole) 0 else 0xFF
                val packed = (alpha shl 24) or (r shl 16) or (g shl 8) or b

                for (uy in 0 until up) {
                    for (ux in 0 until up) {
                        pixels[(oy + ay * up + uy) * size + (ox + ax * up + ux)] = packed
                    }
                }
            }
        }

        // 2. Edge bleed: replicate the outer ring inward-facing pixels across the border,
        //    so a mipmapped texel that reaches past the tile edge still samples this tile.
        for (i in 0 until pad) {
            for (x in 0 until t) {
                val topRow = (oy + pad) * size + (ox + x)
                val bottomRow = (oy + t - 1 - pad) * size + (ox + x)
                for (k in 0 until pad) {
                    pixels[(oy + k) * size + (ox + x)] = pixels[topRow]
                    pixels[(oy + t - 1 - k) * size + (ox + x)] = pixels[bottomRow]
                }
            }
        }
        for (j in 0 until t) {
            val leftCol = (oy + j) * size + (ox + pad)
            val rightCol = (oy + j) * size + (ox + t - 1 - pad)
            for (k in 0 until pad) {
                pixels[(oy + j) * size + (ox + k)] = pixels[leftCol]
                pixels[(oy + j) * size + (ox + t - 1 - k)] = pixels[rightCol]
            }
        }
    }
}
