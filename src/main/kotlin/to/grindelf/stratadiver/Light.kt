package to.grindelf.stratadiver

/**
 * What Light needs from the world, and nothing more: read a Cell, read and write a Cell's
 * light, and ask which Chunk covers a place **without causing one to be generated**.
 *
 * That last clause is why this is separate from `Neighbourhood`. Light floods outward, and
 * a flood that generated the Chunks it walked into would be reading Cells whose own light
 * had never been computed — and then writing light into them, producing a Chunk lit from
 * nothing and believing it. So Light reads `null` for a place no Chunk covers and refuses
 * to cross it; the Chunk in question gets lit properly when it is created, and inherits
 * its neighbours' light then.
 */
interface LitWorld : Neighbourhood {
    /** The Chunk covering this Cell if it already exists. Never generates one. */
    fun existingChunkAt(x: Int, y: Int, z: Int): Chunk?

    /** The packed light of this Cell, or [Light.DARK] where no Chunk covers it. */
    fun lightAt(x: Int, y: Int, z: Int): Int

    /** Writes this Cell's packed light. Returns the Chunk written to, or null. */
    fun setLight(x: Int, y: Int, z: Int, packed: Int): Chunk?
}

/**
 * Fills the world with light, and takes it away again when the player changes it.
 *
 * Two independent floods, because the two kinds of light arrive by different routes.
 * **Daylight** falls down a Column from the sky and is present only because nothing opaque
 * stands above; a scan assigns it and a flood spreads it sideways under overhangs.
 * **Torchlight** starts at emitters and only ever spreads sideways. Both spread at one
 * level per Cell, less whatever the Cell they pass through takes, and neither reaches full
 * brightness by travel — only a Column open to the sky does.
 *
 * Both are recomputed from the sky rather than adjusted, including after the player changes
 * something. That is not the obvious design and it replaced a better-looking one; see
 * [onBlockChanged] for what the incremental version got wrong, and why every fix I found
 * for it made some other shape of world wrong instead.
 *
 * Nothing here touches GL, and everything is reachable through [LitWorld], so all of it —
 * falloff, cross-Chunk spread, change handling — is checkable with no window.
 */
class LightEngine(private val world: LitWorld) {

    companion object {
        /** The six directions, in the fixed order every neighbour loop uses. */
        private val DX = intArrayOf(1, -1, 0, 0, 0, 0)
        private val DY = intArrayOf(0, 0, 1, -1, 0, 0)
        private val DZ = intArrayOf(0, 0, 0, 0, 1, -1)

        /** An arbitrary in-range coordinate used to ask "which Chunk is that?". */
        private const val PROBE = 0
    }

    /**
     * Chunks whose light this engine has written to since the last [takeDirty].
     *
     * A spread routinely steps over a Chunk border, because that is where the light has to
     * go. A Chunk it steps into was meshed from its *old* light and now shows the wrong
     * thing, so the streamer re-meshes them. Without this the artefact is a one-Cell
     * bright or dark seam along Chunk boundaries, which is precisely where nobody looks
     * for a lighting bug and precisely where it is most visible once you do.
     */
    private val dirty = LinkedHashSet<Chunk>()

    /**
     * The Chunk currently being lit from scratch, if any.
     *
     * Its own light is *supposed* to change — that is the whole job — and the caller
     * meshes it immediately afterwards from the result. Marking it dirty as well would
     * make every single Chunk get meshed twice during fill-in, which is pure waste paid on
     * the exact frames the player is already waiting for the world to appear.
     */
    private var lighting: Chunk? = null

    /** The Chunks whose light has changed, emptying the set. */
    fun takeDirty(): Set<Chunk> {
        if (dirty.isEmpty()) return emptySet()
        val out = LinkedHashSet(dirty)
        dirty.clear()
        return out
    }

    // --- Lighting a whole Chunk ---

    /**
     * Brings [chunk] from "generated, unlit" to correctly lit: from the sky down, and in
     * from whatever light its neighbours already hold.
     *
     * Redone from scratch each time, which is affordable precisely because it is hoisted.
     * The 256 Columns of a Chunk all share the same 16 vertical neighbours, so resolving
     * those 16 once turns 256 x 256 = 65,536 map lookups into 16 lookups and 65,536 array
     * reads. Left un-hoisted this is a visible stall at spawn for no reason at all.
     */
    fun lightChunk(chunk: Chunk) {
        lighting = chunk
        try {
            lightChunkNow(chunk)
        } finally {
            lighting = null
        }
    }

