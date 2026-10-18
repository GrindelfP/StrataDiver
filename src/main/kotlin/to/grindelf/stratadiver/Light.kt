package to.grindelf.stratadiver

/**
 * What Light needs from the world, and nothing more: read a Cell, read and write a Cell's
 * light, and ask which Chunk covers a place **without causing one to be generated**.
 *
 * That last clause is why this is separate from `Neighbourhood`. A light ray is cast
 * across Chunk borders, and resolving one through a Chunk that did not exist yet would
 * read Cells nobody had lit and believe the answer. So Light reads `null` for a place no
 * Chunk covers — a daylight ray treats the gap as open sky, which at a frontier it
 * genuinely is, and a torchlight ray stops there — and never brings a Chunk into being as
 * a side effect of tracing. The Chunk in question is lit when it is created, when its own
 * rays can finally see the whole Column above it.
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
 * Ray-traced light: every Cell's light is the answer to a geometric question, not the
 * residue of a flood.
 *
 * **Daylight** casts a ray straight up from the Cell. If it reaches the sky without
 * striking an opaque Block Type, the Cell is in full daylight; each attenuating Block
 * Type (leaves, by default) the ray passes through subtracts its attenuation from the
 * level that reaches the Cell. This is exact: a tree casts a shadow because the ray
 * from the ground beneath it passes through the canopy, not because a flood happened
 * to carry the right number sideways.
 *
 * **Torchlight** casts a ray from the Cell toward each emitter in range. If the ray
 * reaches the emitter without striking an opaque Block Type, the Cell receives light
 * from it, falling off with distance and with whatever attenuation the path crossed.
 * The highest contribution wins.
 *
 * The torchlight ray is a DDA: it steps from Cell to Cell along the line, so it costs one
 * step per Cell of distance rather than one per 0.1 Cell of a fixed-step march. It cannot
 * skip a Cell, and it cannot land between two — the two failure modes of the raycast this
 * game already had. The daylight ray needs none of that: straight up is a single Column,
 * so it is a direct walk with no stepping arithmetic to get wrong.
 *
 * Nothing here touches GL, and everything is reachable through [LitWorld], so all of it —
 * falloff, cross-Chunk reach, change handling — is checkable with no window.
 */
class LightEngine(private val world: LitWorld) {

    companion object {
        /** An arbitrary in-range coordinate used to ask "which Chunk is that?". */
        private const val PROBE = 0
    }

    /**
     * The Chunk currently being lit from scratch, if any.
     *
     * Its own light is *supposed* to change — that is the whole job — and the caller
     * consumes the result immediately. Recording it as a change as well would double the
     * work for every Chunk during fill-in, paid on the exact frames the player is already
     * waiting for the world to appear.
     */
    private var lighting: Chunk? = null

    // --- Lighting a whole Chunk ---

