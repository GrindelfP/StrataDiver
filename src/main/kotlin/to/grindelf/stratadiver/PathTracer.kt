package to.grindelf.stratadiver

import org.joml.Vector3f
import org.joml.Vector3i
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the eye is, what it can see, and how much of the screen it can see it through.
 *
 * Plain numbers rather than a matrix: a path tracer generates rays directly and never needs
 * a projection it can ask questions of. Everything a ray needs to exist is here.
 */
class Camera(
    val origin: Vector3f,
    val front: Vector3f,
    val up: Vector3f,
    val fovDegrees: Float,
    val aspect: Float
)

/**
 * Everything about the world that is true for one frame but not the next: where the sun is,
 * what is lying on the ground, what the player is looking at.
 *
 * The Tracer reads a [GridWorld] and a camera; everything else about a frame arrives here,
 * so that "what changed since last frame" is a question about one object rather than about
 * the tracer's private state.
 */
class TraceScene(
    val camera: Camera,
    val sunDir: Vector3f,
    /** How bright the day is, 0..1. Night is dim, never a void. */
    val daylight: Float,
    val items: List<Entity> = emptyList(),
    /**
     * The Cell the player is aiming at, outlined. Null when they are aiming at nothing — and
     * unlike the old wireframe, a box with nothing behind it simply does not appear, because
     * there is no pixel it can be drawn on.
     */
    val selection: Vector3i? = null
)

/**
 * A progressive path tracer: the world drawn by tracing rays through it rather than by
 * rasterizing triangles that were built to approximate it.
 *
 * **Why this exists.** Everything the old renderer had was a list of ways a shortcut through
 * the physics of light can be wrong, and every one of them shipped. Light wrapped sideways
 * under overhangs the sun never reached. A shaft lit the whole room it pierced instead of the
 * Column it opened. Leaves shadowed only by accident, because the shadow was a consequence
 * of the fill rather than the reason for it. Each was fixed by finding a better shortcut.
 * There is no shortcut left here to get wrong: light *is* light leaving a surface, being
 * carried along a ray, and landing on something else. A shadow is a ray that did not arrive.
 * Colour bleeding onto a wall from the grass beside it is the first bounce, not a feature.
 *
 * **Progressive.** One frame traces one sample per pixel and folds it into an average, so
 * the picture sharpens and the noise falls as long as nothing moves. Move, and it starts
 * again. That is not a concession to the machine — a path tracer showing one noisy sample is
 * showing a lie with a soft filter over it. The average is the answer.
 *
 * **Pure Kotlin.** Nothing in this file touches GL, which makes it the only renderer the
 * game has had that can be checked without a window. tools/Check.kt drives it against a fake
 * world, and every claim below about what it draws is a claim somebody can run.
 */