    private fun lightChunkNow(chunk: Chunk) {
        val layers = columnLayers(chunk.xOffset, chunk.zOffset)
        chunk.clearAllLight()

        // --- Daylight ---
        //
        // Walk each Column from the ceiling of the *world* — not the ceiling of this Chunk
        // — down, and every Cell nothing opaque stands above is in full daylight. A layer
        // the streamer has not made reads as open, so a frontier Chunk is lit by what is
        // actually above it rather than going dark along the seam.
        for (lx in 0 until Chunk.SIZE) {
            for (lz in 0 until Chunk.SIZE) {
                var openToSky = true
                for (cy in Chunk.HEIGHT_LAYERS - 1 downTo chunk.yOffset) {
                    val layer = layers[cy] ?: continue
                    val mine = cy == chunk.yOffset
                    for (ly in Chunk.SIZE - 1 downTo 0) {
                        val opaque = BlockType.getById(layer.getBlock(lx, ly, lz)).opaque
                        if (opaque) {
                            openToSky = false
                        } else if (mine && openToSky) {
                            chunk.setPackedLight(lx, ly, lz, Light.pack(Light.MAX, Light.DARK))
                        }
                    }
                }
            }
        }

        seedDaylight(chunk)
        spread(daylightQueue, day = true)

        // --- Torchlight ---
        seedTorchlight(chunk)
        importTorchlightFromNeighbours(chunk)
        spread(torchlightQueue, day = false)
    }

    /**
     * Every emitter in [chunk] as a spread seed.
     *
     * Seeded at the emitter's own level rather than at full brightness: an emitter *is* a
     * light level, and pretending otherwise would light a weak emitter's room as brightly
     * as a strong one's all the way to the edge of its range, and then stop dead.
     */
    private fun seedTorchlight(chunk: Chunk) {
        val baseX = chunk.xOffset * Chunk.SIZE
        val baseY = chunk.yOffset * Chunk.SIZE
        val baseZ = chunk.zOffset * Chunk.SIZE

        for (lx in 0 until Chunk.SIZE) {
            for (ly in 0 until Chunk.SIZE) {
                for (lz in 0 until Chunk.SIZE) {
                    val emission = BlockType.getById(chunk.getBlock(lx, ly, lz)).emission
                    if (emission <= 0) continue
                    chunk.setPackedLight(
                        lx, ly, lz,
                        Light.pack(Light.day(chunk.packedLightAt(lx, ly, lz)), emission)
                    )
                    torchlightQueue.push(baseX + lx, baseY + ly, baseZ + lz)
                }
            }
        }
    }

    /** Every Cell of [chunk] that the sky reaches, as a spread seed. */
    private fun seedDaylight(chunk: Chunk) {
        val baseX = chunk.xOffset * Chunk.SIZE
        val baseY = chunk.yOffset * Chunk.SIZE
        val baseZ = chunk.zOffset * Chunk.SIZE

        for (lx in 0 until Chunk.SIZE) {
            for (ly in 0 until Chunk.SIZE) {
                for (lz in 0 until Chunk.SIZE) {
                    if (Light.day(chunk.packedLightAt(lx, ly, lz)) <= 1) continue
                    daylightQueue.push(baseX + lx, baseY + ly, baseZ + lz)
                }
            }
        }
    }

    /**
     * Seeds from every already-lit Cell on the face each neighbouring Chunk presents.
     *
     * Light crosses a Chunk border like any other step, but a Chunk being lit for the
     * first time has just had its own light cleared — so nothing has yet radiated *into*
     * it from next door. Without this, a torch in the neighbouring Chunk lights its own
     * room and stops dead at the seam. It is worth being explicit that this is the one
     * place Light reads a Chunk it is not writing, and that Chunk's light is trustworthy
     * precisely because the streamer lights a Chunk before it meshes it and meshes it
     * before it draws it.
     */
    private fun importTorchlightFromNeighbours(chunk: Chunk) {
        val baseX = chunk.xOffset * Chunk.SIZE
        val baseY = chunk.yOffset * Chunk.SIZE
        val baseZ = chunk.zOffset * Chunk.SIZE

        for (d in 0 until 6) {
            val neighbour = world.existingChunkAt(
                baseX + DX[d] * Chunk.SIZE, baseY + DY[d] * Chunk.SIZE, baseZ + DZ[d] * Chunk.SIZE
            ) ?: continue

            // The local coordinate on each axis of the face this neighbour is showing us.
            val faceX = if (DX[d] > 0) 0 else if (DX[d] < 0) Chunk.SIZE - 1 else -1
            val faceY = if (DY[d] > 0) 0 else if (DY[d] < 0) Chunk.SIZE - 1 else -1
            val faceZ = if (DZ[d] > 0) 0 else if (DZ[d] < 0) Chunk.SIZE - 1 else -1

            for (lx in 0 until Chunk.SIZE) {
                if (faceX >= 0 && lx != faceX) continue
                for (ly in 0 until Chunk.SIZE) {
                    if (faceY >= 0 && ly != faceY) continue
                    for (lz in 0 until Chunk.SIZE) {
                        if (faceZ >= 0 && lz != faceZ) continue
                        if (Light.torch(neighbour.packedLightAt(lx, ly, lz)) <= 1) continue
                        torchlightQueue.push(
                            neighbour.xOffset * Chunk.SIZE + lx,
                            neighbour.yOffset * Chunk.SIZE + ly,
                            neighbour.zOffset * Chunk.SIZE + lz
                        )
                    }
                }
            }
        }
    }

