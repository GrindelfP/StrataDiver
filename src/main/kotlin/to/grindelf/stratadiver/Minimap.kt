package to.grindelf.stratadiver

import org.joml.Vector2f
import org.joml.Vector3f
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * How a Block Type reads when seen from directly above.
 *
 * Lives here rather than on `BlockType` because it is a rendering decision, and
 * `BlockType` is about identity. Grass and Leaves are the pair that made this necessary:
 * both are green, and at 2.25 screen pixels per Cell two similar greens turn a forest
 * into one unreadable mass.
 */
private val MAP_COLOURS: Map<BlockType, Int> = mapOf(
    BlockType.GRASS to 0x7EB048,   // yellow-green: the commonest thing on a map
    BlockType.LEAVES to 0x2C5C3E,  // darker, bluer green: canopy, not ground
    BlockType.DIRT to 0x866043,
    BlockType.STONE to 0x848486,
    BlockType.BEDROCK to 0x3A3A3E,
    BlockType.WOOD to 0x7A5C38
)

/** A Column with nothing in it at all: genuinely empty, and drawn as such. */
private const val EMPTY = 0x00000000

/**
 * A Column the world has not produced. Deliberately a neutral near-black rather than the
 * sky's colour: empty and Unknown are opposite facts — one is safe to build on, the other
 * is a hole in what we know — and a map that draws them alike is confidently wrong.
 */
private const val UNKNOWN = 0x181820

/**
 * The Minimap's model: a grid of Surfaces centred on the player, one entry per Cell.
 *
 * Pure data and pure arithmetic — no GL — so what the map *says* can be asserted without
 * a window, and only the drawing of it needs one.
 */
