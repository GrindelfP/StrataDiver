package check

import org.joml.Vector2f
import org.joml.Vector3f
import to.grindelf.stratadiver.*

/**
 * Headless checks over everything that does not need a GL context.
 * Run with target/classes on the classpath.
 */
var failures = 0

fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) println("  ok   $name") else { failures++; println("  FAIL $name  $detail") }
}

/**
 * [check] with a detail that is only built when the check fails.
 *
 * The String overload evaluates its detail on every run, which has two costs. It spends
 * time on checks that passed — one of them filters the whole Block Type enum — and, worse,
 * the detail is built *outside* any `run { }` wrapping the condition, so it cannot see a
 * local the condition just computed. That produces `unresolved reference` rather than a
 * wrong answer, which is at least loud, but it has caught this file twice now.
 */
fun check(name: String, condition: Boolean, detail: () -> String) {
    if (condition) println("  ok   $name") else { failures++; println("  FAIL $name  ${detail()}") }
}

fun main() {
    println("\n== Meshing: the numbers the session log recorded before the split ==")
    // The mesher needs a Neighbourhood, and the honest one is the world itself. Two are
    // in play deliberately:
    //
    //   IN_WORLD   — every Chunk in the sample created first, so border faces are culled
    //                against the Chunk actually next door.
    //   CHUNK_ONLY — reads the Chunk's own Cells and says AIR outside them, which is
    //                exactly what `Chunk.getBlock` did when the mesher asked it about the
    //                next door. This is the old behaviour, reproduced rather than
    //                remembered, so the pre-fix figure stays checkable.
    //
    // Comparing them is the assertion. The absolute counts are regression guards; the
    // difference between the two is the bug.
    val sample = mutableListOf<Triple<Int, Int, Int>>()
    for (cx in 0 until 4) for (cz in 0 until 4) for (cy in 0 until 16) sample.add(Triple(cx, cy, cz))
    val sampleChunks = sample.map { (cx, cy, cz) -> Chunk(cx, cy, cz) }

    val inWorld = object : Neighbourhood {
        val byPosition = sampleChunks.associateBy { it.position }
        override fun blockAtOrNull(x: Int, y: Int, z: Int): BlockType? {
            val cx = Math.floorDiv(x, 16); val cy = Math.floorDiv(y, 16); val cz = Math.floorDiv(z, 16)
            val chunk = byPosition[Triple(cx, cy, cz)] ?: return null
            return BlockType.getById(
                chunk.getBlock(Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16))
            )
        }
    }

    /** The old mesher's whole model of its neighbours: this Chunk, and AIR past its edge. */
    fun chunkOnlyNeighbourhood(chunk: Chunk) = object : Neighbourhood {
        override fun blockAtOrNull(x: Int, y: Int, z: Int): BlockType = BlockType.getById(
            chunk.getBlock(x - chunk.xOffset * 16, y - chunk.yOffset * 16, z - chunk.zOffset * 16)
        )
    }

    val chunkOnly = chunkOnlyNeighbourhood(sampleChunks.first())

    var total = 0
    var allAir = 0
    var chunkOnlyTotal = 0
    var chunkOnlyNonEmpty = 0
    var chunksGained = 0
    for (chunk in sampleChunks) {
        val vertices = MeshGenerator.generateChunkMesh(chunk, inWorld).size / Geometry.POS_UV_NORMAL
        val naive = MeshGenerator.generateChunkMesh(chunk, chunkOnlyNeighbourhood(chunk))
            .size / Geometry.POS_UV_NORMAL
        total += vertices
        chunkOnlyTotal += naive
        if (vertices == 0) allAir++
        if (naive > 0) chunkOnlyNonEmpty++
        if (naive > vertices) chunksGained++
    }
    println("  4x4x16 sample: $total vertices in-world, $chunkOnlyTotal chunk-only, " +
            "$allAir/${sampleChunks.size} chunks all-air")

    // The figure the session log recorded, reproduced through the old neighbour model. If
    // this moves, the refactor changed what the mesher does when nothing is next door —
    // which is the one thing it was supposed to stop doing.
    check("chunk-only culling still reproduces the pre-fix figure",
        chunkOnlyTotal == 1_213_368, "was $chunkOnlyTotal, expected 1213368")
    check("in-world total is stable run to run", run {
        val again = sampleChunks.sumOf {
            MeshGenerator.generateChunkMesh(it, inWorld).size / Geometry.POS_UV_NORMAL
        }
        again == total
    }, "re-ran to a different number")
    check("border culling removes geometry", total < chunkOnlyTotal,
        "in-world $total, chunk-only $chunkOnlyTotal")
    // Every Chunk that drew anything under the old model now draws strictly less. Not a
    // sample: all of them. A partially-fixed culler leaves a few Chunks still paying for
    // a shell nobody can see, and it is exactly those that never show up in a screenshot.
    check("every Chunk that drew anything now draws less", chunksGained == chunkOnlyNonEmpty,
        "$chunksGained of $chunkOnlyNonEmpty non-empty Chunks gained anything")
    check("more Chunks are left with nothing to draw", allAir > 117,
        "was 117 before the fix, now $allAir")

    // The seam itself: a solid block against the next Chunk over must lose the face that
    // used to be drawn inside it. A Chunk above the terrain is all air, so one Cell
    // written into it is the entire world of the test — the smallest world in which the
    // old code was wrong, and the only one where the face count is readable.
    val seam = Chunk(0, 15, 0)
    seam.setBlock(0, 0, 0, BlockType.STONE)
    val seamWorld = object : Neighbourhood {
        override fun blockAtOrNull(x: Int, y: Int, z: Int) =
            if (x == 1 && y == 240 && z == 0) BlockType.STONE else BlockType.AIR
    }
    fun facesOf(n: Neighbourhood) =
        MeshGenerator.generateChunkMesh(seam, n).size / Geometry.POS_UV_NORMAL / 6
    check("a lone Cell draws 6 faces", facesOf(chunkOnly) == 6, "got ${facesOf(chunkOnly)}")
    check("a Cell with solid next door draws 5, not 6", facesOf(seamWorld) == 5,
        "got ${facesOf(seamWorld)}")

    println("\n== Generation is reproducible ==")
    fun cells(x: Int, y: Int, z: Int) = Chunk(x, y, z).let { c ->
        ByteArray(16 * 16 * 16) { i ->
            c.getBlock(i / 256, (i / 16) % 16, i % 16)
        }
    }
    check("same position twice is byte-identical", cells(3, 8, -2).contentEquals(cells(3, 8, -2)))
    check("different positions differ", !cells(3, 8, -2).contentEquals(cells(4, 8, -2)))

    println("\n== Slot layout: the arithmetic that was written out three times ==")
    val layout = SlotLayout(1280f, 720f)
    check("hotbar top edge", layout.hotbarTop == 660f, "was ${layout.hotbarTop}")
    check("main grid top edge", layout.mainGridTop == 490f, "was ${layout.mainGridTop}")
    check("main grid clears the hotbar", layout.mainGridTop + 3 * layout.slotSize < layout.hotbarTop)
    check("panel stops above the hotbar", layout.mainPanel.bottom < layout.hotbarTop)
    check("grid is centred", layout.mainSlot(0, 0).x == (1280f - 450f) / 2f, "was ${layout.mainSlot(0, 0).x}")

    // Every slot must be reachable, and the hotbar must stay clickable while the
    // inventory is open — that is the bug that came out of duplicating this arithmetic.
    val hits = (0 until 27).map { i ->
        val slot = layout.mainSlot(i % 9, i / 9)
        layout.slotAt(slot.x + 25f, slot.y + 25f)
    }
    check("all 27 main slots hit-test to themselves",
        hits.indices.all { hits[it] == SlotRef(SlotContainer.MAIN, it) }, "got $hits")
    check("all 9 hotbar slots hit-test even with the grid open",
        (0 until 9).all { i ->
            val slot = layout.hotbarSlot(i)
            layout.slotAt(slot.x + 25f, slot.y + 25f) == SlotRef(SlotContainer.HOTBAR, i)
        })
    check("a point between the panels hits nothing", layout.slotAt(5f, 5f) == null)

    println("\n== Geometry shapes ==")
    check("unit quad is 6 vertices", UNIT_QUAD.size == 6 * Geometry.POS_UV)
    check("unit cube is 36 vertices", UNIT_CUBE.size == 36 * Geometry.POS_UV_NORMAL)
    check("selection box is 24 line vertices", SELECTION_BOX.size == 24 * Geometry.POS_UV)
    val lineU = 0.5f / Atlas.TILES
    val lineV = 3.5f / Atlas.TILES
    check("every selection vertex samples the centre of Atlas.LINE",
        (0 until SELECTION_BOX.size step Geometry.POS_UV).all {
            SELECTION_BOX[it + 3] == lineU && SELECTION_BOX[it + 4] == lineV
        })

    println("\n== Player physics against a flat world ==")
    // Ground at y=0: the top of that cell is the surface at y=1.
    val flat = object : SolidWorld { override fun isSolid(x: Float, y: Float, z: Float) = y < 1f }
    val player = Player(flat)
    player.position.set(0.5f, 5f, 0.5f)
    repeat(200) { player.step() }
    // position is the *eye*, and the body hangs Player.HEIGHT below it, so standing on
    // y=1 means the eye rests at 1 + 1.8. Asserting the feet, not the eye, is what makes
    // this a test of the physics rather than of where we happen to call the camera.
    //
    // The tolerance is one gravity step, not a fudge: collision is resolved per frame, so
    // a resting player stops up to |GRAVITY| short of the surface. Landing *below* the
    // surface would be the real bug, and is asserted separately.
    val gravityStep = 0.015f
    fun checkResting(who: String, p: Player) {
        val feet = p.position.y - Player.HEIGHT
        check("$who does not sink into the ground", feet >= 1f, "feet=$feet")
        check("$who does not float above it", feet < 1f + gravityStep, "feet=$feet")
        check("$who is grounded", p.isGrounded)
    }
    checkResting("a falling player", player)
    repeat(120) { player.step() }
    checkResting("a landed player", player)

    val walled = object : SolidWorld { override fun isSolid(x: Float, y: Float, z: Float) = y < 1f || x > 1f }
    val walker = Player(walled)
    walker.position.set(0.5f, 1f, 0.5f)
    walker.moveInput(forward = true, back = false, left = false, right = false, jump = false)
    repeat(60) { walker.step() }
    check("a wall stops horizontal movement", walker.position.x <= 1f + Player.WIDTH,
        "x=${walker.position.x}")

    println("\n== Raycast ==")
    val wall = object : SolidWorld {
        override fun isSolid(x: Float, y: Float, z: Float) = y in 2f..3f && x >= 1f
    }
    val looker = Player(wall)
    looker.position.set(0.5f, 2.5f, 0.5f)
    looker.look(deltaX = 0.0, deltaY = 0.0, sensitivity = 1f) // faces -Z; aim +X instead
    looker.front.set(1f, 0f, 0f)
    val hit = looker.raycast()
    check("finds the cell in front", hit?.blockPos?.x == 1, "got ${hit?.blockPos}")
    check("reports the face it entered through", hit?.faceNormal?.x == -1, "got ${hit?.faceNormal}")
    looker.front.set(0f, 0f, 1f)
    check("nothing within reach", looker.raycast() == null)

    println("\n== Block interactions the game depends on ==")
    check("grass drops dirt", BlockType.GRASS.drop == BlockType.DIRT)
    check("leaves drop nothing", BlockType.LEAVES.drop == BlockType.AIR)
    check("stone drops itself", BlockType.STONE.drop == BlockType.STONE)
    check("cutouts are solid but not opaque", BlockType.LEAVES.opaque.not())
    check("bedrock is the floor of the world", BlockType.BEDROCK.id == 4.toByte())

    println("\n== Light: how a Cell's two levels are packed ==")
    // The packing has one failure mode worth guarding: an id or a level that spills out of
    // its nibble silently turns into a neighbouring level. Round-tripping every value is
    // the only check that would notice.
    check("every level survives the round trip", run {
        (0..15).all { d -> (0..15).all { t -> Light.pack(d, t).let {
            Light.day(it) == d && Light.torch(it) == t
        } } }
    })
    check("the two levels do not bleed into each other", run {
        val fullTorch = Light.pack(day = 0, torch = 15)
        val fullDay = Light.pack(day = 15, torch = 0)
        Light.torch(fullTorch) == 15 && Light.day(fullTorch) == 0 &&
            Light.torch(fullDay) == 0 && Light.day(fullDay) == 15
    })
    check("a packed level is one byte", Light.pack(15, 15) in 0..255)

    check("a fresh Cell is dark", run {
        val dark = Chunk(0, 15, 0)
        (0 until 16).all { x -> (0 until 16).all { y -> dark.packedLightAt(x, y, 0) == Light.DARK } }
    })
    check("writing a Cell invalidates only that Cell's light", run {
        val lit = Chunk(0, 15, 0)
        lit.setPackedLight(3, 4, 5, Light.pack(day = 15, torch = 9))
        val before = lit.packedLightAt(4, 4, 5)
        lit.setBlock(3, 4, 5, BlockType.STONE)
        lit.packedLightAt(3, 4, 5) == Light.DARK && lit.packedLightAt(4, 4, 5) == before
    }, "a rebuilt wall must stop the light it used to pass")
    check("light outside the Chunk reads dark and writes nothing", run {
        val lit = Chunk(0, 15, 0)
        lit.packedLightAt(16, 0, 0) == Light.DARK && lit.packedLightAt(-1, 0, 0) == Light.DARK
        lit.setPackedLight(0, 16, 0, Light.pack(15, 15))
        lit.packedLightAt(0, 16, 0) == Light.DARK
    })

    println("\n== Light: what a Block Type does to it ==")
    check("exactly one Block Type emits", BlockType.entries.count { it.emission > 0 } == 1)
    check("no Block Type claims to be brighter than full", BlockType.entries.all { it.emission in 0..15 })
    check("attenuation is only claimed by things light passes through", run {
        BlockType.entries.none { it.opaque && it.attenuation > 0 }
    })
    check("leaves dim light without blocking it", run {
        BlockType.LEAVES.opaque.not() && BlockType.LEAVES.attenuation > 0
    }, "a forest floor should be dimmer than a clearing")
    check("AIR costs a step and nothing more", BlockType.AIR.attenuation == 0)

    // An emitter that is not reachable is not a light source. GLOWSTONE has to be buried in
    // the stone, not floating in the sky, or the whole Torchlight channel is decoration.
    check("an emitter is a solid, so it has to be dug out", BlockType.GLOWSTONE.opaque)
    check("it drops itself, so digging it out yields light", BlockType.GLOWSTONE.drop == BlockType.GLOWSTONE)
    check("it has a Material, so walking on it is audible", BlockType.GLOWSTONE.material != null)
    check("it emits less than full daylight", run {
        BlockType.GLOWSTONE.emission in 1..Light.MAX - 1
    }, "emitted ${BlockType.GLOWSTONE.emission}")

    println("\n== Light: the atlas has room for exactly what it holds ==")
    val usedTiles = setOf(
        Atlas.GRASS_TOP, Atlas.GRASS_SIDE, Atlas.DIRT, Atlas.STONE, Atlas.WOOD_SIDE,
        Atlas.WOOD_TOP, Atlas.LEAVES, Atlas.GLOWSTONE, Atlas.SLOT, Atlas.SLOT_SELECTED,
        Atlas.BEDROCK, Atlas.PANEL, Atlas.CROSSHAIR, Atlas.LINE
    )
    check("no two Tiles share a slot", usedTiles.size == usedTiles.map { it.tx to it.ty }.toSet().size)
    check("every Tile is inside the atlas", usedTiles.all {
        it.tx in 0 until Atlas.TILES && it.ty in 0 until Atlas.TILES
    })
    val freeSlots = (0 until Atlas.TILES).flatMap { tx ->
        (0 until Atlas.TILES).mapNotNull { ty ->
            if ((tx to ty) in usedTiles.map { it.tx to it.ty }) null else tx to ty
        }
    }
    println("  free atlas slots: $freeSlots")
    check("the atlas is not silently full", freeSlots.isNotEmpty(),
        "the next Block Type has to grow the atlas to 8x8")

    // Every Block Type must be drawable, or its Icon samples a tile that was never authored
    // and the atlas there is whatever was in the buffer — silent, and wrong.
    //
    // `filter` first, not `all { it != AIR && ... }`. `all` returns false the instant any
    // element fails, so excluding AIR inside the predicate excludes nothing at all: AIR is
    // still in the collection, still evaluated, and still fails. Written this way the
    // assertion is false for every possible program, which is worth recognising — a check
    // that cannot pass is not a weak check, it is a broken one.
    val authored = usedTiles
    val drawable = BlockType.entries.filter { it != BlockType.AIR }
    check("every drawable Block Type has a Tile that was actually authored", run {
        drawable.all { it.primary in authored }
    }, "missing ${drawable.filter { it.primary !in authored }.map { it.name }}")
    check("and there is more than one of them", drawable.size >= 6, "only ${drawable.size}")
    check("the atlas renders without throwing", Art.buildAtlas().remaining() == Atlas.TILES * Atlas.TILE_PX * Atlas.TILES * Atlas.TILE_PX * 4)

    println("\n== Light: glowstone is buried, and the same vein twice ==")
    // Veins have to be rare enough to be worth finding and common enough to exist. Rather
    // than assert a number nobody chose on purpose, measure the distribution and assert the
    // two properties that actually matter: most Chunks have none, and a Chunk that has one
    // has a *small* one.
    var chunksWithVein = 0
    var veinCells = 0
    var biggestVein = 0
    val veinSample = 20 * 20 * Chunk.HEIGHT_LAYERS
    for (cx in 0 until 20) for (cz in 0 until 20) for (cy in 0 until 16) {
        val chunk = Chunk(cx, cy, cz)
        var here = 0
        for (x in 0 until 16) for (y in 0 until 16) for (z in 0 until 16) {
            if (chunk.getBlock(x, y, z) == BlockType.GLOWSTONE.id) here++
        }
        if (here > 0) { chunksWithVein++; veinCells += here; biggestVein = maxOf(biggestVein, here) }
    }
    val fraction = chunksWithVein.toFloat() / veinSample
    println("  $chunksWithVein/$veinSample Chunks hold glowstone (${"%.0f".format(fraction * 100)}%), " +
            "largest vein $biggestVein, mean ${"%.1f".format(veinCells.toFloat() / chunksWithVein)}")
    check("most Chunks have none", fraction < 0.35f, "$fraction of Chunks had one")
    check("but it does occur", fraction > 0.01f, "$fraction of Chunks had one — nothing to find")
    check("a vein is a vein, not a cavern", biggestVein <= Chunk.GLOWSTONE_VEIN_MAX,
        "largest vein was $biggestVein")
    check("veins only replace stone", run {
        // A vein in a Chunk with no stone at all must produce nothing rather than hang in
        // the air, which is the failure a bounded search exists to prevent.
        val sky = Chunk(0, 15, 0)
        var count = 0
        for (x in 0 until 16) for (y in 0 until 16) for (z in 0 until 16) {
            if (sky.getBlock(x, y, z) == BlockType.GLOWSTONE.id) count++
        }
        count == 0
    })
    check("generating the same Chunk twice gives the same vein", run {
        fun veins(cx: Int, cy: Int, cz: Int) = Chunk(cx, cy, cz).let { c ->
            ByteArray(16 * 16 * 16) { i ->
                if (c.getBlock(i / 256, (i / 16) % 16, i % 16) == BlockType.GLOWSTONE.id) 1 else 0
            }
        }
        veins(7, 5, -3).contentEquals(veins(7, 5, -3))
    })

    println("\n== FPS seven-segment table ==")
    // Named after this exact class of bug: hand-written masks were wrong for 9 of 10
    // digits, and only a human looking at the picture would catch it.
    val segments = FpsCounter::class.java.getDeclaredField("SEGMENT_DIGITS").apply { isAccessible = true }
        .let { (it.get(FpsCounter(250)) as Array<String>) }
    val expected = listOf(
        "abcdef" to "0", "bc" to "1", "abdeg" to "2", "abcdg" to "3", "bcfg" to "4",
        "acdfg" to "5", "acdefg" to "6", "abc" to "7", "abcdefg" to "8", "abcdfg" to "9"
    )
    expected.forEachIndexed { i, (name, digit) ->
        check("digit $digit lights $name", segments[i] == name, "got ${segments[i]}")
        check("digit $digit has no duplicate segments", segments[i].toSet().size == segments[i].length)
    }

    println("\n== Minimap: Surfaces are read from live Chunks ==")
    val streamer = ChunkStreamer()
    // Fully generated, so every column has an answer and nothing reads as Unknown.
    streamer.ensureSurfaceKnown(0, 0, Minimap.coverageRadius(64), budget = 100_000)

    fun colourAt(x: Int, z: Int): Int? {
        val surface = streamer.surfaceAt(x, z)
        return surface?.let { Minimap.colourOf(it.type) }
    }

    check("a column over natural ground has a Surface", colourAt(0, 0) != null)
    check("every Column in the covered area is known",
        (-40..40 step 8).all { x -> (-40..40 step 8).all { z -> colourAt(x, z) != null } })

    // The topmost block must actually be the top: write high above the terrain and check
    // the map reports the new block, not the ground it covers.
    //
    // Written through `Chunk.setBlock` rather than `ChunkStreamer.setBlock`, and that is a
    // real limitation rather than a convenience: the streamer's write path also re-meshes,
    // which is a GL call, so the only way to exercise "the map follows what you build"
    // headlessly is to bypass it. `Chunk.setBlock` makes no GL calls and is what the
    // streamer calls underneath, so the cache-invalidation path under test is the real one
    // — but the remesh half of `setBlock` is verified only by a playtest.
    //
    // `chunkAt` takes WORLD coordinates, not grid ones. Passing a Chunk layer here floors
    // to layer 0 and writes into the bedrock band, which looks like a broken minimap and
    // is not one.
    val towerY = streamer.surfaceAt(0, 0)!!.worldY + 20
    val tower = streamer.chunkAt(0, towerY, 0)!!

    check("placing high above terrain is reported", run {
        tower.setBlock(0, Math.floorMod(towerY, 16), 0, BlockType.WOOD)
        colourAt(0, 0) == Minimap.colourOf(BlockType.WOOD)
    }, "got ${Integer.toHexString(colourAt(0, 0) ?: 0)}")

    check("removing it again reveals what it covered", run {
        tower.setBlock(0, Math.floorMod(towerY, 16), 0, BlockType.AIR)
        colourAt(0, 0) == Minimap.colourOf(BlockType.GRASS)
    }, "got ${Integer.toHexString(colourAt(0, 0) ?: 0)}")

    // A dug-out pit must become the new Surface — that is what "topmost non-air" means.
    check("digging down makes the new floor the Surface", run {
        val g = streamer.surfaceAt(0, 0)!!.worldY
        streamer.chunkAt(0, g, 0)!!.setBlock(0, Math.floorMod(g, 16), 0, BlockType.AIR)
        val s = streamer.surfaceAt(0, 0)!!
        s.type == BlockType.DIRT && s.worldY == g - 1
    }, "got ${streamer.surfaceAt(0, 0)}")

    println("\n== Minimap: Unknown is not empty ==")
    val fresh = ChunkStreamer()
    check("a world with no Chunks yet reports Unknown, not empty",
        fresh.surfaceAt(5000, 5000) == null)
    check("Unknown has its own colour", Minimap.UNKNOWN_COLOUR != Minimap.EMPTY_COLOUR)
    check("the two greens are actually distinguishable", run {
        val g = Minimap.colourOf(BlockType.GRASS)!!
        val l = Minimap.colourOf(BlockType.LEAVES)!!
        val dr = (g shr 16) - (l shr 16)
        val dg = (g shr 8 and 0xFF) - (l shr 8 and 0xFF)
        val db = (g and 0xFF) - (l and 0xFF)
        // Perceptually far enough apart to tell apart at ~2px.
        kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db) > 90
    }, "grass=${Integer.toHexString(Minimap.colourOf(BlockType.GRASS)!!)} " +
        "leaves=${Integer.toHexString(Minimap.colourOf(BlockType.LEAVES)!!)}")

    println("\n== Minimap: the grid and its bytes ==")
    val map = Minimap(streamer, cells = 16)
    map.refreshIfDue(Vector3f(0.5f, 130f, 0.5f), 0L)
    check("one pixel per Cell", map.pixels.size == 16 * 16)
    val rgba = map.toRgbaBytes()
    check("four bytes per pixel, RGBA", rgba.remaining() == 16 * 16 * 4)
    check("north row first: row 0 is the north edge, so it is known", run {
        // Row 0 must not be the Unknown tone anywhere in a fully generated area.
        (0 until 16).all { c ->
            val px = map.pixels[c]
            ((px ushr 16) and 0xFF) or ((px ushr 8) and 0xFF) or (px and 0xFF) !=
                ((Minimap.UNKNOWN_COLOUR ushr 16) and 0xFF) or
                ((Minimap.UNKNOWN_COLOUR ushr 8) and 0xFF) or
                (Minimap.UNKNOWN_COLOUR and 0xFF)
        }
    })
    // The map is rebuilt centred on the player, so the middle Cell of the grid is the
    // Cell they are standing over — and must therefore be their Surface's colour.
    check("the middle Cell is the one under the player",
        map.pixels[8 * 16 + 8] == Minimap.colourOf(streamer.surfaceAt(0, 0)!!.type),
        "got ${Integer.toHexString(map.pixels[8 * 16 + 8])}")
    check("a refresh that is not due reports that it did nothing", run {
        val before = map.pixels.copyOf()
        !map.refreshIfDue(Vector3f(0.5f, 130f, 0.5f), 1L) && before.contentEquals(map.pixels)
    })
    check("an invalidate forces the next refresh to rebuild even when not due", run {
        map.invalidate()
        map.refreshIfDue(Vector3f(0.5f, 130f, 0.5f), 1L)
    })
    check("the interval elapsing forces a rebuild without an invalidate", run {
        map.refreshIfDue(Vector3f(0.5f, 130f, 0.5f), 1L + map.refreshIntervalNanos)
    })
    // A map built at a new position must show that position's ground, and the middle Cell
    // must be the Cell under the player — the property the player marker relies on, and
    // the one that breaks first if the map is ever rebuilt off-centre.
    val moved = Minimap(streamer, cells = 16)
    moved.refreshIfDue(Vector3f(20.5f, 130f, 12.5f), 0L)
    check("moving the player re-centres the map",
        moved.pixels[8 * 16 + 8] == Minimap.colourOf(streamer.surfaceAt(20, 12)!!.type),
        "got ${Integer.toHexString(moved.pixels[8 * 16 + 8])}")

    println("\n== Minimap: the heading must be a unit vector at every pitch ==")
    // The player marker is drawn by measuring a fragment's distance perpendicular to the
    // heading and its distance along it. Both tests are only meaningful for a unit vector,
    // and the raw look direction is a unit *3D* vector whose horizontal part is cos(pitch).
    // Handing that over raw made the marker's size 1/cos(pitch): fine looking level, and
    // at 89° the permitted width is 0.69 against a disc of radius 0.5, so the tick
    // covered the map. Asserting the length is the whole regression test.
    val headed = Minimap(streamer, cells = 16)
    val looker2 = Player(object : SolidWorld { override fun isSolid(x: Float, y: Float, z: Float) = false })

    check("heading is unit length looking level",
        run {
            looker2.front.set(1f, 0f, 0f)
            kotlin.math.abs(headed.heading(looker2.front).length() - 1f) < 1e-4f
        })

    // Sweep every pitch the player can actually reach. This is the regression test for
    // the marker growing as you look up.
    var worstPitch = Int.MIN_VALUE
    var worstDeviation = -1f
    for (pitchDegrees in -89..89) {
        val pitch = Math.toRadians(pitchDegrees.toDouble())
        // Reproduce exactly what Player.look produces: a unit 3D direction.
        looker2.front.set(
            kotlin.math.cos(pitch).toFloat(),
            kotlin.math.sin(pitch).toFloat(),
            0.6f
        ).normalize()
        val deviation = kotlin.math.abs(headed.heading(looker2.front).length() - 1f)
        if (deviation > worstDeviation) {
            worstDeviation = deviation
            worstPitch = pitchDegrees
        }
    }
    check("heading is unit length at EVERY pitch from -89 to +89",
        worstDeviation < 1e-4f,
        "worst deviation $worstDeviation at pitch $worstPitch")

    check("looking straight up does not send the heading to zero", run {
        looker2.front.set(0f, 1f, 0f)
        val h = headed.heading(looker2.front)
        kotlin.math.abs(h.length() - 1f) < 1e-4f
    })

    // A glance at the sky must not spin the marker: the heading the player last had while
    // looking horizontally is still the true heading.
    looker2.front.set(0f, 0f, 1f)   // facing south, horizontal
    val heldHeading = Vector2f(headed.heading(looker2.front))
    looker2.front.set(0f, 1f, 0f)   // now straight up
    val duringVertical = headed.heading(looker2.front)
    check("a vertical glance keeps the last usable heading rather than snapping",
        heldHeading.x == duringVertical.x && heldHeading.y == duringVertical.y,
        "before=$heldHeading during=$duringVertical")

    check("a vertical view before any horizontal one still gets a unit heading", run {
        val virgin = Minimap(streamer, cells = 8)
        looker2.front.set(0f, 1f, 0f)
        kotlin.math.abs(virgin.heading(looker2.front).length() - 1f) < 1e-4f
    })

    check("heading matches yaw when the player looks along it", run {
        val yaw = 2.5 // radians, not axis-aligned
        looker2.front.set(kotlin.math.cos(yaw).toFloat(), 0f, kotlin.math.sin(yaw).toFloat()).normalize()
        val h = headed.heading(looker2.front)
        kotlin.math.abs(h.x - looker2.front.x) < 1e-5f && kotlin.math.abs(h.y - looker2.front.z) < 1e-5f
    })

    // ==================================================================================
    // The light engine.
    //
    // Driven against a world of exactly the Chunks a test asks for, all air. Everything
    // here is arithmetic over Cell contents, so all of it is reachable without a window —
    // which is the point. Light has more ways to be subtly wrong than anything else in
    // this codebase, and nearly every one of them produces a plausible picture rather
    // than an error: a sealed room that stays lit after you wall it off renders
    // perfectly. Only the numbers say it is wrong, and no amount of looking at a
    // screenshot will ever say it.
    // ==================================================================================

    /** A [LitWorld] with no terrain and no GL: only the Chunks a test asks for, all air. */
    class FlatWorld : LitWorld {
        private val chunks = LinkedHashMap<Triple<Int, Int, Int>, Chunk>()

        fun create(cx: Int, cy: Int, cz: Int): Chunk = chunks.getOrPut(Triple(cx, cy, cz)) {
            Chunk(cx, cy, cz).also { c ->
                for (x in 0 until 16) for (y in 0 until 16) for (z in 0 until 16) {
                    c.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }

        /** Creates a block of Chunks: 2*rx+1 across, [ry] tall, 2*rz+1 deep. */
        fun createAll(rx: Int = 1, ry: Int = 3, rz: Int = 1) {
            for (cx in -rx..rx) for (cy in 0 until ry) for (cz in -rz..rz) create(cx, cy, cz)
        }

        fun all() = chunks.values.toList()

        /** Writes a Cell, in world coordinates, creating whatever Chunk holds it. */
        fun set(x: Int, y: Int, z: Int, type: BlockType) {
            create(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16))
                .setBlock(Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16), type)
        }

        /** Writes an inclusive box of Cells, in world coordinates. */
        fun fill(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, type: BlockType) {
            for (x in x0..x1) for (y in y0..y1) for (z in z0..z1) set(x, y, z, type)
        }

        private fun chunkAt(x: Int, y: Int, z: Int) =
            chunks[Triple(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16))]

        private fun lx(x: Int) = Math.floorMod(x, 16)
        private fun ly(y: Int) = Math.floorMod(y, 16)
        private fun lz(z: Int) = Math.floorMod(z, 16)

        override fun existingChunkAt(x: Int, y: Int, z: Int) = chunkAt(x, y, z)

        override fun blockAtOrNull(x: Int, y: Int, z: Int): BlockType? =
            chunkAt(x, y, z)?.let { BlockType.getById(it.getBlock(lx(x), ly(y), lz(z))) }

        override fun lightAt(x: Int, y: Int, z: Int): Int =
            chunkAt(x, y, z)?.packedLightAt(lx(x), ly(y), lz(z)) ?: Light.DARK

        override fun setLight(x: Int, y: Int, z: Int, packed: Int): Chunk? =
            chunkAt(x, y, z)?.also { it.setPackedLight(lx(x), ly(y), lz(z), packed) }
    }

    /** A world plus its engine, with the write path `ChunkStreamer` uses. */
    class LitFixture(rx: Int = 1, ry: Int = 3, rz: Int = 1) {
        val world = FlatWorld()
        val engine = LightEngine(world)

        init { world.createAll(rx, ry, rz) }

        fun lightEverything() = world.all().forEach { engine.lightChunk(it) }

        fun day(x: Int, y: Int, z: Int) = Light.day(world.lightAt(x, y, z))
        fun torch(x: Int, y: Int, z: Int) = Light.torch(world.lightAt(x, y, z))

        /** What `ChunkStreamer.setBlockData` does, in miniature. */
        fun change(x: Int, y: Int, z: Int, type: BlockType) {
            val was = world.lightAt(x, y, z)
            world.set(x, y, z, type)
            engine.onBlockChanged(x, y, z, was)
        }

        /**
         * A sealed room: x and z in 0..15, y in 0..15, walled in stone on every side, with
         * a roof at y=16..31 and optionally a shaft pierced through it at x=8, z=8.
         *
         * Sealed matters more than it looks. A room with a forgotten wall in it is lit by
         * the sky through that gap, every assertion about darkness then passes for the
         * wrong reason, and the whole section quietly tests nothing while looking thorough.
         */
        fun sealedRoom(shaft: Boolean = false) {
            world.fill(-17, 0, -17, 31, 15, 31, BlockType.STONE)
            world.fill(0, 0, 0, 15, 15, 15, BlockType.AIR)
            world.fill(-17, 16, -17, 31, 31, 31, BlockType.STONE)
            if (shaft) world.fill(8, 16, 8, 8, 31, 8, BlockType.AIR)
        }
    }

    /**
     * Daylight in a room lit only through a shaft bored at x=8, z=8 through the roof.
     *
     * Two rules, and the second surprises people:
     *
     *  - Light spreads to six neighbours at once, so travelling from the shaft to a Cell
     *    costs the *sum* of the offsets, not the greatest of them. Writing this as a
     *    maximum is the mistake that makes a whole room look wrong in a test while the
     *    engine is right.
     *  - Height does not appear at all, because the shaft is bored through the whole roof
     *    and so lights an entire Column. Every Cell of that Column is at full daylight by
     *    the sky rule before any spreading happens, and each layer of the room is then lit
     *    sideways from its own shaft Cell. A room lit through a single *hole* rather than
     *    a whole Column would fall off with depth as well; that is a different test.
     */
    fun daylightUnderShaft(x: Int, y: Int, z: Int): Int =
        if (x == 8 && z == 8) Light.MAX
        else (Light.MAX - kotlin.math.abs(x - 8) - kotlin.math.abs(z - 8)).coerceAtLeast(0)

    /** How many Cells of a box hold a daylight level other than the one expected. */
    fun LitFixture.countWrong(expected: (Int, Int, Int) -> Int): Int {
        var wrong = 0
        for (y in 0..15) for (z in 0..15) for (x in 0..15) if (day(x, y, z) != expected(x, y, z)) wrong++
        return wrong
    }

    /**
     * A real world with real terrain, lit, without touching a GL context.
     *
     * `ChunkStreamer.update` cannot be used here: it meshes, and meshing is a GL call. The
     * Chunks are created through `chunkAt` instead, which generates on demand and uploads
     * nothing — the same Chunks the streamer would have made, reached a different way.
     */
    fun litTerrain(cx: Int, cz: Int, side: Int, cy0: Int, layers: Int): ChunkStreamer {
        val streamer = ChunkStreamer()
        for (x in cx until cx + side) for (z in cz until cz + side) for (y in cy0 until cy0 + layers) {
            streamer.chunkAt(x * 16, y * 16, z * 16)
        }
        for (x in cx until cx + side) for (z in cz until cz + side) for (y in cy0 until cy0 + layers) {
            streamer.existingChunkAt(x * 16, y * 16, z * 16)?.let { streamer.light.lightChunk(it) }
        }
        return streamer
    }

    println("\n== Light: open sky is full daylight ==")
    run {
        val f = LitFixture()
        f.lightEverything()
        var notFull = 0
        var lit = 0
        for (y in 0 until 48) for (z in -16 until 32) for (x in -16 until 32) {
            if (f.day(x, y, z) != Light.MAX) notFull++
            if (f.torch(x, y, z) != 0) lit++
        }
        check("a Cell with nothing above it is at full daylight", f.day(8, 8, 8) == Light.MAX)
        check("and so is every Cell of a world with nothing in it", notFull == 0) { "$notFull Cells were not" }
        check("none of which is Torchlight, with nothing emitting", lit == 0) { "$lit Cells had Torchlight" }
    }

    println("\n== Light: a shaft in a roof, and what it does to the room below ==")
    run {
        val f = LitFixture()
        f.sealedRoom(shaft = true)
        f.lightEverything()

        // Straight down an open Column, daylight does not decay. This is the assertion that
        // catches it if a future change makes it decay by one per step like everything else.
        check("daylight falls a Column at full strength", (0..15).none { f.day(8, it, 8) != Light.MAX }) {
            "cells at ${(0..15).filter { f.day(8, it, 8) != Light.MAX }} were not full"
        }
        check("away from the shaft, light costs one level per Cell travelled",
            (0 until 16).all { f.day(it, 15, 8) == daylightUnderShaft(it, 15, 8) }) {
            "row y=15 is ${(0 until 16).map { f.day(it, 15, 8) }}"
        }
        check("diagonal travel costs two steps, not one", f.day(9, 15, 9) == 13, "got ${f.day(9, 15, 9)}")
        check("and the whole 16x16x16 room matches that rule", f.countWrong(::daylightUnderShaft) == 0) {
            "${f.countWrong(::daylightUnderShaft)} Cells differ"
        }
        check("a Column shaft lights the room the same at every depth",
            (0..15).all { y -> (0..15).all { z -> f.day(0, y, z) == f.day(0, 15, z) } }) {
            "the room is lit per-layer from its shaft Column, so depth does not enter"
        }
        check("the roof is opaque to it", f.day(8, 16, 0) == Light.DARK)
        check("open sky above the roof is untouched", f.day(8, 32, 8) == Light.MAX)
    }

    println("\n== Light: an emitter lights a room the sky cannot reach ==")
    run {
        val f = LitFixture()
        f.sealedRoom()
        f.world.set(8, 15, 8, BlockType.GLOWSTONE)   // set into the room's ceiling
        f.lightEverything()

        // A single Cell of emission, so the falloff is three-dimensional: unlike a shaft,
        // there is no Column of it to light the room evenly from top to bottom.
        fun expected(x: Int, y: Int, z: Int) =
            (BlockType.GLOWSTONE.emission -
                    (kotlin.math.abs(x - 8) + kotlin.math.abs(y - 15) + kotlin.math.abs(z - 8)))
                .coerceAtLeast(0)

        check("the emitter sits at its own level", f.torch(8, 15, 8) == BlockType.GLOWSTONE.emission)
        check("one step sideways costs one", f.torch(9, 15, 8) == 13, "got ${f.torch(9, 15, 8)}")
        check("one step down costs one too", f.torch(8, 14, 8) == 13, "got ${f.torch(8, 14, 8)}")
        check("it reaches exactly as far as its level says", run {
            val reach = BlockType.GLOWSTONE.emission
            f.torch(8, 15 - reach + 1, 8) == 1 && f.torch(8, 15 - reach, 8) == 0
        }) {
            "at reach-1: ${f.torch(8, 15 - BlockType.GLOWSTONE.emission + 1, 8)}, " +
                    "at reach: ${f.torch(8, 15 - BlockType.GLOWSTONE.emission, 8)}"
        }
        check("every Cell of the room is exactly its level minus the distance", run {
            var wrong = 0
            for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                if (f.torch(x, y, z) != expected(x, y, z)) wrong++
            }
            wrong == 0
        }) {
            "corner (0,0,0) expected ${expected(0, 0, 0)} got ${f.torch(0, 0, 0)}; " +
                    "middle (8,8,8) expected ${expected(8, 8, 8)} got ${f.torch(8, 8, 8)}"
        }
        check("no daylight gets in, because the room is sealed", f.day(8, 8, 8) == 0)
        check("and the roof does not glow from inside it", f.torch(8, 16, 8) == 0)
    }

    println("\n== Light: leaves dim what passes through them ==")
    run {
        val bare = LitFixture()
        bare.sealedRoom()
        bare.world.set(8, 15, 8, BlockType.GLOWSTONE)
        bare.lightEverything()

        val leafy = LitFixture()
        leafy.sealedRoom()
        leafy.world.set(8, 15, 8, BlockType.GLOWSTONE)
        leafy.world.fill(8, 13, 8, 8, 14, 8, BlockType.LEAVES)
        leafy.lightEverything()

        check("a leaf costs a step and one more", leafy.torch(8, 12, 8) == bare.torch(8, 12, 8) - 2) {
            "air ${bare.torch(8, 12, 8)}, through two leaves ${leafy.torch(8, 12, 8)}"
        }
        check("leaves do not stop light outright", leafy.torch(8, 12, 8) > 0)
        check("what gets through is dimmer, not absent",
            leafy.torch(8, 12, 8) in 1 until bare.torch(8, 12, 8))
        check("a leaf is lit too, so a canopy glows from within",
            leafy.torch(8, 14, 8) == BlockType.GLOWSTONE.emission - 2) {
            "got ${leafy.torch(8, 14, 8)}, expected ${BlockType.GLOWSTONE.emission - 2}"
        }
    }

    println("\n== Light: it crosses a Chunk border ==")
    run {
        val f = LitFixture()
        f.sealedRoom()
        // A tunnel west through the wall at x=-1 and on into the Chunk at cx=-1. The Cells
        // at x=0 and x=-1 are in different Chunks, so light reaching the second has left
        // the Chunk the emitter is in.
        f.world.fill(-12, 15, 8, -1, 15, 8, BlockType.AIR)
        f.world.set(2, 15, 8, BlockType.GLOWSTONE)
        f.lightEverything()

        check("light does not stop at the seam", f.torch(-1, 15, 8) == 11) {
            "got ${f.torch(-1, 15, 8)} — x=-1 is in a different Chunk from x=0"
        }
        check("and keeps travelling through it", f.torch(-11, 15, 8) == 1, "got ${f.torch(-11, 15, 8)}")
        check("reaching the far cap of the tunnel and no further", f.torch(-12, 15, 8) == 0)
        check("the Cell on the near side of the seam is brighter than the far side",
            f.torch(0, 15, 8) > f.torch(-1, 15, 8))
    }

    println("\n== Light: it stops at the frontier instead of inventing the world ==")
    run {
        // Only cx=0 exists, so x=-1 and x=16 do not. The room is sealed by the edges of the
        // world rather than by walls, and light must reach the last existing Cell and stop.
        //
        // The Chunks are created by hand rather than by `fill`, because `fill` creates
        // whatever Chunk holds a Cell it writes — and an earlier version of this test
        // filled its walls out to x=-17, which quietly created exactly the Chunks it was
        // asserting did not exist.
        val world = FlatWorld()
        world.createAll(rx = 0, ry = 3, rz = 0)
        val engine = LightEngine(world)
        world.fill(0, 0, 0, 15, 15, 15, BlockType.AIR)        // the room
        world.fill(0, 16, 0, 15, 31, 15, BlockType.STONE)     // the roof
        world.set(2, 15, 8, BlockType.GLOWSTONE)
        world.all().forEach { engine.lightChunk(it) }

        fun t(x: Int) = Light.torch(world.lightAt(x, 15, 8))
        check("the frontier does not stop light early inside the world", t(15) == 1,
            "got ${t(15)} at the +X edge of the Chunk")
        check("nothing exists beyond it", world.existingChunkAt(-1, 15, 8) == null)
        check("so nothing is lit beyond it", t(-1) == Light.DARK)
        check("and the engine did not generate the missing Chunk as a side effect",
            world.existingChunkAt(16, 15, 8) == null) {
            "the +X neighbour was created by reading it"
        }
    }

    println("\n== Light: the player builds, and the room responds ==")
    run {
        val f = LitFixture()
        f.sealedRoom(shaft = true)
        f.lightEverything()

        fun wrong() = f.countWrong(::daylightUnderShaft)
        check("the room starts matching the shaft's falloff", wrong() == 0) { "${wrong()} Cells differ" }

        f.change(8, 20, 8, BlockType.STONE)      // roof the shaft over
        check("roofing the shaft puts the room back in darkness", f.day(8, 15, 8) == 0) {
            "got ${f.day(8, 15, 8)} — light left behind after the hole was closed"
        }
        check("all of it, not just the Cell underneath", wrong() == 0) { "${wrong()} Cells still lit" }
        check("and nothing above the new block changed", f.day(8, 21, 8) == Light.MAX)

        f.change(8, 20, 8, BlockType.AIR)        // open it again
        check("reopening it restores every Cell exactly", wrong() == 0) { "${wrong()} Cells differ" }
        check("and the shaft itself is full daylight again", f.day(8, 0, 8) == Light.MAX)
    }

    println("\n== Light: putting the light back gives back exactly the light you took ==")
    // The strongest single statement available about this engine. Any asymmetry between
    // "make it darker" and "make it lighter again" — a decay applied twice, a survivor
    // never re-seeded, the frontier handled differently the second time — shows up here and
    // almost nowhere else. Run against real terrain rather than a flat world, because the
    // interesting cases are near a surface where daylight, attenuation and the frontier all
    // meet at once.
    run {
        val streamer = litTerrain(cx = -2, cz = -2, side = 5, cy0 = 5, layers = 6)

        fun snapshot() = (-32..47).flatMap { x ->
            (0..95).flatMap { y -> (-32..47).map { z -> streamer.lightAt(x, y, z) } }
        }.toIntArray()

        // Each Cell is changed and then changed back, and the whole region must return.
        val targets = listOf(
            Triple(8, 41, 8), Triple(9, 41, 8), Triple(8, 40, 9),
            Triple(12, 60, 3), Triple(3, 63, 12), Triple(20, 85, -4),
            Triple(-5, 71, -5), Triple(30, 57, 30), Triple(-20, 63, 18),
            Triple(40, 100, -40), Triple(-45, 48, 44), Triple(0, 0, 0)
        )

        var mismatches = 0
        var firstMismatch = "none"
        for ((x, y, z) in targets) {
            val original = streamer.blockAt(x, y, z)
            val opposite = if (original == BlockType.AIR) BlockType.STONE else BlockType.AIR
            val before = snapshot()
            streamer.setBlockData(x, y, z, opposite)
            streamer.setBlockData(x, y, z, original)
            val after = snapshot()
            if (!before.contentEquals(after)) {
                mismatches++
                if (firstMismatch == "none") {
                    val i = before.indices.first { before[it] != after[it] }
                    val side = 80
                    firstMismatch = "at (${-32 + i % side}, ${i / (side * side)}, " +
                            "${-32 + (i / side) % side}) day ${Light.day(before[i])}->${Light.day(after[i])}" +
                            " torch ${Light.torch(before[i])}->${Light.torch(after[i])}"
                }
            }
        }
        println("  ${targets.size} edits, each applied and reverted over an 80x96x80 region")
        check("every build and unbuild returns the light to exactly where it was",
            mismatches == 0) { "$mismatches of ${targets.size} left a difference; first $firstMismatch" }
    }

    println("\n== Light: real terrain is lit like terrain should be ==")
    run {
        val streamer = litTerrain(cx = -2, cz = -2, side = 5, cy0 = 4, layers = 8)

        var sampled = 0
        var groundColumns = 0
        var skyWrong = 0
        val buriedLit = mutableListOf<Triple<Int, Int, Int>>()
        for (x in -30..46 step 3) for (z in -30..46 step 3) {
            val surface = streamer.surfaceAt(x, z) ?: continue
            sampled++
            for (dy in 1..3) {
                if (Light.day(streamer.lightAt(x, surface.worldY + dy, z)) != Light.MAX) skyWrong++
            }
            // Only Columns the ground actually caps. A tree's Surface is a leaf, and
            // leaves are not a roof: daylight really does fall past a canopy into the gap
            // under it, so asserting otherwise here would be asserting that trees cast
            // shadows, which they do not in this model.
            if (surface.type != BlockType.GRASS && surface.type != BlockType.DIRT) continue
            groundColumns++
            if (surface.worldY - 6 >= 0 &&
                Light.day(streamer.lightAt(x, surface.worldY - 6, z)) == Light.MAX) {
                buriedLit.add(Triple(x, surface.worldY - 6, z))
            }
        }
        check("every Column sampled has a Surface", sampled > 200, "only $sampled")
        check("open air above the ground is full daylight", skyWrong == 0,
            "$skyWrong Cells just above the Surface were not")
        check("most sampled Columns are capped by actual ground", groundColumns > sampled / 2,
            "$groundColumns of $sampled")
        check("six Cells under solid ground is not full daylight", buriedLit.isEmpty()) {
            "lit at $buriedLit"
        }
        check("and glowstone does light the dark", run {
            var lit = 0
            for (x in -30..46 step 5) for (y in 0..127) for (z in -30..46 step 5) {
                if (Light.torch(streamer.lightAt(x, y, z)) > 0) lit++
            }
            lit > 0
        }, "no Cell in the sampled region had any Torchlight at all")
    }

    println("\n== Sounds: every Material maps, and AIR does not ==")
    check("every solid Block Type has a Material",
        BlockType.entries.filter { it != BlockType.AIR }.all { it.material != null })
    check("AIR has no Material — it has no substance", BlockType.AIR.material == null)
    check("grass and dirt are both earth", BlockType.GRASS.material == BlockType.DIRT.material)
    check("stone and bedrock are both stone", BlockType.STONE.material == BlockType.BEDROCK.material)
    check("leaves are foliage, not wood",
        BlockType.LEAVES.material == Material.FOLIAGE && BlockType.LEAVES.material != BlockType.WOOD.material)
    check("every Material is used by at least one Block Type",
        Material.entries.all { m -> BlockType.entries.any { it.material == m } })

    println("\n== Sounds: the buffers are real audio, not valid silence ==")
    val sounds = Material.entries.flatMap { m -> Occasion.entries.map { m to it } }
    val rendered = sounds.associateWith { Synth.render(it.first, it.second) }

    fun rms(s: FloatArray) =
        kotlin.math.sqrt(s.sumOf { (it * it).toDouble() } / s.size)

    fun peak(s: FloatArray) = s.maxOf { kotlin.math.abs(it) }

    // These three are the failure modes this shape of code invites. A filter that cancels
    // to zero, an envelope that never opens, and a buffer that clips are all perfectly
    // valid PCM and all inaudible or painful.
    check("no Sound is silent", rendered.values.all { rms(it) > 0.001 },
        "quietest RMS ${rendered.values.minOf { rms(it) }}")
    check("no Sound is clipped", rendered.values.all { peak(it) <= 1.0f },
        "loudest peak ${rendered.values.maxOf { peak(it) }}")
    check("no Sound is long enough to be mistaken for a loop",
        rendered.values.all { it.size.toFloat() / Synth.SAMPLE_RATE < 0.5f },
        "longest ${rendered.values.maxOf { it.size }.toFloat() / Synth.SAMPLE_RATE}s")
    check("every Sound ends at silence, so it cannot pop",
        rendered.values.all { s -> kotlin.math.abs(s.last()) < 1e-3f })

    println("\n== Sounds: different Materials must actually sound different ==")
    // The claim "different Materials sound different" is worthless unless measured. This is
    // the check that would catch a filter change quietly collapsing stone onto dirt.
    fun spectralCentroid(s: FloatArray): Float {
        // Crude zero-crossing rate as a brightness proxy. Enough to separate a 320Hz earth
        // thud from a 3400Hz rustle; not a real spectrum, and not trying to be.
        var crossings = 0
        for (i in 1 until s.size) if (s[i - 1] < 0f && s[i] >= 0f) crossings++
        return crossings.toFloat() * Synth.SAMPLE_RATE / (2f * s.size)
    }

    val centroids = Material.entries.associateWith { m ->
        spectralCentroid(Synth.render(m, Occasion.FOOTSTEP))
    }
    check("stone is brighter than earth",
        centroids[Material.STONE]!! > centroids[Material.EARTH]!!,
        "stone=${centroids[Material.STONE]} earth=${centroids[Material.EARTH]}")
    check("foliage is brighter than wood",
        centroids[Material.FOLIAGE]!! > centroids[Material.WOOD]!!,
        "foliage=${centroids[Material.FOLIAGE]} wood=${centroids[Material.WOOD]}")

    val allCentroids = Material.entries.map {
        it to spectralCentroid(Synth.render(it, Occasion.FOOTSTEP))
    }
    check("no two Materials are accidentally identical", run {
        (0 until allCentroids.size).all { i ->
            (i + 1 until allCentroids.size).all { j ->
                kotlin.math.abs(allCentroids[i].second - allCentroids[j].second) > 20f
            }
        }
    }, "centroids $allCentroids")

    println("\n== Sounds: synthesis is deterministic ==")
    // If this fails, every other assertion in this section is asserting against a moving
    // target and none of them mean anything.
    check("rendering the same Sound twice is byte-identical", run {
        val a = Synth.render(Material.STONE, Occasion.BREAK)
        val b = Synth.render(Material.STONE, Occasion.BREAK)
        a.contentEquals(b)
    })
    check("every Sound is distinct from every other", run {
        val keys = sounds.toList()
        (0 until keys.size).all { i ->
            (i + 1 until keys.size).all { j ->
                !rendered[keys[i]]!!.contentEquals(rendered[keys[j]]!!)
            }
        }
    })

    println("\n== Sounds: the landing seam ==")
    // Player reports its landing rather than knowing that sound exists. These assert the
    // report itself, which is the half that can be checked without a sound card.
    val faller = Player(object : SolidWorld { override fun isSolid(x: Float, y: Float, z: Float) = y < 1f })
    faller.position.set(0.5f, 12f, 0.5f)
    // Run until grounded, *without* consuming the report — reading it inside the loop
    // condition would throw away the very thing being asserted.
    var steps = 0
    while (steps < 400 && !faller.isGrounded) { faller.step(); steps++ }

    val landing = faller.consumeLanding()
    check("a long fall reports a landing", landing != null && landing > 6f, "got $landing")
    check("the landing is reported exactly once", faller.consumeLanding() == null)
    check("standing still does not report another landing", run {
        repeat(30) { faller.step() }
        faller.consumeLanding() == null
    })
    // Under MIN_FALL_TO_LAND the report is suppressed at the source rather than being
    // returned and filtered by the caller, so nothing is ever emitted for a hop.
    val hopper = Player(object : SolidWorld { override fun isSolid(x: Float, y: Float, z: Float) = y < 1f })
    hopper.position.set(0.5f, 2.5f, 0.5f)
    hopper.velocity.set(0f, 0.25f, 0f)
    var hopLandings = 0
    repeat(120) { hopper.step(); if (hopper.consumeLanding() != null) hopLandings++ }
    check("a short hop does not report a landing at all", hopLandings == 0,
        "reported $hopLandings landings for a sub-threshold hop")

    // The real reason the threshold exists: a fast arrival stops short of the surface and
    // keeps falling in small increments, and each of those is a genuine airborne transition.
    // Without the threshold this is a machine gun of landing noises.
    val wobbler = Player(object : SolidWorld { override fun isSolid(x: Float, y: Float, z: Float) = y < 1f })
    wobbler.position.set(0.5f, 30f, 0.5f)
    var i = 0
    while (i < 600 && !wobbler.isGrounded) { wobbler.step(); i++ }
    wobbler.consumeLanding()
    var spurious = 0
    repeat(200) { wobbler.step(); if (wobbler.consumeLanding() != null) spurious++ }
    check("the settling wobble after a hard landing does not re-report", spurious == 0,
        "reported $spurious spurious landings while standing still")

    println("\n== Slot click merge arithmetic ==")
    val inv = Inventory()
    check("a pickup lands in the hotbar first", inv.addItem(BlockType.STONE) && inv.hotbar[0]?.type == BlockType.STONE)
    check("the same type stacks", run { repeat(10) { inv.addItem(BlockType.STONE) }; inv.hotbar[0]?.count == 11 })

    println()
    if (failures == 0) println("All checks passed.") else println("$failures FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
