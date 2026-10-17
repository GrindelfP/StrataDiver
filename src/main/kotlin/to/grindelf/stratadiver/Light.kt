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
 * The algorithm is the ordinary one: seed, spread, and on a change *darken first, then let
 * what survives spread back in*. The darkening half is the half that is easy to omit and
 * produces the classic black blob, where a wall the player has just built leaves the Cells
 * behind it holding exactly the light they had when the wall was not there.
 *
 * Nothing here touches GL, and everything is reachable through [LitWorld], so all of it —
 * falloff, cross-Chunk spread, removal — is checkable with no window.
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
     * [wasPacked] is that Cell's light *before* the write, and it cannot be recovered
     * afterwards: `Chunk.setBlock` clears a Cell's light precisely because the numbers
     * there described a world that no longer exists. Without it there is nowhere to start
     * the darkening walk from, and the alternative — inferring the old level — is a guess.
     *
     * The order is the entire design, and it is not the order that reads most naturally:
     *
     *  1. **Darken.** Take back the light the change was letting through. Doing this after
     *     the Column is re-lit would delete the light just re-established, because the
     *     Column walk hands the Cells below the change the very level the darkening walk is
     *     about to recognise as "fed only through here".
     *  2. **Re-derive the Column**, because the sky is a source no spread can rediscover.
     *     It exists because a Column is open, which is a fact about everything *above* the
     *     change rather than about the change.
     *  3. **Spread** from what survived and from the Cells still lit around the change.
     */
    fun onBlockChanged(x: Int, y: Int, z: Int, wasPacked: Int) {
        darkenFromChange(x, y, z, wasPacked)
        relightColumn(x, z)
        seedAround(x, y, z)

        spread(daylightQueue, day = true)
        spread(torchlightQueue, day = false)
    }

    /**
     * Takes back the light the changed Cell used to pass on, and leaves the Cells that were
     * keeping themselves lit queued to re-seed.
     *
     * A Cell is only darkened if the change could plausibly have been what was lighting
     * it — its level is below the level being removed, which means something else was
     * brighter and reached it another way. Cells at or above the removed level were
     * carrying their own light, and are left alone and re-seeded.
     */
    private fun darkenFromChange(x: Int, y: Int, z: Int, wasPacked: Int) {
        for (day in booleanArrayOf(true, false)) {
            val level = if (day) Light.day(wasPacked) else Light.torch(wasPacked)
            if (level <= 0) continue
            darken(x, y, z, level, day, if (day) daylightQueue else torchlightQueue)
        }
    }

    private fun darken(x: Int, y: Int, z: Int, level: Int, day: Boolean, seeds: IntQueue) {
        walk.clear()
        clear(x, y, z, day)
        walk.push(x, y, z, level)

        walk.drain { cx, cy, cz, atLevel ->
            for (d in 0 until 6) {
                val nx = cx + DX[d]
                val ny = cy + DY[d]
                val nz = cz + DZ[d]
                val type = world.blockAtOrNull(nx, ny, nz) ?: continue
                if (type.opaque) continue

                val neighbourLevel = levelOf(world.lightAt(nx, ny, nz), day)
                if (neighbourLevel == 0) continue

                val fedOnlyByUs = neighbourLevel < atLevel ||
                        // Daylight falls a whole Column at full strength, so a Cell
                        // directly below another at the same brightness is fed by it and by
                        // nothing else. Treating it as self-sufficient is what leaves a lit
                        // Column in darkness under the block the player just placed.
                        (day && d == 3 && neighbourLevel == atLevel)

                if (fedOnlyByUs) {
                    clear(nx, ny, nz, day)
                    walk.push(nx, ny, nz, neighbourLevel)
                } else {
                    seeds.push(nx, ny, nz)
                }
            }
        }
    }

    /**
     * Walks one Column from the ceiling of the world and restores full daylight to every
     * Cell nothing opaque stands above.
     */
    private fun relightColumn(x: Int, z: Int) {
        val layers = columnLayers(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE))
        val lx = Math.floorMod(x, Chunk.SIZE)
        val lz = Math.floorMod(z, Chunk.SIZE)

        var openToSky = true
        for (cy in Chunk.HEIGHT_LAYERS - 1 downTo 0) {
            val layer = layers[cy] ?: continue
            for (ly in Chunk.SIZE - 1 downTo 0) {
                if (BlockType.getById(layer.getBlock(lx, ly, lz)).opaque) {
                    openToSky = false
                    continue
                }
                if (!openToSky) continue

                val worldY = cy * Chunk.SIZE + ly
                val packed = world.lightAt(x, worldY, z)
                if (Light.day(packed) >= Light.MAX) continue
                write(x, worldY, z, Light.pack(Light.MAX, Light.torch(packed)))
                daylightQueue.push(x, worldY, z)
            }
        }
    }

    /**
     * Seeds both channels from around the change: the emitters in its Chunk, and every
     * Cell next to it that is still lit.
     *
     * A neighbourhood rather than a fresh spread from scratch. The rest of the world is
     * unchanged, and re-deriving it would be both slow and, near a frontier, impossible —
     * there is no seed available for a region of sky light whose top the walk cannot see.
     */
    private fun seedAround(x: Int, y: Int, z: Int) {
        world.existingChunkAt(x, y, z)?.let { chunk ->
            val baseX = chunk.xOffset * Chunk.SIZE
            val baseY = chunk.yOffset * Chunk.SIZE
            val baseZ = chunk.zOffset * Chunk.SIZE
            for (lx in 0 until Chunk.SIZE) {
                for (ly in 0 until Chunk.SIZE) {
                    for (lz in 0 until Chunk.SIZE) {
                        if (BlockType.getById(chunk.getBlock(lx, ly, lz)).emission > 0) {
                            torchlightQueue.push(baseX + lx, baseY + ly, baseZ + lz)
                        }
                    }
                }
            }
        }

        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            val nx = x + dx; val ny = y + dy; val nz = z + dz
            if (world.blockAtOrNull(nx, ny, nz)?.opaque != false) continue
            val packed = world.lightAt(nx, ny, nz)
            if (Light.day(packed) > 1) daylightQueue.push(nx, ny, nz)
            if (Light.torch(packed) > 1) torchlightQueue.push(nx, ny, nz)
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
    private val walk = LevelQueue()

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

    /** An [IntQueue] that also carries a light level, for the darkening walk. */
    private class LevelQueue {
        private var xs = IntArray(256)
        private var ys = IntArray(256)
        private var zs = IntArray(256)
        private var ls = IntArray(256)
        private var head = 0
        private var tail = 0

        fun clear() { head = 0; tail = 0 }

        fun push(x: Int, y: Int, z: Int, level: Int) {
            if (tail == xs.size) grow()
            xs[tail] = x; ys[tail] = y; zs[tail] = z; ls[tail] = level; tail++
        }

        inline fun drain(action: (Int, Int, Int, Int) -> Unit) {
            while (head != tail) {
                val x = xs[head]; val y = ys[head]; val z = zs[head]; val l = ls[head]; head++
                action(x, y, z, l)
            }
            head = 0; tail = 0
        }

        /**
         * Doubles the queue, **keeping what is in it**.
         *
         * The copying is the whole of this method. A version that reallocated without it
         * compiled, ran, and produced a room that was correctly darkened for 256 Cells and
         * then quietly abandoned — light left behind in a ragged wedge around the edges of
         * wherever the walk had reached when the queue overflowed, and nothing anywhere
         * reporting that the walk had stopped early. It is the worst shape a bug can have:
         * plausible output, no error, and the threshold nowhere near it in the source.
         */
        private fun grow() {
            val bigger = IntArray(xs.size * 2)
            System.arraycopy(xs, 0, bigger, 0, tail)
            System.arraycopy(ys, 0, bigger, 0, tail)
            System.arraycopy(zs, 0, bigger, 0, tail)
            System.arraycopy(ls, 0, bigger, 0, tail)
            xs = bigger; ys = bigger.copyOf(); zs = bigger.copyOf(); ls = bigger.copyOf()
        }
    }
}