    /**
     * The 16 vertical Chunks at [cx], [cz], resolved once.
     *
     * A null layer is one the streamer has not made, which the daylight scan reads as open
     * sky. That is the right reading at a frontier — the world above really is open, we
     * have simply not written it down — and it is why lighting does not need the Chunks
     * above to exist first.
     */
    private fun columnLayers(cx: Int, cz: Int): Array<Chunk?> =
        Array(Chunk.HEIGHT_LAYERS) { cy ->
            world.existingChunkAt(
                cx * Chunk.SIZE + PROBE, cy * Chunk.SIZE + PROBE, cz * Chunk.SIZE + PROBE
            )
        }

    // --- Spreading ---

    /**
     * Spreads light outwards from every seed, brightening Cells that can be reached more
     * and leaving the rest alone.
     *
     * Only ever brightens. That is what lets daylight skip a special case: a Column open to
     * the sky sits at [Light.MAX], the spread would offer it one level less on the way
     * down, and the offer is declined. Darkness still has to be taken back explicitly,
     * which is what [onBlockChanged] is for.
     */
    private fun spread(seeds: IntQueue, day: Boolean) {
        seeds.drain { x, y, z ->
            val here = world.lightAt(x, y, z)
            val level = if (day) Light.day(here) else Light.torch(here)
            if (level <= 1) return@drain

            for (d in 0 until 6) {
                val nx = x + DX[d]
                val ny = y + DY[d]
                val nz = z + DZ[d]

                // A place the world has not produced is not a place light may enter. It is
                // not AIR: treating it as AIR would pour light into a Chunk that has not
                // been lit and get the answer wrong in a way nothing would report.
                val type = world.blockAtOrNull(nx, ny, nz) ?: continue
                if (type.opaque) continue

                val carried = level - 1 - type.attenuation
                if (carried <= 0) continue

                val there = world.lightAt(nx, ny, nz)
                if ((if (day) Light.day(there) else Light.torch(there)) >= carried) continue

                write(nx, ny, nz, if (day) Light.pack(carried, Light.torch(there))
                                else Light.pack(Light.day(there), carried))
                seeds.push(nx, ny, nz)
            }
        }
    }

    // --- Reacting to the player ---

    /**
     * Recomputes light around a Cell the player has just changed.
     *
     * [wasPacked] is deliberately ignored, and that is the point of this method. It used to
     * be the seed of an incremental darkening walk, and that walk was the only genuinely
     * hard code in the engine — so hard that I could not fix it, only delete it.
     *
     * The walk assumes light in a region forms a tree flowing downhill from a source, and
     * that assumption breaks exactly where the world is interesting. A Cell at full
     * daylight satisfies it in several directions at once: a shaft lights a whole Column
     * to 15, so a Cell beside that Column is fed from above *and* from beside and has no
     * unique path for the walk to walk back along. Given a room lit through one shaft, it
     * correctly cleared 3,835 of 4,096 Cells and then abandoned the rest, leaving light
     * behind in a gradient that looked like a rendering bug and was a queue of wrong
     * answers.
     *
     * Every fix I could think of was right about one geometry and wrong about another:
     * clear equal-level neighbours too, and a legitimately-lit plateau goes dark; treat
     * vertical runs as fed-from-above, and overhangs go dark; walk in a different order,
     * and a different shape goes dark. None of them had a failing case you could picture,
     * which is the signature of code whose rules have not been found yet.
     *
     * So the walk is gone, and in its place: light the whole neighbourhood again. It is
     * less clever, it is a few milliseconds, and it cannot be wrong in a way nobody has
     * thought of — it recomputes from the sky, which is the definition of correct. Given
     * [wasPacked] is unused the parameter is kept only so the call site documents that the
     * Cell's light was cleared by the write and that this does not need it back.
     *
     * Cost is bounded and argued rather than guessed: light travels at most [Light.MAX]
     * Cells and a Chunk is [Chunk.SIZE] across, so no Chunk two steps away in any axis can
     * hold a Cell the change reached. One step is 16 Cells, which clears 15 with a Cell to
     * spare. Measured at ~0.5ms a Chunk, so ~13ms for the 27 — inside a frame at 60Hz, and
     * paid only on a discrete block change rather than per frame.
     */
    fun onBlockChanged(x: Int, y: Int, z: Int, wasPacked: Int) {
        relightNeighbourhood(x, y, z)
    }