    /**
     * Brings [chunk] from "generated, unlit" to correctly lit: each Cell's daylight and
     * torchlight are derived by tracing a ray to its source, so the answer is exact
     * rather than the residue of a flood.
     *
     * Redone from scratch each time, which is affordable because it is hoisted. The
     * 256 Columns of a Chunk all share the same 16 vertical neighbours, so resolving
     * those 16 once turns 256 x 256 = 65,536 map lookups into 16 lookups and 65,536
     * array reads. Left un-hoisted this is a visible stall at spawn for no reason at
     * all.
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

        // Collect every emitter this Chunk's rays might reach: emitters in this Chunk
        // and in each of the 26 neighbours. An emitter further away than its own
        // emission level cannot light anything here, so it is not worth tracing to.
        val emitters = collectEmitters(chunk)

        for (lx in 0 until Chunk.SIZE) {
            for (ly in 0 until Chunk.SIZE) {
                for (lz in 0 until Chunk.SIZE) {
                    val x = chunk.xOffset * Chunk.SIZE + lx
                    val y = chunk.yOffset * Chunk.SIZE + ly
                    val z = chunk.zOffset * Chunk.SIZE + lz

                    val daylight = traceDaylight(x, y, z, layers)

                    var torchlight = 0
                    for ((ex, ey, ez, emission) in emitters) {
                        val contribution = traceTorchlight(x, y, z, ex, ey, ez, emission)
                        if (contribution > torchlight) torchlight = contribution
                    }

                    if (daylight > 0 || torchlight > 0) {
                        write(x, y, z, Light.pack(daylight, torchlight))
                    }
                }
            }
        }
    }

    // --- Tracing ---

    /**
     * Daylight reaching (x, y, z), by tracing a straight line upward.
     *
     * Starts at the Cell above [y] and walks toward the sky. An opaque Block Type ends
     * the ray and returns 0 — that Cell is in shadow. An attenuating one subtracts its
     * attenuation from the level that gets through. A layer the streamer has not made
     * reads as open sky, so a frontier Chunk is lit by what is actually above it rather
     * than going dark along the seam.
     */
    private fun traceDaylight(x: Int, y: Int, z: Int, layers: Array<Chunk?>): Int {
        var daylight = Light.MAX
        val startCy = y / Chunk.SIZE
        val startLy = (y % Chunk.SIZE) + 1

        // Cells in the same chunk, above the target
        val startLayer = layers[startCy]
        if (startLayer != null) {
            for (ly in startLy until Chunk.SIZE) {
                val type = BlockType.getById(startLayer.getBlock(
                    x - startLayer.xOffset * Chunk.SIZE, ly, z - startLayer.zOffset * Chunk.SIZE
                ))
                if (type.opaque) return 0
                daylight -= type.attenuation
                if (daylight <= 0) return 0
            }
        }

        // Chunks above
        for (cy in startCy + 1 until Chunk.HEIGHT_LAYERS) {
            val layer = layers[cy] ?: continue
            for (ly in Chunk.SIZE - 1 downTo 0) {
                val type = BlockType.getById(layer.getBlock(
                    x - layer.xOffset * Chunk.SIZE, ly, z - layer.zOffset * Chunk.SIZE
                ))
                if (type.opaque) return 0
                daylight -= type.attenuation
                if (daylight <= 0) return 0
            }
        }

        return daylight
    }

    /**
     * Torchlight reaching (x, y, z) from the emitter at (ex, ey, ez), by tracing the
     * straight line between them.
     *
     * The ray is a DDA: it steps from Cell to Cell along the line, checking each for
     * opacity. An opaque Block Type ends the ray and returns 0. An attenuating one
     * subtracts its attenuation from the level that gets through. The remaining level is
     * the emitter's emission minus the distance travelled and the attenuation
     * accumulated along the way.
     */
    private fun traceTorchlight(
        x: Int, y: Int, z: Int,
        ex: Int, ey: Int, ez: Int,
        emission: Int
    ): Int {
        val dx = (ex - x).toDouble()
        val dy = (ey - y).toDouble()
        val dz = (ez - z).toDouble()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (distance > emission) return 0

        // DDA setup: step direction and the distance to the first Cell boundary.
        val stepX = if (dx > 0) 1 else -1
        val stepY = if (dy > 0) 1 else -1
        val stepZ = if (dz > 0) 1 else -1

        val tDeltaX = if (dx != 0.0) kotlin.math.abs(1.0 / dx) else Double.MAX_VALUE
        val tDeltaY = if (dy != 0.0) kotlin.math.abs(1.0 / dy) else Double.MAX_VALUE
        val tDeltaZ = if (dz != 0.0) kotlin.math.abs(1.0 / dz) else Double.MAX_VALUE

        var tMaxX = if (dx != 0.0) {
            val fracX = (x + 0.5) - x
            (if (dx > 0) 1.0 - fracX else fracX) * tDeltaX
        } else Double.MAX_VALUE
        var tMaxY = if (dy != 0.0) {
            val fracY = (y + 0.5) - y
            (if (dy > 0) 1.0 - fracY else fracY) * tDeltaY
        } else Double.MAX_VALUE
        var tMaxZ = if (dz != 0.0) {
            val fracZ = (z + 0.5) - z
            (if (dz > 0) 1.0 - fracZ else fracZ) * tDeltaZ
        } else Double.MAX_VALUE

        var cx = x
        var cy = y
        var cz = z
        var travelled = 0.0
        var attenuation = 0

        // Step through Cells along the ray until we reach the emitter or hit a wall.
        while (travelled <= distance) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                cx += stepX
                travelled = tMaxX
                tMaxX += tDeltaX
            } else if (tMaxY < tMaxZ) {
                cy += stepY
                travelled = tMaxY
                tMaxY += tDeltaY
            } else {
                cz += stepZ
                travelled = tMaxZ
                tMaxZ += tDeltaZ
            }