class PathTracer(
    private val world: GridWorld,
    atlas: IntArray,
    private val atlasSize: Int = Atlas.TILES * Atlas.TILE_PX,
    threads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
) {

    companion object {
        /**
         * How far a ray travels before it is assumed to have left the world.
         *
         * A Cell the world has not produced reads as air, so without a bound a ray toward
         * the horizon would march through the entire unwritten world one Cell at a time,
         * finding nothing, forever. The bound is also where the aerial perspective takes
         * over, so the edge of the streamed world is read as haze rather than as a cliff.
         */
        const val MAX_DISTANCE = 128f

        /**
         * How many times a ray may bounce.
         *
         * Five is a judgement about a world of Lambertian surfaces: every bounce loses the
         * albedo it reflected off, so by the fourth the light arriving has already fallen
         * below a part in a thousand. Deeper bounces are still traced — they are what a room
         * lit through one doorway looks like — but past [RR_START] they are traded against
         * cost by Russian roulette.
         */
        const val MAX_DEPTH = 5

        /** Bounces before the roulette starts. See [MAX_DEPTH]. */
        const val RR_START = 3

        /**
         * The Sun's angular radius, in radians.
         *
         * The real thing is half a degree, which is a penumbra too sharp to resolve. A
         * tracer using it faithfully would need thousands of samples for a shadow edge to
         * stop looking like static, so this is deliberately wider: a soft shadow edge is the
         * entire reason to treat the Sun as an area light rather than a direction.
         */
        const val SUN_ANGLE = 0.035f

        /**
         * How much energy the Sun delivers per unit area, head on.
         *
         * The renderer works in radiance and the look is set by two numbers: this and
         * [EXPOSURE]. Everything else — the sky, the Moon, the albedo of the art — is
         * physics-shaped and should not be tuned to taste.
         */
        const val SUN_IRRADIANCE = 3.2f

        /** The Moon's version of the same, about a hundredth as strong and blue. */
        const val MOON_IRRADIANCE = 0.035f

        /** How bright a fully-emitting Block Type is, measured on its own faces. */
        const val EMISSION_RADIANCE = 6f

        /** How quickly the air hides distance. See where it is applied in [PathTracer.trace]. */
        const val FOG_DENSITY = 0.0055f

        /** The film. Radiance is not a colour until something decides it is. */
        const val EXPOSURE = 1.15f

        /** The brightest radiance the film's table is built over. See [PathTracer.rgba]. */
        const val FILM_RANGE = 8f

        /** How many steps across [FILM_RANGE]. Finer than one 255th of the output. */
        const val FILM_STEPS = 8192

        /** The reciprocal of one table step, so the lookup is a multiply. */
        const val FILM_INVERSE_STEP = FILM_STEPS / FILM_RANGE

        /**
         * The brightest indirect contribution a single bounce may make.
         *
         * A clamp, and therefore a lie — stated here rather than buried because it is one.
         * Its purpose is fireflies: a diffuse bounce that happens to land on a glowing vein
         * is a sample worth a hundred ordinary ones, and one such sample is a white dot that
         * takes a hundred frames to wash out. Everything above this is light the tracer
         * could not have resolved at any sample count this machine could afford.
         */
        const val FIREFLY_CLAMP = 12f

        /**
         * How far off a surface a ray leaves it, in Cells.
         *
         * A thousandth of a Cell is far below anything the player could see and far above
         * the precision of a float at these coordinates — which is the whole requirement.
         * It has to exist at all: see where the hit point is stepped off the surface in
         * [trace], where the reason is given.
         */
        const val SURFACE_EPS = 1e-3f

        private const val INV_PI = 1f / 3.14159265f
        private const val TWO_PI = 6.28318531f

        private const val EMISSION_SCALE = EMISSION_RADIANCE / 15f

        // Face indices, in the order the Face enum declares them.
        private const val TOP = 0
        private const val BOTTOM = 1
        private const val NORTH = 2
        private const val SOUTH = 3
        private const val WEST = 4
        private const val EAST = 5

        private val FACE_NX = floatArrayOf(0f, 0f, 0f, 0f, -1f, 1f)
        private val FACE_NY = floatArrayOf(1f, -1f, 0f, 0f, 0f, 0f)
        private val FACE_NZ = floatArrayOf(0f, 0f, -1f, 1f, 0f, 0f)

        // --- The sky's colours. The world's look, not its physics. ---
        private const val ZENITH_R = 0.22f
        private const val ZENITH_G = 0.38f
        private const val ZENITH_B = 0.85f
        private const val HORIZON_R = 0.62f
        private const val HORIZON_G = 0.72f
        private const val HORIZON_B = 0.92f
        private const val GROUND_R = 0.20f
        private const val GROUND_G = 0.18f
        private const val GROUND_B = 0.15f

        /**
         * How the sky's own light is divided between the two ways a path can find it.
         *
         * A bounce that escapes finds some sky, and the direct lighting above it already
         * found some. Counting both at full weight counts the sky twice; counting both at
         * half weight counts it once. It is exactly half because both strategies sample the
         * same cosine-weighted distribution, so the power heuristic's weights are equal
         * whatever the direction — a coincidence worth having, and one that would silently
         * stop being true the day either distribution changed.
         */
        private const val SKY_MIS_WEIGHT = 0.5f
    }

    // --- The image being accumulated into ---

    var width = 0
        private set

    var height = 0
        private set

    /** Samples averaged into the picture so far. Zero means nothing has been drawn. */
    var sampleCount = 0
        private set

    private var radiance = FloatArray(0)

    private val workers = threads.coerceAtLeast(1)
    private val pool = Executors.newFixedThreadPool(workers) { runnable ->
        Thread(runnable, "path-tracer").apply { isDaemon = true }
    }

    private val pixels = ByteArray(atlasSize * atlasSize * 4)

    init {
        // One copy of the art, read as bytes rather than as packed Ints. The tracer wants
        // four bytes in a row, and an IntArray would mean a shift and a mask per component,
        // several million times a frame.
        for (i in atlas.indices) {
            val p = atlas[i]
            pixels[i * 4] = ((p ushr 16) and 0xFF).toByte()
            pixels[i * 4 + 1] = ((p ushr 8) and 0xFF).toByte()
            pixels[i * 4 + 2] = (p and 0xFF).toByte()
            pixels[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte()
        }
    }

    /** Resizes the image, discarding whatever had been accumulated into it. */
    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        radiance = FloatArray(w * h * 3)
        sampleCount = 0
    }

    /**
     * Throws the average away, because the eye has moved or the world has changed and the
     * samples taken so far describe a scene that no longer exists.
     */
    fun reset() {
        radiance.fill(0f)
        sampleCount = 0
    }

    // --- One frame of tracing ---

    /**
     * Traces one sample for every pixel and folds it into the average.
     *
     * Parallel over horizontal bands. Each band writes only its own pixels, so the
     * accumulation needs no lock — and a lock per pixel would spend more time
     * synchronising than the ray marching.
     */
    fun sample(scene: TraceScene) {
        if (width <= 0 || height <= 0) return

        val basis = Basis(scene.camera)
        val emitters = Emitters(scene.sunDir, scene.daylight)
        val frame = sampleCount

        // A latch per frame rather than a reused one: CountDownLatch's count is final, and
        // every other design here — a reusable counter with a spin, a barrier borrowed from
        // the pool — is a way of getting the counting subtly wrong once. This is one small
        // object per frame.
        val done = CountDownLatch(workers)

        var submitted = 0
        for (band in 0 until workers) {
            val y0 = band * height / workers
            val y1 = (band + 1) * height / workers
            if (y0 >= y1) continue
            val ctx = Context(world)
            pool.submit {
                try {
                    for (y in y0 until y1) traceRow(scene, basis, emitters, ctx, y, frame)
                } finally {
                    done.countDown()
                }
            }
            submitted++
        }
        // Bands that had no rows to trace still have to release the latch.
        for (i in submitted until workers) done.countDown()
        done.await()

        sampleCount = frame + 1
    }

    private fun traceRow(
        scene: TraceScene, basis: Basis, emitters: Emitters, ctx: Context, y: Int, frame: Int
    ) {
        for (x in 0 until width) {
            // A fresh sequence per pixel, rotated per frame. Halton on its own lays the same
            // sub-pixel pattern into every pixel and the image grows a visible weave; the
            // rotation is what turns that back into noise, which averages out.
            val seed = (x * 73856093) xor (y * 19349663) xor (frame * 83492791)
            val rng = Rng(seed.toLong())

            val jx = x + fract(halton(frame + 1, 2) + rng.nextFloat())
            val jy = y + fract(halton(frame + 1, 3) + rng.nextFloat())

            val sx = (2f * jx / width - 1f) * basis.tanHalf * basis.aspect
            val sy = (1f - 2f * jy / height) * basis.tanHalf

            val dx = basis.wx + basis.ux * sx + basis.vx * sy
            val dy = basis.wy + basis.uy * sx + basis.vy * sy
            val dz = basis.wz + basis.uz * sx + basis.vz * sy
            val len = sqrt(dx * dx + dy * dy + dz * dz)

            trace(ctx, scene, basis, emitters, rng, dx / len, dy / len, dz / len)

            val i = (y * width + x) * 3
            radiance[i] += ctx.result[0]
            radiance[i + 1] += ctx.result[1]
            radiance[i + 2] += ctx.result[2]
        }
    }

    // --- The path ---

    /**
     * Follows one ray from the eye and leaves the light that arrived back at it in
     * `ctx.result`.
     *
     * The shape is the standard one: at every surface, add the light reaching it directly,
     * then throw a new ray along a direction drawn from the BSDF and carry on. What makes it
     * this game's rather than the textbook's is where the light comes from, and what happens
     * to a ray that hits nothing.
     */
    private fun trace(
        ctx: Context, scene: TraceScene, basis: Basis, emitters: Emitters,
        rng: Rng, startX: Float, startY: Float, startZ: Float
    ) {
        val eyeX = scene.camera.origin.x
        val eyeY = scene.camera.origin.y
        val eyeZ = scene.camera.origin.z

        var ox = eyeX; var oy = eyeY; var oz = eyeZ
        // The direction is carried from bounce to bounce, so it has to be a variable rather
        // than the argument it started as.
        var dx = startX; var dy = startY; var dz = startZ
        var r = 0f; var g = 0f; var b = 0f
        var thR = 1f; var thG = 1f; var thB = 1f

        /**
         * How far the eye could see before something stopped it. Only this distance gets the
         * aerial perspective blend, and that is a deliberate simplification, said here
         * because it is one: fog between two surfaces deep in the world would put weather
         * inside a sealed room.
         */
        var firstDistance = -1f
        var firstCellX = 0
        var firstCellY = 0
        var firstCellZ = 0

        var depth = 0
        while (depth <= MAX_DEPTH) {
            val hit = ctx.surface
            val worldHit = intersectWorld(ctx, ox, oy, oz, dx, dy, dz, MAX_DISTANCE, hit)
            val worldT = if (worldHit) hit.t else Float.MAX_VALUE
            val itemHit = scene.items.isNotEmpty() &&
                intersectItems(ctx, scene.items, ox, oy, oz, dx, dy, dz, worldT, hit)

            if (!worldHit && !itemHit) {
                // The Sun's disc is returned *here* and only here, so the player can look at
                // it. Every bounce below the first has the Sun already accounted for by its
                // own direct lighting, and counting it again would be counting it twice.
                val weight = if (depth == 0) 1f else SKY_MIS_WEIGHT
                val sky = skyRadiance(ctx, emitters, dx, dy, dz, withSun = depth == 0)
                r += thR * sky[0] * weight
                g += thG * sky[1] * weight
                b += thB * sky[2] * weight
                break
            }

            val t = if (itemHit) hit.t else worldT
            if (depth == 0) {
                firstDistance = t
                firstCellX = hit.x; firstCellY = hit.y; firstCellZ = hit.z
            }

            val nx = hit.nx
            val ny = hit.ny
            val nz = hit.nz

            /**
             * The point everything downstream works from: the hit, stepped off its own
             * surface along its own normal.
             *
             * Not a detail. A hit lands on a Cell boundary, and rounding decides which side
             * of it the coordinates actually fall on. A shadow ray started at the raw hit
             * point therefore has a good chance of starting *inside* the solid it is
             * standing on, and every surface in the world reports itself occluded — the Sun
             * blocked by the floor it is shining on, the sky blocked by the wall it is
             * lighting. It compiles, it runs, and it looks like a world lit only by its own
             * reflections, which is a perfectly plausible picture of a very wrong one.
             */
            val px = ox + dx * t + nx * SURFACE_EPS
            val py = oy + dy * t + ny * SURFACE_EPS
            val pz = oz + dz * t + nz * SURFACE_EPS

            // Albedo: the art pixel this ray actually landed on, taken into linear light.
            sampleArt(ctx.art, hit.tile, hit.u, hit.v)
            val aR = srgbToLinear(ctx.art[0])
            val aG = srgbToLinear(ctx.art[1])
            val aB = srgbToLinear(ctx.art[2])

            // A Block Type that emits is a light source, and this is the only way light gets
            // out of one. No stored level, no per-vertex anything: a vein is bright because
            // a ray that lands on it picks that up and carries it away.
            if (hit.emission > 0) {
                val e = hit.emission * EMISSION_SCALE
                r += thR * aR * e
                g += thG * aG * e
                b += thB * aB * e
            }

            // Direct: the Sun, the Moon and the sky, asked for rather than stumbled into.
            val direct = directLight(ctx, scene, emitters, rng, px, py, pz, nx, ny, nz, aR, aG, aB)
            r += thR * direct[0]
            g += thG * direct[1]
            b += thB * direct[2]

            // And on: a direction drawn cosine-weighted about the normal, so the sampling and
            // the BSDF agree and their cosines cancel. What is left to carry through is the
            // albedo and nothing else.
            cosineHemisphere(rng, nx, ny, nz, ctx.dir)

            dx = ctx.dir[0]; dy = ctx.dir[1]; dz = ctx.dir[2]
            thR *= aR; thG *= aG; thB *= aB

            // Already standing off the surface, for the reason given above.
            ox = px; oy = py; oz = pz
            depth++

            if (depth >= RR_START) {
                val keep = maxOf(aR, maxOf(aG, aB))
                if (keep < 1f && rng.nextFloat() > keep) break
            }
        }

        val out = ctx.result
        out[0] = r; out[1] = g; out[2] = b

        /**
         * Aerial perspective, applied once, over the distance the eye can see.
         *
         * Two things about this were wrong before they were right, and both are worth
         * writing down because both produced a plausible picture.
         *
         * The blend used to multiply the air's own contribution by the transmittance as well
         * as mixing it in, which is the (1-T) term applied twice: the world's distance haze was
         * quadratically weak, and — because the air's colour was a constant — it put a faint
         * blue glow inside sealed rooms that had no business being lit at all.
         *
         * The second is why it is gated on the Daylight the hit Cell can see. The air
         * scatters the sky's light, so there is no sky light to scatter where the sky does not
         * reach. That is one byte out of the Chunk the ray has already resolved, and it is
         * what makes a cave read as dark and a valley read as hazy rather than both reading
         * as the same amount of nothing.
         *
         * The world really does stop being written down at the edge of the streamed band,
         * and this is the difference between that reading as weather and reading as a bug.
         */
        if (firstDistance > 0f && ctx.daylightAt(firstCellX, firstCellY, firstCellZ) > 0) {
            val transmittance = exp(-FOG_DENSITY * firstDistance)
            if (transmittance < 1f) {
                val haze = skyRadiance(ctx, emitters, startX, startY, startZ, withSun = false)
                out[0] = out[0] * transmittance + haze[0] * (1f - transmittance)
                out[1] = out[1] * transmittance + haze[1] * (1f - transmittance)
                out[2] = out[2] * transmittance + haze[2] * (1f - transmittance)
            }
        }

        }

    // --- Finding the surface ---

    /**
     * Walks the world's Cells along a ray and reports what it hit.
     *
     * A DDA — Amanatides and Woo — which steps from Cell to Cell along the line rather than
     * marching in fixed steps. Both of the reasons for that are failures this game has
     * already shipped: a fixed step fine enough not to skip a Cell is a thousand steps per
     * ray, and a step that can skip one puts holes in geometry that nothing else reports.
     *
     * A Cutout is honoured rather than treated as solid: the art is sampled where the ray
     * entered, and a hole in a leaf is a hole the ray goes through. Leaves therefore cast the
     * shadow their drawn shape implies, and that shape is re-decided per ray — so a canopy
     * edge dithers into its own shadow rather than being settled once, and wrongly.
     */
    private fun intersectWorld(
        ctx: Context, ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float, maxT: Float, hit: Surface
    ): Boolean {
        var cx = floor(ox).toInt()
        var cy = floor(oy).toInt()
        var cz = floor(oz).toInt()

        val stepX = if (dx > 0f) 1 else -1
        val stepY = if (dy > 0f) 1 else -1
        val stepZ = if (dz > 0f) 1 else -1

        val tDeltaX = if (dx != 0f) abs(1f / dx) else Float.MAX_VALUE
        val tDeltaY = if (dy != 0f) abs(1f / dy) else Float.MAX_VALUE
        val tDeltaZ = if (dz != 0f) abs(1f / dz) else Float.MAX_VALUE

        var tMaxX = if (dx != 0f) (if (dx > 0f) (cx + 1 - ox) else (ox - cx)) * tDeltaX
        else Float.MAX_VALUE
        var tMaxY = if (dy != 0f) (if (dy > 0f) (cy + 1 - oy) else (oy - cy)) * tDeltaY
        else Float.MAX_VALUE
        var tMaxZ = if (dz != 0f) (if (dz > 0f) (cz + 1 - oz) else (oz - cz)) * tDeltaZ
        else Float.MAX_VALUE

        var t = 0f
        var face = -1

        while (t <= maxT) {
            val type = ctx.typeAt(cx, cy, cz)

            if (type != BlockType.AIR) {
                val entry = if (face < 0) TOP else face
                val a = (ox + dx * t - cx).coerceIn(0f, 0.999999f)
                val b = (oy + dy * t - cy).coerceIn(0f, 0.999999f)
                val c = (oz + dz * t - cz).coerceIn(0f, 0.999999f)

                if (type.opaque || opaqueAt(ctx, type.tile(Face.entries[entry]), a, b, c, entry)) {
                    hit.t = t
                    hit.x = cx; hit.y = cy; hit.z = cz
                    hit.type = type
                    hit.tile = type.tile(Face.entries[entry])
                    hit.face = entry
                    hit.nx = FACE_NX[entry]; hit.ny = FACE_NY[entry]; hit.nz = FACE_NZ[entry]
                    hit.emission = type.emission
                    faceUV(ctx.uv, entry, a, b, c)
                    hit.u = ctx.uv[0]; hit.v = ctx.uv[1]
                    return true
                }
            }

            // To the next Cell: the axis whose boundary is nearest wins. Ties go to X, then
            // Y, then Z, which is arbitrary and harmless — a ray through an exact Cell corner
            // is a measure-zero event and every answer to it is equally right.
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                t = tMaxX; tMaxX += tDeltaX; cx += stepX
                face = if (stepX > 0) WEST else EAST
            } else if (tMaxY <= tMaxZ) {
                t = tMaxY; tMaxY += tDeltaY; cy += stepY
                face = if (stepY > 0) BOTTOM else TOP
            } else {
                t = tMaxZ; tMaxZ += tDeltaZ; cz += stepZ
                face = if (stepZ > 0) NORTH else SOUTH
            }
        }
        return false
    }

    /**
     * Whether any dropped item stands between this ray and what it is looking at.
     *
     * Same job as [intersectWorld] on a smaller set of shapes, so it answers the same
     * question the same way — nearest wins, and the caller compares distances. A dropped item
     * therefore blocks light and casts a shadow, which is the whole difference between an
     * item lying in a room and a picture of one lying in a room.
     */
    private fun intersectItems(
        ctx: Context, items: List<Entity>, ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float, maxT: Float, hit: Surface
    ): Boolean {
        var found = false
        for (item in items) {
            if (intersectItem(ctx, item, ox, oy, oz, dx, dy, dz, maxT, hit)) found = true
        }
        return found
    }

    /**
     * A ray against one dropped item's box.
     *
     * The box spins about Y, so this works in the item's own frame — the ray is rotated
     * rather than the box, which is a handful of multiplies and is exact. Testing an
     * axis-aligned box around a spinning cube would only ever be approximately right, and
     * would visibly fatten every item as it turned.
     */
    private fun intersectItem(
        ctx: Context, item: Entity, ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float, maxT: Float, hit: Surface
    ): Boolean {
        val centre = item.drawCentre()
        val h = item.halfExtent()
        val angle = Math.toRadians(item.rotation.toDouble())
        val cs = cos(angle).toFloat()
        val sn = sin(angle).toFloat()

        // Into the item's frame: rotate by -angle about Y.
        val rx = ox - centre.x; val ry = oy - centre.y; val rz = oz - centre.z
        val lox = cs * rx + sn * rz
        val loy = ry
        val loz = -sn * rx + cs * rz

        val ldx = cs * dx + sn * dz
        val ldy = dy
        val ldz = -sn * dx + cs * dz

        var tEnter = 0f
        var tExit = maxT
        var enterAxis = 0
        var enterSign = 0f

        for (axis in 0 until 3) {
            val o = when (axis) { 0 -> lox; 1 -> loy; else -> loz }
            val d = when (axis) { 0 -> ldx; 1 -> ldy; else -> ldz }
            if (abs(d) < 1e-8f) {
                // Parallel to this slab: either always inside it or never.
                if (o < -h || o > h) return false
                continue
            }
            val t1 = (-h - o) / d
            val t2 = (h - o) / d
            val near = minOf(t1, t2)
            val far = maxOf(t1, t2)
            if (near > tEnter) {
                tEnter = near
                enterAxis = axis
                // -1 means the ray entered through the negative face of this axis.
                enterSign = if (t1 < t2) -1f else 1f
            }
            if (far < tExit) tExit = far
            if (tEnter > tExit) return false
        }

        if (tEnter > maxT) return false

        val lhx = lox + ldx * tEnter
        val lhy = loy + ldy * tEnter
        val lhz = loz + ldz * tEnter

        val face = when (enterAxis) {
            0 -> if (enterSign < 0f) WEST else EAST
            1 -> if (enterSign < 0f) BOTTOM else TOP
            else -> if (enterSign < 0f) NORTH else SOUTH
        }

        val lnx = if (enterAxis == 0) enterSign else 0f
        val lny = if (enterAxis == 1) enterSign else 0f
        val lnz = if (enterAxis == 2) enterSign else 0f

        hit.t = tEnter
        hit.x = floor(centre.x - h).toInt()
        hit.y = floor(centre.y - h).toInt()
        hit.z = floor(centre.z - h).toInt()
        hit.type = item.blockType ?: BlockType.AIR
        hit.tile = item.tile()
        hit.face = face
        // And back out into the world, the other way round.
        hit.nx = cs * lnx + sn * lnz
        hit.ny = lny
        hit.nz = -sn * lnx + cs * lnz
        hit.emission = item.blockType?.emission ?: 0

        // Across the face, in the item's own 0..1.
        faceUV(
            ctx.uv,
            face,
            ((lhx + h) / (2f * h)).coerceIn(0f, 0.999999f),
            ((lhy + h) / (2f * h)).coerceIn(0f, 0.999999f),
            ((lhz + h) / (2f * h)).coerceIn(0f, 0.999999f)
        )
        hit.u = ctx.uv[0]
        hit.v = ctx.uv[1]
        return true
    }

    // --- Light ---

    /**
     * The light arriving at a surface from everything that is not another bounce: the Sun,
     * the Moon, and the sky.
     *
     * Sampled rather than found. Each takes one sample per bounce and carries an unbiased
     * estimate of everything it stands for, which is why this converges in a few dozen
     * frames instead of the thousands it would take to stumble into a light source by
     * throwing rays at it.
     */
    private fun directLight(
        ctx: Context, scene: TraceScene, emitters: Emitters, rng: Rng,
        px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float,
        aR: Float, aG: Float, aB: Float
    ): FloatArray {
        val out = ctx.direct
        out[0] = 0f; out[1] = 0f; out[2] = 0f

        // --- The Sun, and the Moon, as area lights ---
        //
        // A direction is drawn from inside the disc rather than taken as a single vector, so
        // a shadow edge is a penumbra that sharpens as samples accumulate. The estimator is
        // radiance times the disc's solid angle times the cosine over a pdf of one over that
        // same solid angle, and the two cancel — which is why a product appears below and
        // not a quotient.
        for (e in 0 until emitters.count) {
            val ex = emitters.dirX[e]
            val ey = emitters.dirY[e]
            val ez = emitters.dirZ[e]
            val cosSurface = nx * ex + ny * ey + nz * ez
            if (cosSurface <= 0f) continue

            val cone = sampleCone(rng, ex, ey, ez, emitters.cosAngle[e], ctx)
            if (!occluded(ctx, scene, px, py, pz, cone, ctx.rayY, ctx.rayZ, MAX_DISTANCE)) {
                val omega = emitters.solidAngle[e] * cosSurface
                out[0] += aR * INV_PI * emitters.radianceR[e] * omega
                out[1] += aG * INV_PI * emitters.radianceG[e] * omega
                out[2] += aB * INV_PI * emitters.radianceB[e] * omega
            }
        }

        // --- The sky ---
        //
        // One cosine-weighted sample of the hemisphere, and the estimator is the sky's own
        // radiance times the albedo: the cosine and the 1/pi of the pdf cancel exactly,
        // because the direction was drawn to match the BRDF. That is why no division appears
        // here. The other half of this integral is the bounce that escapes, and MIS is what
        // stops the two halves from counting the sky twice.
        cosineHemisphere(rng, nx, ny, nz, ctx.dir)
        val wx = ctx.dir[0]; val wy = ctx.dir[1]; val wz = ctx.dir[2]

        if (!occluded(ctx, scene, px, py, pz, wx, wy, wz, MAX_DISTANCE)) {
            val sky = skyRadiance(ctx, emitters, wx, wy, wz, withSun = false)
            out[0] += aR * SKY_MIS_WEIGHT * sky[0]
            out[1] += aG * SKY_MIS_WEIGHT * sky[1]
            out[2] += aB * SKY_MIS_WEIGHT * sky[2]
        }

        // Fireflies are born in this function and nowhere else, so this is where the clamp
        // belongs.
        for (c in 0 until 3) if (out[c] > FIREFLY_CLAMP) out[c] = FIREFLY_CLAMP
        return out
    }

    /**
     * Whether anything Solid stands between two points.
     *
     * The same walk that finds a surface, so the two can never disagree about where a wall
     * is — which is the failure this whole renderer exists to avoid, and which a separate
     * faster shadow routine would reintroduce the first day it was written.
     */
    private fun occluded(
        ctx: Context, scene: TraceScene, ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float, maxT: Float
    ): Boolean {
        if (intersectWorld(ctx, ox, oy, oz, dx, dy, dz, maxT, ctx.surface)) return true
        if (scene.items.isNotEmpty() &&
            intersectItems(ctx, scene.items, ox, oy, oz, dx, dy, dz, maxT, ctx.surface)
        ) return true
        return false
    }

    /**
     * The sky, as light.
     *
     * A gradient from a pale horizon to a deeper zenith, warmed around the Sun, and darker
     * below the horizon than above. It is a model and not a measurement — the game has no
     * atmosphere — but it is a function of *direction*, which is what an ambient constant can
     * never be: two faces of the same wall, one lit and one in shadow, receive different
     * amounts of sky because they see different parts of it.
     */
    private fun skyRadiance(
        ctx: Context, emitters: Emitters, dx: Float, dy: Float, dz: Float, withSun: Boolean
    ): FloatArray {
        val out = ctx.sky
        val day = emitters.daylight

        val blend = maxOf(0f, dy).pow(0.45f)
        var r = HORIZON_R + (ZENITH_R - HORIZON_R) * blend
        var g = HORIZON_G + (ZENITH_G - HORIZON_G) * blend
        var b = HORIZON_B + (ZENITH_B - HORIZON_B) * blend

        if (dy < 0f) {
            // Below the horizon: the ground the air is sitting on. Only ever seen at the edge
            // of the streamed world, through haze.
            val down = (-dy).coerceAtMost(1f)
            r += (GROUND_R * day * down - r) * down
            g += (GROUND_G * day * down - g) * down
            b += (GROUND_B * day * down - b) * down
        }

        // Around the Sun: a tight glow and a broad one, because a real sky is bright well
        // away from the disc, and only the glow is what makes the far side of a clearing
        // warmer than the near side.
        val cosSun = dx * emitters.sunX + dy * emitters.sunY + dz * emitters.sunZ
        if (cosSun > 0f) {
            val tight = cosSun.pow(64f)
            val broad = cosSun.pow(6f)
            r += (tight * 0.9f + broad * 0.10f) * day
            g += (tight * 0.75f + broad * 0.08f) * day
            b += (tight * 0.50f + broad * 0.05f) * day
        }

        if (withSun && cosSun > emitters.cosAngle[0]) {
            r += emitters.radianceR[0]
            g += emitters.radianceG[0]
            b += emitters.radianceB[0]
        }

        out[0] = r; out[1] = g; out[2] = b
        return out
    }

    /**
     * The light the air itself scatters into a line of sight.
     *
     * No longer a function of its own: the in-scattered radiance is the sky's radiance in
     * the direction being looked in, and whether there is any at all is decided by whether
     * the sky reaches the Cell that was hit. See where it is applied in [trace].
     */

    // --- Reading the art ---

    /**
     * The colour of one point on one Tile, in 0..1.
     *
     * The art is 16x16 per Tile and a ray lands anywhere on it, so this is not a filtered
     * lookup — it is a point sample, and the filtering is done by every ray landing
     * somewhere different. A surface far enough away that a Cell is smaller than a pixel
     * converges to the Tile's average colour on its own, which is what texture filtering is
     * for, and which no amount of mipmapping does as correctly.
     *
     * The result lands in [into] rather than being returned, because the caller already owns
     * a per-thread array and a returned one would mean an allocation per ray.
     */
    private fun sampleArt(into: FloatArray, tile: TileRef, u: Float, v: Float) {
        val artX = (u * Atlas.ART_PX).toInt().coerceIn(0, Atlas.ART_PX - 1)
        // v runs from the bottom of the face, which is the bottom of the art — the same
        // orientation the mesher's UVs and the atlas upload both use.
        val artY = (v * Atlas.ART_PX).toInt().coerceIn(0, Atlas.ART_PX - 1)

        val ox = tile.tx * Atlas.TILE_PX + Atlas.PAD + artX * Atlas.UPSCALE
        val oy = tile.ty * Atlas.TILE_PX + Atlas.PAD + artY * Atlas.UPSCALE
        val index = (oy * atlasSize + ox) * 4

        into[0] = (pixels[index].toInt() and 0xFF) / 255f
        into[1] = (pixels[index + 1].toInt() and 0xFF) / 255f
        into[2] = (pixels[index + 2].toInt() and 0xFF) / 255f
        into[3] = (pixels[index + 3].toInt() and 0xFF) / 255f
    }

    /** Whether the art at this point on this Tile is solid rather than a hole. */
    private fun opaqueAt(ctx: Context, tile: TileRef, a: Float, b: Float, c: Float, face: Int): Boolean {
        faceUV(ctx.uv, face, a, b, c)
        sampleArt(ctx.art, tile, ctx.uv[0], ctx.uv[1])
        return ctx.art[3] > 0.5f
    }

    /**
     * Where on a face this point is, as the two coordinates that face varies over.
     *
     * These are the mesher's own UV conventions, in the mesher's own order, so the same Tile
     * lands the same way up on the same face as it always has. Grass still has its fringe at
     * the bottom; a grass side tile quietly turned upside down here would be an easy and
     * completely invisible mistake to make.
     */
    private fun faceUV(into: FloatArray, face: Int, a: Float, b: Float, c: Float) {
        when (face) {
            TOP, BOTTOM -> { into[0] = a; into[1] = c }
            NORTH -> { into[0] = 1f - a; into[1] = b }
            SOUTH -> { into[0] = a; into[1] = b }
            WEST -> { into[0] = c; into[1] = b }
            else -> { into[0] = 1f - c; into[1] = b }
        }
    }

    // --- Out ---

    /**
     * The film, as a table.
     *
     * Tonemapping and gamma together are `1 - exp(-v * EXPOSURE)` raised to `1/2.2`, which
     * is one exponential and one power per channel per pixel. Kotlin routes both through
     * `Math.exp` and `Math.pow` in Double precision and back, and measured at 173 ns a
     * pixel — which is 160 ms a frame at 1280x720 and 40 ms at 640x360, on the main thread,
     * *outside* the window the adaptive resolution measures as the frame's trace cost. A
     * renderer can spend a second a frame being clever about rays and still lose to its own
     * film.
     *
     * A table of the whole curve costs one multiply, one floor and one array read. It is
     * exact enough because the output is 8 bits: with [FILM_STEPS] entries over [0,
     * [FILM_RANGE]] the table step is finer than one 255th of the output range, so the
     * quantisation cannot move a byte. Anything brighter than the range saturates, which is
     * what `coerceIn` at the far end of the exact formula did anyway.
     */
    private fun filmLut(): FloatArray {
        val lut = film ?: FloatArray(FILM_STEPS + 1).also { table ->
            for (i in 0..FILM_STEPS) {
                val v = i.toFloat() / FILM_STEPS * FILM_RANGE
                table[i] = (1f - exp(-v * EXPOSURE)).pow(1f / 2.2f)
            }
            film = table
        }
        return lut
    }

    private var film: FloatArray? = null

    /**
     * The average of everything traced so far, as the bytes to hand the GPU.
     *
     * Film, then gamma. Two one-line steps and the whole difference between an image and a
     * photograph of a light meter: without the exponential the Sun's own radiance clips to
     * white across a huge area, and without the gamma the whole thing reads as a dark room
     * with the lights on.
     */
    fun rgba(): ByteArray {
        val count = sampleCount.coerceAtLeast(1)
        val out = ByteArray(width * height * 4)
        val lut = filmLut()
        for (i in 0 until width * height) {
            for (c in 0 until 3) {
                var v = radiance[i * 3 + c] / count
                if (v.isNaN()) v = 0f
                val step = (maxOf(v, 0f) * FILM_INVERSE_STEP).toInt()
                val tone = lut[if (step > FILM_STEPS) FILM_STEPS else step]
                out[i * 4 + c] = (tone * 255f + 0.5f).toInt().toByte()
            }
            out[i * 4 + 3] = 255.toByte()
        }
        return out
    }

    /** The raw average radiance at a pixel. For checks, which must not trust the film. */
    fun radianceAt(x: Int, y: Int, into: FloatArray) {
        val count = sampleCount.coerceAtLeast(1)
        val i = (y * width + x) * 3
        into[0] = radiance[i] / count
        into[1] = radiance[i + 1] / count
        into[2] = radiance[i + 2] / count
    }

    /**
     * What a single ray finds, with no light transport, no averaging and no film involved.
     *
     * This exists because the Cell walk is the one part of a renderer that has a right
     * answer rather than an approximately right one: a Cell is an axis-aligned box, so the
     * distance from any point to where a ray first enters it is available in closed form and
     * can be compared to what the walk says. Without this seam the only way to test the walk
     * is to trace a picture and look at it, which is the mistake that let a stepped ray
     * skip a Cell for a whole release.
     */
    fun probe(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float
    ): RayHit {
        val ctx = Context(world)
        val surface = ctx.surface
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        return if (intersectWorld(ctx, ox, oy, oz, dx / len, dy / len, dz / len, MAX_DISTANCE, surface)) {
            RayHit(true, surface.t, surface.x, surface.y, surface.z, surface.face, surface.type)
        } else {
            RayHit(false, Float.MAX_VALUE, 0, 0, 0, -1, BlockType.AIR)
        }
    }

    // --- Scratch ---
    //
    // One of each per tracing thread, never shared. A renderer that hands its working memory
    // out to a field on the tracer is one refactor away from two threads writing the same
    // three floats, and the symptom is a picture that is mostly right and occasionally wrong
    // in a way nobody can point at.

    /**
     * Everything one tracing thread owns.
     *
     * A ray allocates nothing: the vectors it bounces between, the surface it last hit and
     * the arrays its results come back in are all here, and are overwritten. Chunks are held
     * between steps too — a ray crosses a Chunk boundary once in sixteen Cells and then does
     * sixteen array reads, which is the difference between a map lookup per Cell and one per
     * ray.
     */
    private class Context(private val world: GridWorld) {
        var chunkX = 0
        var chunkY = Int.MIN_VALUE
        var chunkZ = 0
        var chunk: Chunk? = null

        val surface = Surface()
        val direct = FloatArray(3)
        val sky = FloatArray(3)
        val art = FloatArray(4)
        val dir = FloatArray(3)
        val uv = FloatArray(2)
        val result = FloatArray(3)
        var rayY = 0f
        var rayZ = 0f

        /** The Block Type in a Cell. Air where the world has not been written. */
        fun typeAt(x: Int, y: Int, z: Int): BlockType {
            // Below the floor of the world there is bedrock and then nothing. Treating the
            // void as air would let a ray that somehow got under the world see straight
            // through the planet.
            if (y < 0) return BlockType.BEDROCK
            if (y >= Chunk.SIZE * Chunk.HEIGHT_LAYERS) return BlockType.AIR

            val cx = Math.floorDiv(x, Chunk.SIZE)
            val cy = Math.floorDiv(y, Chunk.SIZE)
            val cz = Math.floorDiv(z, Chunk.SIZE)
            if (cx != chunkX || cy != chunkY || cz != chunkZ) {
                chunkX = cx; chunkY = cy; chunkZ = cz
                chunk = world.existingChunk(cx, cy, cz)
            }
            val c = chunk ?: return BlockType.AIR
            return BlockType.getById(
                c.getBlock(x - cx * Chunk.SIZE, y - cy * Chunk.SIZE, z - cz * Chunk.SIZE)
            )
        }

        /**
         * How much Daylight reaches a Cell, 0..[Light.MAX], or 0 where the world has not
         * been written.
         *
         * Read out of the same Chunk the ray already has in hand, so it costs an array index
         * rather than a lookup. The tracer asks one question of it — is the sky above this
         * place at all — and that is the difference between a cave that reads as dark and a
         * cave that reads as blue.
         */
        fun daylightAt(x: Int, y: Int, z: Int): Int {
            if (y < 0 || y >= Chunk.SIZE * Chunk.HEIGHT_LAYERS) return 0
            val cx = Math.floorDiv(x, Chunk.SIZE)
            val cy = Math.floorDiv(y, Chunk.SIZE)
            val cz = Math.floorDiv(z, Chunk.SIZE)
            if (cx != chunkX || cy != chunkY || cz != chunkZ) {
                chunkX = cx; chunkY = cy; chunkZ = cz
                chunk = world.existingChunk(cx, cy, cz)
            }
            val c = chunk ?: return 0
            return Light.day(
                c.packedLightAt(x - cx * Chunk.SIZE, y - cy * Chunk.SIZE, z - cz * Chunk.SIZE)
            )
        }
    }

    /** What a ray found. One of these per thread, rewritten on every hit. */
    private class Surface {
        var t = 0f
        var x = 0; var y = 0; var z = 0
        var type = BlockType.AIR
        var tile: TileRef = Atlas.LINE
        var face = 0
        var nx = 0f; var ny = 1f; var nz = 0f
        var u = 0f; var v = 0f
        var emission = 0
    }

    /** The camera, reduced to the three numbers a ray needs and the lens in front of them. */
    private class Basis(camera: Camera) {
        val wx: Float; val wy: Float; val wz: Float
        val ux: Float; val uy: Float; val uz: Float
        val vx: Float; val vy: Float; val vz: Float
        val tanHalf: Float
        val aspect: Float

        init {
            val len = sqrt(
                camera.front.x * camera.front.x +
                    camera.front.y * camera.front.y +
                    camera.front.z * camera.front.z
            )
            wx = camera.front.x / len
            wy = camera.front.y / len
            wz = camera.front.z / len

            // Right = forward x up. Which is degenerate when the camera looks straight up or
            // straight down, because then forward and up are parallel and the cross product
            // is zero — and dividing zero by its own length is how a renderer produces a
            // NaN in every pixel of a frame and no error anywhere.
            //
            // The player cannot quite look straight down; the pitch clamps at 89 degrees,
            // which leaves the cross product 1.7% of full length. Anything asked to look
            // straight down — a check, a future overhead camera, a minimap fly-over — gets
            // zero instead, so the degenerate case is handled here rather than discovered.
            var upX = camera.up.x; var upY = camera.up.y; var upZ = camera.up.z
            var rx = wy * upZ - wz * upY
            var ry = wz * upX - wx * upZ
            var rz = wx * upY - wy * upX
            var rlen = sqrt(rx * rx + ry * ry + rz * rz)

            if (rlen < 1e-4f) {
                // Any axis that is not parallel to the view will do.
                upX = 0f; upY = 0f; upZ = 1f
                rx = wy * upZ - wz * upY
                ry = wz * upX - wx * upZ
                rz = wx * upY - wy * upX
                rlen = sqrt(rx * rx + ry * ry + rz * rz)
                if (rlen < 1e-6f) {
                    upX = 0f; upY = 1f; upZ = 0f
                    rx = wy * upZ - wz * upY
                    ry = wz * upX - wx * upZ
                    rz = wx * upY - wy * upX
                    rlen = sqrt(rx * rx + ry * ry + rz * rz)
                }
            }

            ux = rx / rlen; uy = ry / rlen; uz = rz / rlen
            // Up = right x forward, recomputed from the substituted up when there was one.
            vx = uy * wz - uz * wy
            vy = uz * wx - ux * wz
            vz = ux * wy - uy * wx

            tanHalf = tan(Math.toRadians(camera.fovDegrees / 2.0)).toFloat()
            aspect = camera.aspect
        }
    }

    /** The Sun and the Moon, with the numbers that make sampling them worth doing. */
    private class Emitters(sunDir: Vector3f, daylight: Float) {
        val daylight = daylight
        val sunX: Float; val sunY: Float; val sunZ: Float
        val count = 2

        val dirX = FloatArray(count)
        val dirY = FloatArray(count)
        val dirZ = FloatArray(count)
        val cosAngle = FloatArray(count)
        val solidAngle = FloatArray(count)
        val radianceR = FloatArray(count)
        val radianceG = FloatArray(count)
        val radianceB = FloatArray(count)

        init {
            val len = sqrt(sunDir.x * sunDir.x + sunDir.y * sunDir.y + sunDir.z * sunDir.z)
            sunX = sunDir.x / len
            sunY = sunDir.y / len
            sunZ = sunDir.z / len

            val cosSun = cos(SUN_ANGLE.toDouble()).toFloat()
            val omega = TWO_PI * (1f - cosSun)

            dirX[0] = sunX; dirY[0] = sunY; dirZ[0] = sunZ
            cosAngle[0] = cosSun
            solidAngle[0] = omega
            radianceR[0] = SUN_IRRADIANCE * daylight / omega
            radianceG[0] = radianceR[0] * 0.97f
            radianceB[0] = radianceR[0] * 0.90f

            // The Moon is where the Sun is not, and counts only once it is itself above the
            // horizon. The clamp is on purpose: without it a Sun below the horizon gives a
            // negative irradiance, and negative light is the sort of thing that shows up
            // three functions away as a black sky.
            val moonUp = (-sunY).coerceIn(0f, 1f)
            if (moonUp > 0f) {
                dirX[1] = -sunX; dirY[1] = -sunY; dirZ[1] = -sunZ
            } else {
                dirX[1] = 0f; dirY[1] = 1f; dirZ[1] = 0f
            }
            cosAngle[1] = cosSun
            solidAngle[1] = omega
            val moon = MOON_IRRADIANCE * moonUp / omega
            radianceR[1] = moon
            radianceG[1] = moon * 1.05f
            radianceB[1] = moon * 1.30f
        }
    }

    // --- Small maths ---

    /**
     * A direction drawn cosine-weighted about a normal.
     *
     * Sampled by picking a uniform disc and projecting it onto the hemisphere, rather than
     * by picking an angle and a cosine. Both are correct; this one is uniform over the *area*
     * of the hemisphere, which is the thing being sampled.
     */
    private fun cosineHemisphere(rng: Rng, nx: Float, ny: Float, nz: Float, into: FloatArray) {
        val u1 = rng.nextFloat()
        val phi = TWO_PI * rng.nextFloat()
        val r = sqrt(u1)
        val localX = r * cos(phi)
        val localY = r * sin(phi)
        val localZ = sqrt(maxOf(0f, 1f - u1))

        // An orthonormal pair perpendicular to the normal, built from whichever axis is
        // least aligned with it so the cross product is never degenerate.
        var t1x: Float; var t1y: Float; var t1z: Float
        if (abs(nx) < 0.9f) { t1x = 0f; t1y = -nz; t1z = ny }
        else { t1x = -nz; t1y = nx; t1z = 0f }
        val inv = 1f / sqrt(t1x * t1x + t1y * t1y + t1z * t1z)
        t1x *= inv; t1y *= inv; t1z *= inv
        val t2x = ny * t1z - nz * t1y
        val t2y = nz * t1x - nx * t1z
        val t2z = nx * t1y - ny * t1x

        into[0] = t1x * localX + t2x * localY + nx * localZ
        into[1] = t1y * localX + t2y * localY + ny * localZ
        into[2] = t1z * localX + t2z * localY + nz * localZ
    }

    /** A random direction inside the disc of half-angle [cosAngle] around a direction. */
    private fun sampleCone(
        rng: Rng, x: Float, y: Float, z: Float, cosAngle: Float, ctx: Context
    ): Float {
        val cosTheta = 1f - rng.nextFloat() * (1f - cosAngle)
        val sinTheta = sqrt(maxOf(0f, 1f - cosTheta * cosTheta))
        val phi = TWO_PI * rng.nextFloat()
        val localX = sinTheta * cos(phi)
        val localY = sinTheta * sin(phi)

        // A perpendicular to the axis, crossed with whichever world axis is least aligned
        // with it. The least-aligned one is chosen because crossing with an aligned one
        // gives a zero vector, and dividing that by its own length is how a renderer that
        // works at noon produces a black screen.
        var tx: Float; var ty: Float; var tz: Float
        if (abs(x) < 0.9f) { tx = 0f; ty = z; tz = -y }
        else { tx = -z; ty = 0f; tz = x }
        val inv = 1f / sqrt(tx * tx + ty * ty + tz * tz)
        tx *= inv; ty *= inv; tz *= inv

        val bx = y * tz - z * ty
        val by = z * tx - x * tz
        val bz = x * ty - y * tx

        ctx.rayY = ty * localX + by * localY + y * cosTheta
        ctx.rayZ = tz * localX + bz * localY + z * cosTheta
        return tx * localX + bx * localY + x * cosTheta
    }

    /** The fractional part. */
    private fun fract(v: Float) = v - floor(v)

    /** Van der Corput in base 2 or 3: the cheapest low-discrepancy sequence there is. */
    private fun halton(index: Int, base: Int): Float {
        var f = 1f
        var result = 0f
        var i = index
        while (i > 0) {
            f /= base
            result += f * (i % base)
            i /= base
        }
        return result
    }

    private fun srgbToLinear(c: Float): Float = c.pow(2.2f)
}

/** What [PathTracer.probe] found. A report, not a picture. */
class RayHit(
    val hit: Boolean,
    val t: Float,
    val x: Int,
    val y: Int,
    val z: Int,
    val face: Int,
    val type: BlockType
)

/** xorshift64. Cheap, no state to share, and reproducible from a seed. */
private class Rng(seed: Long) {
    private var s: Long = if (seed == 0L) -0x61c8864680b583ebL else seed

    fun nextLong(): Long {
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        return s
    }

    fun nextFloat(): Float = ((nextLong() ushr 11).toDouble() / 9007199254740992.0).toFloat()
}