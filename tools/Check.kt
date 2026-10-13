package check

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

fun main() {
    println("\n== Meshing: the numbers the session log recorded before the split ==")
    var total = 0
    var allAir = 0
    var chunks = 0
    for (cx in 0 until 4) for (cz in 0 until 4) for (cy in 0 until 16) {
        val chunk = Chunk(cx, cy, cz)
        val vertices = MeshGenerator.generateChunkMesh(chunk).size / Geometry.POS_UV_NORMAL
        total += vertices
        chunks++
        if (vertices == 0) allAir++
    }
    println("  4x4x16 sample: $total vertices, $allAir/$chunks chunks all-air")
    check("vertex count matches the pre-refactor figure", total == 1_213_368, "was $total, expected 1213368")
    check("all-air count matches the pre-refactor figure", allAir == 117, "was $allAir, expected 117")

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

    println("\n== Slot click merge arithmetic ==")
    val inv = Inventory()
    check("a pickup lands in the hotbar first", inv.addItem(BlockType.STONE) && inv.hotbar[0]?.type == BlockType.STONE)
    check("the same type stacks", run { repeat(10) { inv.addItem(BlockType.STONE) }; inv.hotbar[0]?.count == 11 })

    println()
    if (failures == 0) println("All checks passed.") else println("$failures FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