class Minimap(
    private val streamer: ChunkStreamer,

    /** Cells across the whole map, and therefore across its diameter. */
    val cells: Int = 64,

    /** How far out the map reaches, in Cells, from the centre. */
    val radius: Int = cells / 2
) {

    /** One RGBA entry per Cell, row-major, north row first. */
    val pixels = IntArray(cells * cells)

    /** Whether the map was rebuilt since it was last drawn. */
    private var dirty = true

    /**
     * How long a Cell must have changed before the map is rebuilt again, if nothing was
     * built or broken. A full rebuild is cheap, but the map changes slowly enough that
     * redoing it several times a second would be work for an identical picture.
     */
    private var lastRebuildNanos = 0L

    private var lastCentreX = Int.MIN_VALUE
    private var lastCentreZ = Int.MIN_VALUE

    /** The player's heading in map space. Owned here, read by the shader every frame. */
    private val heading = Vector2f(0f, -1f)
    private var headingValid = false

    val refreshIntervalNanos = 250_000_000L

    /** Call whenever a Cell changes, so the map cannot lag behind a build. */
    fun invalidate() {
        dirty = true
    }

    /**
     * Where the player is facing, as a direction in map space. Always unit length.
     *
     * Pitch is dropped, and it must be. The shader draws the facing tick by measuring a
     * fragment's perpendicular distance from this vector and its distance along it, and
     * both of those tests are only meaningful for a *unit* vector — hand them one of
     * length L and the tick comes out L times too wide and L times too long.
     *
     * That is not a hypothetical. `Player.front` is a normalised 3D direction, so its
     * horizontal part is `cos(pitch)`: hand that over raw and the marker is
     * `1/cos(pitch)` times its intended size, which is fine until you look up, and then
     * at 89° — 0.0175 — the permitted width is 0.69 against a disc of radius 0.5. The
     * tick swallowed the map. A plan view has no vertical component to show anyway, so
     * dropping pitch is both the fix and the correct reading of a heading.
     */
    fun heading(front: Vector3f): Vector2f {
        val x = front.x
        val z = front.z
        val length = sqrt(x * x + z * z)

        if (length > DEGENERATE_HEADING) {
            heading.set(x / length, z / length)
        } else if (!headingValid) {
            // Looking straight up or down before ever having looked horizontally. The map
            // genuinely does not know which way is which, so it says north rather than
            // reporting a direction made of zeros.
            heading.set(0f, -1f)
            headingValid = true
        }
        // Otherwise: keep the last heading that had a horizontal component. Snapping to
        // north whenever the player glances at the sky would make the marker spin.
        return heading
    }

    /**
     * Brings the map up to date, if it is due.
     *
     * Two clocks, deliberately. A change to the world marks it dirty and it is rebuilt on
     * the very next frame — building something must never wait on a timer. Ordinary
     * drift, which is only ever the player's own movement, waits for the interval.
     */
    fun refreshIfDue(player: Vector3f, nowNanos: Long): Boolean {
        val centreX = Math.floor(player.x.toDouble()).toInt()
        val centreZ = Math.floor(player.z.toDouble()).toInt()

        if (centreX != lastCentreX || centreZ != lastCentreZ) dirty = true

        if (!dirty && nowNanos - lastRebuildNanos < refreshIntervalNanos) return false

        rebuild(centreX, centreZ, nowNanos)
        return true
    }

    private fun rebuild(centreX: Int, centreZ: Int, nowNanos: Long) {
        dirty = false
        lastRebuildNanos = nowNanos
        lastCentreX = centreX
        lastCentreZ = centreZ

        val minX = centreX - radius
        val minZ = centreZ - radius

        for (row in 0 until cells) {
            for (column in 0 until cells) {
                // Row 0 is the NORTH edge.
                //
                // GL reads v=0 as the first uploaded row, and the map quad's v=0 is its
                // top vertex, so row 0 has to be the far side from +Z for north to end up
                // at the top. Getting this backwards does not fail loudly — it draws a
                // perfectly plausible map, mirrored north-south, with the player marker
                // still correctly centred, which is the hardest kind of map bug to notice.
                val z = minZ + row
                val x = minX + column

                val surface = streamer.surfaceAt(x, z)
                pixels[row * cells + column] = if (surface != null) {
                    // An unmapped Block Type falls back to stone rather than to Unknown:
                    // we know what is there, we just have no opinion about how it looks
                    // from above, and Unknown would claim we do not know at all.
                    MAP_COLOURS[surface.type] ?: MAP_COLOURS[BlockType.STONE]!!
                } else {
                    UNKNOWN
                }
            }
        }
    }

    /**
     * The RGBA bytes GL wants: interleaved R,G,B,A, top row first.
     *
     * A `ByteBuffer` rather than a `ByteArray` because that is what LWJGL's
     * `glTexImage2D` overload set accepts — there is no `byte[]` overload, so handing it
     * an array would silently mean uploading one channel per texel and a map that came out
     * as coloured static.
     */
    fun toRgbaBytes(): ByteBuffer {
        val out = ByteBuffer.allocateDirect(pixels.size * 4)
        for (pixel in pixels) {
            out.put(((pixel ushr 16) and 0xFF).toByte())
            out.put(((pixel ushr 8) and 0xFF).toByte())
            out.put((pixel and 0xFF).toByte())
            out.put((pixel ushr 24 and 0xFF).toByte())
        }
        out.flip()
        return out
    }

    companion object {
        /**
         * Below this horizontal length a look direction has no usable heading in a plan
         * view, and the last good one is kept instead.
         *
         * Pitch is clamped at 89°, so a real heading never gets shorter than about 0.0175
         * and this only fires on a genuinely vertical view — or on a caller passing
         * something that is not a direction at all.
         */
        private const val DEGENERATE_HEADING = 1e-3f

        /** The colour of a Block Type on the Minimap, or null if it has none. */
        fun colourOf(type: BlockType): Int? = MAP_COLOURS[type]

        /** The colour drawn where the world is not known yet. */
        const val UNKNOWN_COLOUR = UNKNOWN

        /** The colour drawn where a Column is genuinely empty. */
        const val EMPTY_COLOUR = EMPTY

        /**
         * How much world the Minimap must be told about before it can be complete: the
         * Cells it covers, plus a margin so the disc's edge is never a hole.
         */
        fun coverageRadius(cells: Int = 64): Int = cells / 2 + 1
    }
}