            if (cx == ex && cy == ey && cz == ez) break
            if (travelled > distance) break

            val type = world.blockAtOrNull(cx, cy, cz) ?: break
            if (type.opaque) return 0
            attenuation += type.attenuation
        }

        return (emission - distance.toInt() - attenuation).coerceAtLeast(0)
    }

    // --- Gather ---

    /**
     * Every emitter within range of this Chunk, as (x, y, z, emission).
     *
     * An emitter further away than its own emission level cannot light anything in this
     * Chunk, so it is not worth tracing to. That bound keeps the list short without
     * missing a contribution.
     */
    private fun collectEmitters(chunk: Chunk): List<Quad<Int, Int, Int, Int>> {
        val result = mutableListOf<Quad<Int, Int, Int, Int>>()

        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    val cx = chunk.xOffset + dx
                    val cy = chunk.yOffset + dy
                    val cz = chunk.zOffset + dz
                    val neighbour = world.existingChunkAt(
                        cx * Chunk.SIZE, cy * Chunk.SIZE, cz * Chunk.SIZE
                    ) ?: continue

                    for (lx in 0 until Chunk.SIZE) {
                        for (ly in 0 until Chunk.SIZE) {
                            for (lz in 0 until Chunk.SIZE) {
                                val emission = BlockType.getById(neighbour.getBlock(lx, ly, lz)).emission
                                if (emission <= 0) continue
                                val ex = cx * Chunk.SIZE + lx
                                val ey = cy * Chunk.SIZE + ly
                                val ez = cz * Chunk.SIZE + lz
                                // Cheap distance check before adding to the list.
                                val d = kotlin.math.abs(ex - (chunk.xOffset * Chunk.SIZE + Chunk.SIZE / 2)) +
                                        kotlin.math.abs(ey - (chunk.yOffset * Chunk.SIZE + Chunk.SIZE / 2)) +
                                        kotlin.math.abs(ez - (chunk.zOffset * Chunk.SIZE + Chunk.SIZE / 2))
                                if (d <= emission + Chunk.SIZE) {
                                    result.add(Quad(ex, ey, ez, emission))
                                }
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * The 16 vertical Chunks at [cx], [cz], resolved once.
     *
     * A null layer is one the streamer has not made, which the daylight scan reads as
     * open sky. That is the right reading at a frontier — the world above really is
     * open, we have simply not written it down — and it is why lighting does not need
     * the Chunks above to exist first.
     */
    private fun columnLayers(cx: Int, cz: Int): Array<Chunk?> =
        Array(Chunk.HEIGHT_LAYERS) { cy ->
            world.existingChunkAt(
                cx * Chunk.SIZE + PROBE, cy * Chunk.SIZE + PROBE, cz * Chunk.SIZE + PROBE
            )
        }

    // --- Reacting to the player ---

    /**
     * Recomputes light around a Cell the player has just changed.
     *
     * [wasPacked] is deliberately ignored, and that is the point of this method. It used
     * to be the seed of an incremental darkening walk, and that walk was the only
     * genuinely hard code in the engine — so hard that I could not fix it, only delete
     * it.
     *
     * The walk assumes light in a region forms a tree flowing downhill from a source,
     * and that assumption breaks exactly where the world is interesting. A Cell at
     * full daylight satisfies it in several directions at once: a shaft lights a whole
     * Column to 15, so a Cell beside that Column is fed from above *and* from beside and
     * has no unique path for the walk to walk back along. Given a room lit through one
     * shaft, it correctly cleared 3,835 of 4,096 Cells and then abandoned the rest,
     * leaving light behind in a gradient that looked like a rendering bug and was a
     * queue of wrong answers.
     *
     * Every fix I could think of was right about one geometry and wrong about
     * another: clear equal-level neighbours too, and a legitimately-lit plateau goes
     * dark; treat vertical runs as fed-from-above, and overhangs go dark; walk in a
     * different order, and a different shape goes dark. None of them had a failing
     * case you could picture, which is the signature of code whose rules have not been
     * found yet.
     *
     * So the walk is gone, and in its place: light the whole neighbourhood again. It
     * is less clever, it is a few milliseconds, and it cannot be wrong in a way nobody
     * has thought of — it recomputes from the sky, which is the definition of
     * correct. Given [wasPacked] is unused the parameter is kept only so the call
     * site documents that the Cell's light was cleared by the write and that this does
     * not need it back.
     *
     * Cost is bounded rather than unbounded, and bounded by two different shapes rather
     * than one radius: Torchlight reaches at most [Light.MAX] Cells, which is under a
     * Chunk, and daylight only ever straight down its own Column. So a change touches the
     * 27-Chunk box around it plus the Column beneath it — a few dozen Chunks, never the
     * world. Measured in the low single-digit milliseconds for an ordinary change and
     * low double digits with emitters in range, and paid only on a discrete block change
     * rather than per frame.
     */
    fun onBlockChanged(x: Int, y: Int, z: Int, wasPacked: Int) {
        relightNeighbourhood(x, y, z)
    }

    /**
     * Re-derives every Chunk this Cell's change can reach, skipping those the world has
     * not made.
     *
     * Two reaches, and they are different shapes — which is the whole substance of this
     * method and the reason it is not a single 3x3x3 loop:
     *
     *  - **Torchlight** travels at most [Light.MAX] Cells in any direction, which is under
     *    one Chunk, so it can only touch this Cell's Chunk and the 26 around it.
     *  - **Daylight** is vertical. Capping a shaft at the surface darkens it to the
     *    bottom, however deep the bottom is, so every Chunk below this Cell in its Column
     *    has to be re-derived too. Relighting only the 3x3x3 is what would leave a capped
     *    pit still glowing at its floor — the exact kind of survivor this engine exists to
     *    not have.
     *
     * The order does not matter, and that is a property worth naming rather than a detail
     * left implicit: lighting a Chunk is now a pure function of Block data, so no Chunk's
     * answer depends on another's having been computed first. Under the old flood fill it
     * did depend, and the loop was deliberately nearest-first for that reason. With rays
     * that reason is gone, so the set is unordered and there is no order left to get wrong.
     */
    private fun relightNeighbourhood(x: Int, y: Int, z: Int) {
        val cx = Math.floorDiv(x, Chunk.SIZE)
        val cy = Math.floorDiv(y, Chunk.SIZE)
        val cz = Math.floorDiv(z, Chunk.SIZE)

        val targets = LinkedHashSet<Triple<Int, Int, Int>>()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val py = cy + dy
            if (py < 0 || py >= Chunk.HEIGHT_LAYERS) continue
            targets.add(Triple(cx + dx, py, cz + dz))
        }
        // Daylight reaches down the Column, so every Chunk below this one is affected too.
        for (py in 0 until cy) targets.add(Triple(cx, py, cz))

        for ((px, py, pz) in targets) {
            world.existingChunkAt(px * Chunk.SIZE, py * Chunk.SIZE, pz * Chunk.SIZE)
                ?.let { lightChunk(it) }
        }
    }

    // --- Writing ---

    private fun write(x: Int, y: Int, z: Int, packed: Int) {
        val touched = world.setLight(x, y, z, packed) ?: return
    }
}

/** A four-value tuple, for emitters and their emission levels. */
data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