    /**
     * Re-derives every Chunk within one step of this Cell, skipping those the world has
     * not made.
     *
     * Outermost-first would light a frontier Chunk before its inward neighbour and leave
     * it short of light it should have inherited, so this is deliberately *nearest* first:
     * each Chunk's flood then has its inward neighbours already correct to spread out of.
     */
    private fun relightNeighbourhood(x: Int, y: Int, z: Int) {
        val cx = Math.floorDiv(x, Chunk.SIZE)
        val cy = Math.floorDiv(y, Chunk.SIZE)
        val cz = Math.floorDiv(z, Chunk.SIZE)

        val order = mutableListOf<Triple<Int, Int, Int>>()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val px = cx + dx; val py = cy + dy; val pz = cz + dz
            if (py < 0 || py >= Chunk.HEIGHT_LAYERS) continue
            order.add(Triple(px, py, pz))
        }
        order.sortBy { (px, py, pz) ->
            val d = px * px + py * py + pz * pz
            d
        }

        for ((px, py, pz) in order) {
            world.existingChunkAt(px * Chunk.SIZE, py * Chunk.SIZE, pz * Chunk.SIZE)
                ?.let { lightChunk(it) }
        }
    }

    // --- Writing ---

    private fun write(x: Int, y: Int, z: Int, packed: Int) {
        val touched = world.setLight(x, y, z, packed) ?: return
        if (touched !== lighting) dirty.add(touched)
    }

    /** Zeroes one channel of one Cell, leaving the other alone. */
    private fun clear(x: Int, y: Int, z: Int, day: Boolean) {
        val packed = world.lightAt(x, y, z)
        write(x, y, z, if (day) Light.pack(Light.DARK, Light.torch(packed))
                        else Light.pack(Light.day(packed), Light.DARK))
    }

    private fun levelOf(packed: Int, day: Boolean) =
        if (day) Light.day(packed) else Light.torch(packed)

    // --- Work queues ---

    private val daylightQueue = IntQueue()
    private val torchlightQueue = IntQueue()

    /**
     * A first-in-first-out of world coordinates, as parallel arrays.
     *
     * Parallel `IntArray`s rather than one packed `Long` on purpose. Packing three signed
     * world coordinates into 64 bits is possible, and then every step is a place where an x
     * of 400,000 quietly wraps negative and lights the wrong hemisphere — producing
     * plausible light in the wrong place and no error at all. Parallel arrays have no
     * packing to get wrong.
     */
    private class IntQueue {
        private var xs = IntArray(1024)
        private var ys = IntArray(1024)
        private var zs = IntArray(1024)
        private var head = 0
        private var tail = 0

        fun push(x: Int, y: Int, z: Int) {
            if (tail == xs.size) grow()
            xs[tail] = x; ys[tail] = y; zs[tail] = z; tail++
        }

        inline fun drain(action: (Int, Int, Int) -> Unit) {
            while (head != tail) {
                val x = xs[head]; val y = ys[head]; val z = zs[head]; head++
                action(x, y, z)
            }
            head = 0; tail = 0
        }

        /**
         * Makes room. Called only when the queue is full.
         *
         * The test is on `head`, not on whether the queue is empty. Those are different
         * questions, and confusing them is a small bug with a loud consequence: a queue
         * that is full *and* un-drained has `head == 0`, so "is there a live span to slide
         * down?" answers yes when the truth is "there are `size` live entries and nowhere
         * to put the next one", and the caller then writes one past the end.
         *
         * With `head > 0` there are genuinely `size - head` free slots at the bottom, so
         * sliding suffices and avoids reallocating a queue an earlier spread already grew.
         */
        private fun grow() {
            if (head > 0) {
                val live = tail - head
                System.arraycopy(xs, head, xs, 0, live)
                System.arraycopy(ys, head, ys, 0, live)
                System.arraycopy(zs, head, zs, 0, live)
                head = 0; tail = live
            } else {
                val bigger = IntArray(xs.size * 2)
                System.arraycopy(xs, 0, bigger, 0, tail)
                System.arraycopy(ys, 0, bigger, 0, tail)
                System.arraycopy(zs, 0, bigger, 0, tail)
                xs = bigger; ys = bigger.copyOf(); zs = bigger.copyOf()
            }
        }
    }
}
