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
