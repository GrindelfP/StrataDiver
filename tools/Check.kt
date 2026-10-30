package check

import org.joml.Vector2f
import org.joml.Vector3f
import to.grindelf.stratadiver.*
import kotlin.math.pow

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
    check("EMA 0.92 current weight leaves 0.08 history per frame",
        kotlin.math.abs(TemporalResponse.historyWeight(0.92, 1) - 0.08) < 1e-12)
    check("EMA response half-life matches current-weight convention",
        kotlin.math.abs(TemporalResponse.halfLifeFrames(0.92) - 0.2744) < 0.001)
    check("EMA half-life reproduces the declared number of frames",
        kotlin.math.abs(TemporalResponse.historyWeight(0.2, 3) - 0.512) < 1e-12)
    println("\n== Path tracing estimator and color transfer ==")
    run {
        fun pixel(value: Double, normal: Triple<Double, Double, Double> = Triple(0.0, 0.0, 1.0),
                   depth: Double = 4.0, albedo: Double = 0.5) = BilateralDenoiser.Pixel(
            value, value, value, normal.first, normal.second, normal.third, depth, albedo, albedo, albedo)
        val flat = List(25) { i -> pixel(if (i % 2 == 0) 0.8 else 1.2) }
        val smooth = BilateralDenoiser.filter(flat, 5, 5)
        val rawVariance = flat.map { (it.r - 1.0) * (it.r - 1.0) }.average()
        val filteredVariance = smooth.map { (it.r - 1.0) * (it.r - 1.0) }.average()
        check("bilateral reduces deterministic flat-region noise", filteredVariance < rawVariance * 0.5,
            "raw=$rawVariance filtered=$filteredVariance")

        val silhouette = List(25) { i -> if (i % 5 < 2) pixel(0.1) else pixel(0.9, depth = 8.0) }
        val silhouetteOut = BilateralDenoiser.filter(silhouette, 5, 5)
        check("parallel silhouette depth leakage is bounded", silhouetteOut[1].r < 0.101,
            "edge=${silhouetteOut[1].r}")
        val faces = List(25) { i -> if (i % 5 < 2) pixel(0.2) else pixel(0.8, normal = Triple(1.0, 0.0, 0.0)) }
        check("voxel-face normal leakage is bounded", BilateralDenoiser.filter(faces, 5, 5)[1].r < 0.201)
        val albedoEdge = List(25) { i -> if (i % 5 < 2) pixel(0.15, albedo = 0.1) else pixel(0.85, albedo = 0.9) }
        check("authored albedo detail leakage is bounded", BilateralDenoiser.filter(albedoEdge, 5, 5)[1].r < 0.151)
        val sky = List(25) { i -> if (i % 5 < 2) pixel(0.3, depth = 0.0) else pixel(0.9, depth = 4.0) }
        val skyFiltered = BilateralDenoiser.filter(sky, 5, 5)[1].r
        check("surface radiance does not leak into sky", kotlin.math.abs(skyFiltered - 0.3) < 1e-12,
            "sky=$skyFiltered")
        val flatGuides = pixel(0.4)
        check("same-plane neighboring voxel samples remain eligible",
            BilateralDenoiser.weight(flatGuides, flatGuides.copy(r = 0.5), 1, 0) > 0.0)
    }
    run {
        // The production roulette uses p=max(linear albedo), clamps p to [0.05, 0.95],
        // then divides surviving throughput by p. A fixed-seed Bernoulli experiment
        // checks the expected continuation factor against the no-roulette value.
        val albedo = 0.2
        val survival = albedo.coerceIn(0.05, 0.95)
        val rng = java.util.Random(4004L)
        var estimate = 0.0
        val samples = 500_000
        repeat(samples) {
            if (rng.nextDouble() < survival) estimate += albedo / survival
        }
        estimate /= samples
        check("roulette continuation converges to un-terminated throughput",
            kotlin.math.abs(estimate - albedo) < 0.002, "estimate=$estimate expected=$albedo")

        fun decodeSrgb(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        fun encodeSrgb(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
        val midpoint = decodeSrgb(0.5)
        check("sRGB midpoint decodes to linear light", kotlin.math.abs(midpoint - 0.21404114) < 1e-7,
            "linear=$midpoint")
        check("sRGB encode/decode round-trips authored color",
            kotlin.math.abs(encodeSrgb(midpoint) - 0.5) < 1e-12)
    }

    println("\n== Frame-time rolling window ==")
    run {
        val average = RollingAverage(3)
        check("empty rolling mean is zero", average.count == 0 && average.average() == 0.0)
        listOf(10.0, 20.0, 30.0).forEach(average::add)
        check("mean covers all three retained samples", average.count == 3 && average.average() == 20.0)
        average.add(40.0)
        check("full window evicts exactly its oldest sample", average.count == 3 && average.average() == 30.0)
        average.add(50.0)
        check("wraparound still averages retained samples", average.average() == 40.0)
    }
    run {
        val policy = ResolutionPolicy(16.7, maximumDivisor = 3, decisionFrames = 3)
        var scale = 1
        repeat(2) { scale = policy.observe(scale, 30.0) }
        check("resolution policy waits for a complete decision window", scale == 1)
        scale = policy.observe(scale, 30.0)
        check("over-budget CPU work degrades one bounded divisor step", scale == 2)
        repeat(3) { scale = policy.observe(scale, 15.0) }
        check("hysteresis prevents recovery near budget", scale == 2)
        repeat(3) { scale = policy.observe(scale, 10.0) }
        check("sustained headroom recovers one divisor step", scale == 1)
        repeat(6) { scale = policy.observe(scale, 100.0) }
        check("resolution policy remains within divisor bounds", scale == 2)
    }
    println("\n== GPU volume window coordinates ==")
    val volumeWindow = VolumeWindow(across = 9, high = 7)
    val (initial, firstMove) = volumeWindow.update(-0.1f, 64f, 16f)
    val (stationary, stationaryMove) = volumeWindow.update(-0.1f, 64f, 16f)
    val (crossed, crossedMove) = volumeWindow.update(-16.1f, 64f, 16f)
    check("initial anchor converts chunk coordinates to Cell origin", firstMove && initial.cellX == -80 && initial.cellY == 16 && initial.cellZ == -48)
    check("stationary update preserves anchor without invalidation", !stationaryMove && stationary == initial)
    check("negative boundary crossing moves exactly one Chunk", crossedMove && crossed.cellX == initial.cellX - Chunk.SIZE)
    run {
        val benchmark = FrameBenchmark(4)
        listOf(8.0, 4.0, 16.0, 12.0).forEach(benchmark::record)
        check("benchmark median is nearest-rank p50", benchmark.percentile(50.0) == 8.0)
        check("benchmark p95 is nearest-rank", benchmark.percentile(95.0) == 16.0)
        benchmark.record(100.0)
        check("benchmark capacity is fixed", benchmark.count() == 4)
    }
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
     * Ray tracing gives exact line-of-sight: a Cell is lit by daylight if and only if a
     * ray cast straight up from it reaches the sky without striking an opaque Block Type.
     * The shaft Column (x=8, z=8) is open to the sky, so every Cell in it is at full
     * daylight. Every other Cell in the room has stone above it, so the ray stops there
     * and the Cell is in shadow.
     */
    fun daylightUnderShaft(x: Int, y: Int, z: Int): Int =
        if (x == 8 && z == 8) Light.MAX else Light.DARK

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
        check("the rest of the room is in shadow", f.countWrong(::daylightUnderShaft) == 0) {
            "${f.countWrong(::daylightUnderShaft)} Cells differ"
        }
        check("the roof above the room is dark", f.day(8, 16, 0) == Light.DARK)
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
        fun expected(x: Int, y: Int, z: Int): Int {
            val dx = (x - 8).toDouble()
            val dy = (y - 15).toDouble()
            val dz = (z - 8).toDouble()
            val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toInt()
            return (BlockType.GLOWSTONE.emission - dist).coerceAtLeast(0)
        }

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
        check("the roof stone is in shadow", f.day(8, 16, 8) == Light.DARK)
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
        check("a leaf one step from the emitter is lit by it",
            leafy.torch(8, 14, 8) == BlockType.GLOWSTONE.emission - 1) {
            "got ${leafy.torch(8, 14, 8)}, expected ${BlockType.GLOWSTONE.emission - 1}"
        }
        check("a leaf two steps away is dimmed by the leaf between",
            leafy.torch(8, 13, 8) == BlockType.GLOWSTONE.emission - 3) {
            "got ${leafy.torch(8, 13, 8)}, expected ${BlockType.GLOWSTONE.emission - 3}"
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

        fun litWrong() = f.countWrong(::daylightUnderShaft)
        fun darkWrong() = f.countWrong { _, _, _ -> Light.DARK }
        check("the room starts matching the shaft's falloff", litWrong() == 0) { "${litWrong()} Cells differ" }

        f.change(8, 20, 8, BlockType.STONE)      // roof the shaft over
        check("roofing the shaft puts the room back in darkness", f.day(8, 15, 8) == 0) {
            "got ${f.day(8, 15, 8)} — light left behind after the hole was closed"
        }
        // Compared against *dark*, not against the falloff. The whole 16x16x16 room has to
        // go out: the light in it came through the one hole, and the hole is closed.
        check("all of it, not just the Cell underneath", darkWrong() == 0) { "${darkWrong()} Cells still lit" }
        check("and nothing above the new block changed", f.day(8, 21, 8) == Light.MAX)
        check("nor anything below it", f.day(8, 19, 8) == Light.DARK)

        f.change(8, 20, 8, BlockType.AIR)        // open it again
        check("reopening it restores every Cell exactly", litWrong() == 0) { "${litWrong()} Cells differ" }
        check("and the shaft itself is full daylight again", f.day(8, 0, 8) == Light.MAX)
    }

    println("\n== Light: capping a deep shaft darkens it to the floor ==")
    // Daylight is vertical, so a change at the mouth of a Column reaches every Cell below
    // it — however far down that is. A change that re-lit only the 3x3x3 of Chunks around
    // itself would leave the floor of a deep pit glowing after its mouth was sealed, and
    // the floor is more than one Chunk below the seal. This is that case, as numbers.
    run {
        val f = LitFixture(rx = 0, ry = 4, rz = 0)      // one Chunk Column of four, y 0..63
        f.world.fill(0, 0, 0, 15, 63, 15, BlockType.STONE)
        f.world.fill(8, 0, 8, 8, 63, 8, BlockType.AIR)  // a 1x1 shaft bored to the sky
        f.lightEverything()

        check("an open shaft is lit all the way down", (0..63).all { f.day(8, it, 8) == Light.MAX }) {
            "cells at ${(0..63).filter { f.day(8, it, 8) != Light.MAX }} were not full"
        }

        // Seal the mouth at the very top, three Chunks above the floor.
        f.change(8, 63, 8, BlockType.STONE)
        check("sealing its mouth darkens the floor, three Chunks below",
            f.day(8, 0, 8) == Light.DARK) {
            "got ${f.day(8, 0, 8)} — the floor was not re-derived"
        }
        check("and every Cell beneath the seal with it",
            (0..62).all { f.day(8, it, 8) == Light.DARK }) {
            "cells at ${(0..62).filter { f.day(8, it, 8) != Light.DARK }} stayed lit"
        }

        f.change(8, 63, 8, BlockType.AIR)
        check("opening it again restores the whole Column",
            (0..63).all { f.day(8, it, 8) == Light.MAX }) {
            "cells at ${(0..63).filter { f.day(8, it, 8) != Light.MAX }} stayed dark"
        }
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

    println("\n== Light: an edit must equal recomputing the neighbourhood from scratch ==")
    // The invariant that replaced the incremental darkening walk. Whatever a block change
    // does, the result has to be indistinguishable from throwing the affected Chunks away
    // and deriving them from the sky again — because that is what the change now does, and
    // this is the check that would notice if anyone put clever incremental logic back.
    //
    // It is also the check that would have caught the walk, several checks earlier and with
    // a much clearer message than "261 Cells still lit".
    run {
        val streamer = litTerrain(cx = -2, cz = -2, side = 4, cy0 = 5, layers = 7)
        val side = 4
        val baseY = 5
        val layers = 7

        fun allChunks() = (0 until side).flatMap { cx ->
            (0 until side).flatMap { cz ->
                (0 until layers).map { cy ->
                    streamer.existingChunkAt((cx - 2) * 16, (baseY + cy) * 16, (cz - 2) * 16)
                }
            }
        }.filterNotNull()

        fun snapshot() = (-24..39).flatMap { x ->
            (0..111).flatMap { y -> (-24..39).map { z -> streamer.lightAt(x, y, z) } }
        }.toIntArray()

        val edits = listOf(
            Triple(8, 41, 8), Triple(9, 41, 8), Triple(8, 40, 9), Triple(12, 60, 3),
            Triple(3, 63, 12), Triple(-5, 71, -5), Triple(30, 57, 30), Triple(-20, 63, 18),
            Triple(0, 96, 0), Triple(40, 100, -40), Triple(-16, 48, 16), Triple(16, 80, -16)
        )

        var diverged = 0
        var firstDivergence = "none"
        for ((x, y, z) in edits) {
            val opposite = if (streamer.blockAt(x, y, z) == BlockType.AIR) BlockType.STONE else BlockType.AIR
            streamer.setBlockData(x, y, z, opposite)
            val incremental = snapshot()
            allChunks().forEach { streamer.light.lightChunk(it) }
            val fromScratch = snapshot()
            if (!incremental.contentEquals(fromScratch)) {
                diverged++
                if (firstDivergence == "none") {
                    val i = incremental.indices.first { incremental[it] != fromScratch[it] }
                    val n = 64
                    firstDivergence = "at (${-24 + i % n}, ${i / (n * n)}, " +
                            "${-24 + (i / n) % n}) got day ${Light.day(incremental[i])} " +
                            "expected ${Light.day(fromScratch[i])}"
                }
            }
        }
        check("an edit lands on exactly what a full re-derivation would produce",
            diverged == 0) { "$diverged of ${edits.size} diverged; first $firstDivergence" }

        // And the cost, measured rather than asserted, so the number in the session log is
        // not a guess.
        val t0 = System.nanoTime()
        repeat(20) { streamer.setBlockData(8 + it, 41, 8, BlockType.STONE) }
        val perEdit = (System.nanoTime() - t0) / 20 / 1_000_000.0
        println("  a block change re-lights its neighbourhood in %.1f ms".format(perEdit))
        check("and it is affordable for a discrete player action", perEdit < 33.0,
            "%.1f ms is past two frames".format(perEdit))
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
            // Only Columns the ground actually caps. A tree's Surface is a leaf, and a
            // leaf is not ground: the Cells below it are inside the tree, not buried
            // under it, so they are not the case this check exists for.
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

    pathTracingChecks()

    println()
    if (failures == 0) println("All checks passed.") else println("$failures FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}

/**
 * Everything about the path tracer that can be asserted without a window.
 *
 * The renderer is pure Kotlin, which means the whole of it — the Cell walk, the light
 * transport, the accumulation — can be driven against a world built here rather than one
 * that happens to be on screen. That is worth more than it sounds: a path tracer's failures
 * are almost never crashes, they are *plausible wrong pictures*, and the only defence
 * against a plausible wrong picture is a number that must come out a particular way.
 */
private fun pathTracingChecks() {
    val atlas = Art.buildAtlasPixels()

    /**
     * A Chunk of nothing, so a test can paint the one thing it cares about into it.
     *
     * [Chunk] is a concrete class with a generator in its constructor, so a world built for
     * a check has to start from real terrain and be overwritten. Doing it by hand rather
     * than adding a constructor that skips generation would put a test-only path into the
     * game's own type, and this is the cheaper of the two.
     */
    fun blankChunk(cx: Int, cy: Int, cz: Int): Chunk {
        val chunk = Chunk(cx, cy, cz)
        for (x in 0 until 16) for (y in 0 until 16) for (z in 0 until 16) {
            chunk.setBlock(x, y, z, BlockType.AIR)
        }
        return chunk
    }

    fun worldOf(vararg chunks: Chunk) = object : GridWorld {
        private val byPosition = chunks.associateBy { it.position }
        override fun existingChunk(cx: Int, cy: Int, cz: Int) = byPosition[Triple(cx, cy, cz)]
    }

    fun camera(
        x: Float, y: Float, z: Float,
        fx: Float, fy: Float, fz: Float,
        fov: Float = 70f, aspect: Float = 1f
    ) = Camera(
        Vector3f(x, y, z), Vector3f(fx, fy, fz), Vector3f(0f, 1f, 0f), fov, aspect
    )

    fun scene(cam: Camera, daylight: Float = 1f, sun: Vector3f = Vector3f(0.4f, 0.9f, 0.2f)) =
        TraceScene(cam, sun, daylight)

    /** Traces [size] x [size] pixels [samples] times and returns the mean radiance per pixel. */
    /**
     * The colour of one narrow cone of rays, averaged over [samples] of them.
     *
     * The narrow field of view is the whole point and it is the thing an earlier version of
     * this got wrong. A "pixel" is not a point: it is a cone the width of the view divided
     * by the image, so a 1x1 image through the game's 70-degree lens is the entire screen.
     * Checks written that way compare two averages over almost the same whole scene, and
     * they do not fail — they simply cannot tell the thing they were built to tell.
     */
    /** Traces [size] x [size] pixels [samples] times and returns the mean radiance per pixel. */
    fun tracePixels(
        world: GridWorld, cam: Camera, size: Int = 4, samples: Int = 1,
        daylight: Float = 1f, sun: Vector3f = Vector3f(0.4f, 0.9f, 0.2f)
    ): Array<FloatArray> {
        val tracer = PathTracer(world, atlas, threads = 1)
        tracer.resize(size, size)
        repeat(samples) { tracer.sample(scene(cam, daylight, sun)) }
        return Array(size * size) { index ->
            // index = row * size + column, and radianceAt takes (x, y) = (column, row). The
            // two orders are the same only for a square, which is exactly why it went
            // unnoticed until a check compared the left of an image with the right of it.
            FloatArray(3).also { tracer.radianceAt(index % size, index / size, it) }
        }
    }

    /**
     * The colour of one narrow cone of rays, averaged over [samples] of them.
     *
     * The narrow field of view is the whole point, and it is the thing an earlier version of
     * this got wrong. A "pixel" is not a point: it is a cone the width of the view divided by
     * the image, so a 1x1 image through the game's 70-degree lens is the entire screen. Checks
     * written that way compare two averages over almost the same whole scene, and they do not
     * fail — they simply cannot tell the thing they were built to tell.
     */
    fun conePixels(
        world: GridWorld, cam: Camera, samples: Int = 1,
        daylight: Float = 1f, sun: Vector3f = Vector3f(0.4f, 0.9f, 0.2f)
    ): FloatArray = tracePixels(world, cam, 1, samples, daylight, sun)[0]

    fun luminance(p: FloatArray) = 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]

    // --- The Cell walk is exact ---

    println("\n== Path tracing: the Cell walk ==")

    /**
     * The closed-form distance at which a ray first enters a box, with no stepping in it.
     *
     * A Cell *is* an axis-aligned box, so this answer exists and is exact. Which makes the
     * Cell walk the one part of a renderer with something to be compared against rather
     * than merely looked at.
     */
    fun slabEntry(
        ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
        min: FloatArray, max: FloatArray
    ): Float {
        var tEnter = 0f
        var tExit = Float.MAX_VALUE
        for (axis in 0 until 3) {
            val o = floatArrayOf(ox, oy, oz)[axis]
            val d = floatArrayOf(dx, dy, dz)[axis]
            if (kotlin.math.abs(d) < 1e-9f) continue
            val t1 = (min[axis] - o) / d
            val t2 = (max[axis] - o) / d
            tEnter = maxOf(tEnter, minOf(t1, t2))
            tExit = minOf(tExit, maxOf(t1, t2))
        }
        return if (tEnter > tExit) -1f else tEnter
    }

    val solidChunk = blankChunk(0, 0, 0)
    // High in the Chunk, deliberately. A ring of rays eight Cells across centred on a low
    // Cell starts *below* the floor of the world for some directions, and there the tracer
    // answers BEDROCK — correctly, since that is what stops a ray seeing through the planet.
    // A check that did not notice that difference would be reporting a failure that is really
    // a statement about where the test put its camera.
    solidChunk.setBlock(4, 12, 6, BlockType.STONE)
    val oneCell = worldOf(solidChunk)
    val prober = PathTracer(oneCell, atlas, threads = 1)
    val boxMin = floatArrayOf(4f, 12f, 6f)
    val boxMax = floatArrayOf(5f, 13f, 7f)

    val centreX = 4.5f; val centreY = 12.5f; val centreZ = 6.5f
    var compared = 0
    var worstDistance = 0f
    var wrongCell = 0
    for (i in 0 until 36) for (j in 0 until 36) {
        val theta = (i * 2.0 * Math.PI / 36).toFloat()
        val phi = (j + 0.5).toFloat() / 36.0f * 2f - 1f
        val r = kotlin.math.sqrt(1f - phi * phi)
        val dir = floatArrayOf(r * kotlin.math.cos(theta), phi, r * kotlin.math.sin(theta))

        // Start *behind* the Cell, so the ray travels towards it. Starting along the
        // direction instead puts the origin on the far side and the ray walks away from
        // everything — which is the kind of mistake that makes a check report "0 directions"
        // and read like a pass.
        val ox = centreX - dir[0] * 8f
        val oy = centreY - dir[1] * 8f
        val oz = centreZ - dir[2] * 8f

        val expected = slabEntry(ox, oy, oz, dir[0], dir[1], dir[2], boxMin, boxMax)
        if (expected <= 0f) continue // aimed away; nothing to compare

        val found = prober.probe(ox, oy, oz, dir[0], dir[1], dir[2])
        if (!found.hit) { wrongCell++; continue }
        if (found.x != 4 || found.y != 12 || found.z != 6) { wrongCell++; continue }

        compared++
        worstDistance = maxOf(worstDistance, kotlin.math.abs(found.t - expected))
    }
    println("  $compared directions, worst disagreement ${"%.6f".format(worstDistance)} Cells")
    check("the Cell walk agrees with closed form on every direction tried",
        compared > 1200 && wrongCell == 0,
        "$compared compared, $wrongCell missed or hit the wrong Cell")
    check("and agrees to well within a thousandth of a Cell",
        worstDistance < 1e-3f, "worst disagreement was $worstDistance")

    check("a ray that misses the Cell finds nothing", run {
        // Straight up, in a column with no stone in it. Straight up from *under* the Cell
        // would go straight through it, which is the opposite of what is being claimed.
        val away = prober.probe(2.5f, 1.5f, 2.5f, 0f, 1f, 0f)
        !away.hit
    })
    check("a ray leaving represented chunks through AIR returns a miss, not a volume wall", run {
        // The fake world contains only chunk (0,0,0). Starting beyond its east edge must
        // not invent a hit at the edge; the renderer's no-hit path is the sky path.
        val outside = prober.probe(17.5f, 12.5f, 6.5f, 1f, 0f, 0f)
        !outside.hit
    })
    check("a ray starting inside a Cell is stopped there", run {
        // The starting Cell is the answer, at distance zero. Anything else is a walk that
        // has stepped past the geometry it is standing in.
        val inside = prober.probe(4.5f, 12.5f, 6.5f, 1f, 0f, 0f)
        inside.hit && inside.t == 0f
    })
    check("the face a ray enters through points back at it", run {
        // Entering the Cell from -X means the face is its WEST one, and the reported face
        // is what decides the Tile and which way the surface faces. A face chosen wrongly
        // lights a wall from the wrong side and gives it the wrong art, silently.
        val fromWest = prober.probe(-2.5f, 12.5f, 6.5f, 1f, 0f, 0f)
        fromWest.face == 4
    })

    // --- A Cutout is a hole, not a surface ---

    println("\n== Path tracing: leaves are see-through ==")

    /**
     * Which art pixels of the Leaves Tile are holes, read from the art itself.
     *
     * Not assumed. The art is the authority on where its own holes are, and a check that
     * hard-coded "row 3 is a hole" would keep passing after someone redrew the tile.
     */
    /**
     * The alpha of one point on the Leaves Tile, in the same 0..1 coordinates the tracer
     * itself uses. Taking normalised coordinates rather than art-pixel indices is the point:
     * a version of this took indices, multiplied them by 16, clamped everything to the last
     * pixel, and reported that the Leaves art had no holes in it at all.
     */
    fun leafAlpha(u: Float, v: Float): Int {
        val ax = (u * Atlas.ART_PX).toInt().coerceIn(0, Atlas.ART_PX - 1)
        val ay = (v * Atlas.ART_PX).toInt().coerceIn(0, Atlas.ART_PX - 1)
        val ox = Atlas.LEAVES.tx * Atlas.TILE_PX + Atlas.PAD + ax * Atlas.UPSCALE
        val oy = Atlas.LEAVES.ty * Atlas.TILE_PX + Atlas.PAD + ay * Atlas.UPSCALE
        val size = Atlas.TILES * Atlas.TILE_PX
        return (atlas[oy * size + ox] ushr 24) and 0xFF
    }

    /**
     * One tracer reused for many single-pixel questions.
     *
     * Not an optimisation: [PathTracer] owns a thread pool, and building one per probe would
     * leave a hundred idle threads behind by the end of this function. The reset between
     * questions is what makes reusing it correct — each is asked of a different ray, and
     * averaging two of them would answer neither.
     */
    class SinglePixel(val world: GridWorld) {
        private val tracer = PathTracer(world, atlas, threads = 1)

        init {
            tracer.resize(1, 1)
        }

        fun pixel(cam: Camera, samples: Int = 4): FloatArray {
            tracer.reset()
            repeat(samples) { tracer.sample(scene(cam)) }
            return FloatArray(3).also { tracer.radianceAt(0, 0, it) }
        }
    }

    val leavesChunk = blankChunk(0, 0, 0)
    // A leaf at the near Cell, STONE behind it, and the camera square in front of both.
    leavesChunk.setBlock(4, 5, 4, BlockType.LEAVES)
    leavesChunk.setBlock(4, 5, 5, BlockType.STONE)
    val leavesWorld = worldOf(leavesChunk)

    val hole = (0 until Atlas.ART_PX).flatMap { ay ->
        (0 until Atlas.ART_PX).mapNotNull { ax ->
            if (leafAlpha((ax + 0.5f) / Atlas.ART_PX, (ay + 0.5f) / Atlas.ART_PX) < 128) ax to ay else null
        }
    }
    val solid = (0 until Atlas.ART_PX).flatMap { ay ->
        (0 until Atlas.ART_PX).mapNotNull { ax ->
            if (leafAlpha((ax + 0.5f) / Atlas.ART_PX, (ay + 0.5f) / Atlas.ART_PX) >= 128) ax to ay else null
        }
    }
    check("the Leaves Tile really does have holes in it", hole.isNotEmpty(),
        "found ${hole.size} transparent art pixels of 256")
    check("and really does have solid parts in it", solid.isNotEmpty(),
        "found ${solid.size} opaque art pixels")

    /**
     * Fires one ray at the middle of one art pixel of the leaf's front face, and reports
     * what came back. Returns true if the STONE behind the leaf was the thing found.
     *
     * The lens is 0.2 degrees, which at two and a half Cells is about a fortieth of a Cell —
     * well inside the quarter-Cell an art pixel occupies. A wider one and each sample lands
     * on whatever art pixel its jitter happened to reach, so the check ends up measuring the
     * neighbours of the pixel it named.
     */
    val leafProbe = SinglePixel(leavesWorld)
    fun passesThroughLeaf(artX: Int, artY: Int): Boolean {
        // The leaf's NORTH face (normal -Z) is being looked at from -Z, and on that face
        // faceUV says u = 1 - a, v = b. Solved backwards for the point on the face that
        // lands on (artX, artY): a = 1 - u, b = v.
        val u = (artX + 0.5f) / Atlas.ART_PX
        val v = (artY + 0.5f) / Atlas.ART_PX
        val targetX = 4f + (1f - u)
        val targetY = 5f + v
        val targetZ = 4f

        val from = Vector3f(targetX, targetY, 1.5f)
        val at = Vector3f(targetX, targetY, targetZ)
        val dir = Vector3f(at).sub(from).normalize()

        val seen = leafProbe.pixel(
            camera(from.x, from.y, from.z, dir.x, dir.y, dir.z, fov = 0.2f), samples = 12
        )
        // STONE is grey and very nearly colourless; LEAVES is green. Which of the two came
        // back is the question, and it is answerable without reaching into anything private.
        val greenness = seen[1] / maxOf(seen[0], seen[2], 1e-4f)
        return greenness < 1.15f
    }

    if (hole.isNotEmpty() && solid.isNotEmpty()) {
        check("a hole in a leaf lets the ray through to what is behind it",
            passesThroughLeaf(hole[0].first, hole[0].second), "the leaf stopped it anyway")
        check("solid art in a leaf stops the ray",
            !passesThroughLeaf(solid[0].first, solid[0].second), "the ray went through solid art")
        // Several of each, because a single pair would also pass if the tracer were
        // ignoring the Tile entirely and getting the right answer by accident.
        val holesThrough = hole.filter { passesThroughLeaf(it.first, it.second) }.size
        val solidsThrough = solid.count { passesThroughLeaf(it.first, it.second) }
        check("every hole in the leaf is a hole in the light too",
            holesThrough == hole.size, "$holesThrough of ${hole.size} holes let a ray through")
        check("no solid art pixel in the leaf is a hole",
            solidsThrough == 0, "$solidsThrough of ${solid.size} solid pixels let a ray through")
    }

    // --- Light is light ---

    println("\n== Path tracing: light ==")

    /**
 * A room with a ceiling that has one hole in it, and nothing above that ceiling.
 *
 * The shape matters. A pillar outdoors casts a shadow, but the shadowed side is still lit
 * by the sky, so the ratio between the two is a few percent and any assertion about it is
 * really an assertion about how bright the sky happens to be. Put the Sun overhead and roof
 * the room, and the hole is the *only* way light gets in at all.
 *
 * The ceiling is the top, and that clause is load-bearing. An earlier version also walled
 * the Chunk at y=15, so the gap let light in to a second ceiling fourteen Cells above it,
 * both spots under it were equally dark, and the check could not tell a working shadow ray
 * from a broken one.
 */
    fun roofedRoom(): GridWorld {
        val chunk = blankChunk(0, 0, 0)
        for (y in 0 until 16) for (x in 0 until 16) for (z in 0 until 16) {
            val wall = x == 0 || x == 15 || z == 0 || z == 15 || y == 0
            chunk.setBlock(x, y, z, if (wall) BlockType.STONE else BlockType.AIR)
        }
        // A ceiling, with a single gap in it.
        for (x in 1..14) for (z in 1..14) chunk.setBlock(x, 8, z, BlockType.STONE)
        chunk.setBlock(8, 8, 8, BlockType.AIR)
        return worldOf(chunk)
    }

    val overhead = Vector3f(0.05f, 1f, 0.02f).normalize()

    /**
     * Looking straight down at one spot of floor, through a lens narrow enough that the
     * sub-pixel jitter stays inside the Cell being asked about. The Sun is overhead, because
     * the whole point of this shape of room is that the hole is the only way light gets in.
     */
    fun downCamera(x: Float, z: Float, height: Float = 3.5f) =
        camera(x, height, z, 0f, -1f, 0f, fov = 2f)

    val underHole = conePixels(roofedRoom(), downCamera(8.5f, 8.5f), 300, sun = overhead)
    val underRoof = conePixels(roofedRoom(), downCamera(3.5f, 3.5f), 300, sun = overhead)
    val holeLevel = luminance(underHole)
    val roofLevel = luminance(underRoof)
    println("  floor under the gap $holeLevel, floor under the roof $roofLevel")
    check("a hole in a roof lets the Sun through to the floor",
        holeLevel > roofLevel * 5 && holeLevel > 0.02f,
        "under the gap $holeLevel, under the roof $roofLevel")
    check("and the roof itself really does cast a shadow",
        roofLevel < 0.01f, "the floor under the roof reads $roofLevel")

    /**
 * A sealed room, optionally with a slab of GLOWSTONE in one wall.
 *
 * This is the claim the whole renderer rests on: that light gets into a closed box without
 * any of it being *put* there. Nothing computes a level for that Cell and stores it — a ray
 * leaves the emitter, crosses the room, and lands on the floor.
 *
 * The emitter is a slab rather than one Cell because of how this light is currently found:
 * a bounce has to *hit* an emitter by chance, and a single Cell subtends about a degree from
 * across a room. A hundred samples would then be measuring the luck of the draw rather than
 * the physics. That is a real property of the renderer and it is stated where it is asserted
 * — the fix is an emitter index and direct sampling, not a smaller test.
 */
    fun sealedRoom(emissive: Boolean): GridWorld {
        val chunk = blankChunk(0, 0, 0)
        for (x in 0 until 16) for (y in 0 until 16) for (z in 0 until 16) {
            val shell = x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15
            chunk.setBlock(x, y, z, if (shell) BlockType.STONE else BlockType.AIR)
        }
        if (emissive) for (x in 4..7) for (y in 4..7) chunk.setBlock(x, 4, 1, BlockType.GLOWSTONE)
        return worldOf(chunk)
    }

    val openSky = conePixels(worldOf(blankChunk(0, 0, 0)), camera(8.5f, 8.5f, 8.5f, 0f, 1f, 0f), 64)
    check("an unobstructed ray leaving the world returns clear sky", run {
        openSky.all { it.isFinite() && it >= 0f } && openSky[2] > openSky[0]
    }, "sky radiance was ${openSky.contentToString()}")

    val darkRoom = conePixels(sealedRoom(false), downCamera(10.5f, 10.5f, 6.5f), 400)
    val litRoom = conePixels(sealedRoom(true), downCamera(10.5f, 10.5f, 6.5f), 400)
    println("  sealed room without an emitter ${luminance(darkRoom)}, with one ${luminance(litRoom)}")
    check("a sealed room with no emitter in it is exactly dark",
        luminance(darkRoom) < 1e-6f, "it reads ${luminance(darkRoom)}")
    check("a glowing block lights a sealed room from the inside",
        luminance(litRoom) > luminance(darkRoom) * 100 && luminance(litRoom) > 0.001f,
        "dark ${luminance(darkRoom)}, lit ${luminance(litRoom)}")

    // --- The image is an image ---

    println("\n== Path tracing: the picture ==")

    /**
     * A wall with a green panel on its left and a warm one on its right.
     *
     * A mirrored image is the classic path-tracer bug and it is invisible in a picture of
     * natural terrain — nearly everything in this game is the same colour. So the scene is
     * asymmetric on purpose, and the markers are panels rather than single Cells: at twenty
     * Cells away one Cell is a degree and a half of view, which is *narrower than one pixel*
     * of the check's own image, and a marker that thin measures the jitter rather than the
     * thing it was put there to measure.
     */
    val sceneChunk = blankChunk(0, 0, 0)
    for (y in 0 until 16) for (x in 0 until 16) sceneChunk.setBlock(x, y, 0, BlockType.STONE)
    for (x in 2..6) for (y in 6..10) sceneChunk.setBlock(x, y, 1, BlockType.LEAVES)
    for (x in 11..15) for (y in 6..10) sceneChunk.setBlock(x, y, 1, BlockType.GLOWSTONE)
    val sceneWorld = worldOf(sceneChunk)

    val pixels = 32
    val image = tracePixels(sceneWorld, camera(8.5f, 8.5f, 20f, 0f, 0f, -1f), pixels, 150)
    fun greennessAt(index: Int): Float {
        val p = image[index]
        // Green relative to the *brightest* of red and blue, not to red. Dividing by red
        // alone rates a blue sky as green, which is exactly backwards — an earlier version
        // of this check did that and reported the Leaves panel as barely tinted.
        return p[1] / maxOf(p[0], p[2], 1e-4f)
    }

    // Left half against right half, which needs no arithmetic about where anything projects to.
    //
    // A hard-coded column number is a guess about the projection, and the earlier versions of
    // this check kept needing one: first a column that averaged mostly open sky, then a
    // panel whose bounds were computed and still landed beside it. Halves of the image are
    // defined by the image itself and cannot drift from it.
    val half = pixels / 2
    val leftColumn = (0 until pixels / 2).flatMap { c ->
        (0 until pixels).map { r -> greennessAt(r * pixels + c) }
    }.average()
    val rightColumn = (half until pixels).flatMap { c ->
        (0 until pixels).map { r -> greennessAt(r * pixels + c) }
    }.average()
    println("  greenness on the left $leftColumn, on the right $rightColumn")
    check("the world is not mirrored left to right", leftColumn > rightColumn * 1.15,
        "greenness on the left $leftColumn, on the right $rightColumn — the image is flipped")

    var finite = true
    var nonNegative = true
    for (i in 0 until pixels * pixels) {
        for (c in 0 until 3) {
            val v = image[i][c]
            if (!v.isFinite()) finite = false
            if (v < 0f) nonNegative = false
        }
    }
    check("no pixel of a traced frame is NaN or infinite", finite)
    check("no pixel of a traced frame is negative", nonNegative)

    val film = run {
        val tracer = PathTracer(sceneWorld, atlas, threads = 1)
        tracer.resize(8, 8)
        repeat(3) { tracer.sample(scene(camera(8.5f, 8.5f, 20f, 0f, 0f, -1f))) }
        tracer.rgba()
    }
    check("the film produces one opaque RGBA byte per pixel", run {
        // Alpha is the fourth byte, not the first — reading every fourth byte from zero
        // reads red, which is a picture and therefore never a constant.
        film.size == 8 * 8 * 4 && film.indices.step(4).all { film[it + 3] == 255.toByte() }
    })

    // --- Accumulation is an average ---

    println("\n== Path tracing: accumulation ==")
    val counting = PathTracer(sceneWorld, atlas, threads = 1)
    counting.resize(4, 4)
    check("nothing has been drawn yet", counting.sampleCount == 0)
    counting.sample(scene(camera(8.5f, 8.5f, 20f, 0f, 0f, -1f)))
    check("one sample has been drawn", counting.sampleCount == 1)
    counting.reset()
    check("resetting empties the average", counting.sampleCount == 0)

    check("tracing the same scene twice gives the same picture", run {
        fun picture(): ByteArray {
            val tracer = PathTracer(sceneWorld, atlas, threads = 1)
            tracer.resize(8, 8)
            repeat(4) { tracer.sample(scene(camera(8.5f, 8.5f, 20f, 0f, 0f, -1f))) }
            return tracer.rgba()
        }
        picture().contentEquals(picture())
    })

    check("more threads give the same answer as one", run {
        // Each band writes only its own pixels, so the threads cannot be interfering. If
        // they are, this is where it shows — and it would show as a picture that is right
        // on one machine and speckled on another.
        fun picture(threads: Int): ByteArray {
            val tracer = PathTracer(sceneWorld, atlas, threads = threads)
            tracer.resize(16, 16)
            repeat(3) { tracer.sample(scene(camera(8.5f, 8.5f, 20f, 0f, 0f, -1f))) }
            return tracer.rgba()
        }
        picture(1).contentEquals(picture(4))
    })

    // --- Cost, measured ---

    val started = System.nanoTime()
    val perf = PathTracer(sceneWorld, atlas, threads = 1)
    perf.resize(64, 64)
    repeat(4) { perf.sample(scene(camera(8.5f, 8.5f, 20f, 0f, 0f, -1f))) }
    val perSample = (System.nanoTime() - started) / 4.0 / (64 * 64)
    println("  one thread: ${"%.0f".format(perSample / 1000.0)} us per pixel-sample")
    // A path tracer is allowed to be slow. It is not allowed to be *unknown* slow, and a
    // regression that quietly doubles the cost of a Cell walk would not show up as a
    // failure anywhere else in this file.
    check("a pixel costs under 200 microseconds on one thread", perSample < 200_000,
        "was ${"%.0f".format(perSample / 1000.0)} us")

    // --- The film, measured separately ---
    //
    // `rgba()` sits *outside* the window `drawTracedWorld` measures as the frame's trace
    // cost, so the adaptive resolution controller has never seen it. That is the kind of
    // thing that is easy to assert is expensive and hard to prove, so it is measured here
    // rather than argued about: the film is three `exp` and three `pow` per pixel per
    // channel on one thread, and at the resolution the tracer actually runs at, that is
    // either a rounding error or a third of the frame.
    //
    // The figure is per pixel of output, so it can be multiplied by whatever resolution the
    // game settles on without re-running this.
    val filmTracer = PathTracer(sceneWorld, atlas, threads = 1)
    filmTracer.resize(128, 128)
    filmTracer.sample(scene(camera(8.5f, 8.5f, 20f, 0f, 0f, -1f)))
    filmTracer.rgba() // warm the JIT, so the number below is the film and not the compiler

    //
    // The result is consumed, and that is the whole subtlety: `rgba()` allocates and
    // returns a ByteArray, and an allocation whose result is thrown away does not escape,
    // so the JIT is entitled to delete the entire computation and leave a loop that times
    // almost nothing. The first version of this measured 0.2 ns a pixel — which is not a
    // film, it is the loop — and printed it beside a 161 ms figure derived from the same
    // number. Two answers from one measurement is the signature that the measurement is
    // wrong, so the two are now derived from one place and the bytes are read back.
    val sink = java.util.concurrent.atomic.AtomicInteger(0)
    val filmStarted = System.nanoTime()
    val frames = 20
    repeat(frames) { sink.addAndGet(filmTracer.rgba()[0].toInt()) }
    val perFilmPixel = (System.nanoTime() - filmStarted) / 1e9 / frames / (128.0 * 128.0)

    val msAt = { w: Int, h: Int -> "%.1f".format(perFilmPixel * w * h * 1000) }
    val nsPerPixel = "%.0f".format(perFilmPixel * 1e9)
    println("  film: $nsPerPixel ns per pixel, one thread (sink ${sink.get()})")
    println("    at 1280x720 that is ${msAt(1280, 720)} ms per frame")
    println("    at  640x360 that is ${msAt(640, 360)} ms per frame")

    // The budget is what the game gives the whole frame. A film that costs a meaningful
    // share of it on the main thread, with nothing else on that thread able to overlap it,
    // is not a rounding error. This is measured rather than asserted because it was
    // asserted first and nobody knows which of us was right.
    check("the film costs under 100 ns per pixel", perFilmPixel < 100e-9,
        "was $nsPerPixel ns per pixel, ${msAt(1280, 720)} ms a frame at 1280x720")
}
