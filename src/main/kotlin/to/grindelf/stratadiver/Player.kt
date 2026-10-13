package to.grindelf.stratadiver

import org.joml.Vector3f
import org.joml.Vector3i
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Where the player is looking, and where their body is: the whole of a player. */
data class RaycastResult(val blockPos: Vector3i, val faceNormal: Vector3i)

/**
 * The player: where they stand, how fast they are falling, and what they are looking at.
 *
 * Deliberately knows nothing about GLFW. Input arrives as plain booleans from whoever
 * owns the window, which is what lets the physics and the raycast be reasoned about (and
 * exercised) without one.
 */
class Player(private val world: SolidWorld) {

    /** Eye position. The body hangs below it, from the feet at [EYE_TO_FOOT] down. */
    var position = Vector3f(5f, 15f, 5f)
    var velocity = Vector3f(0f, 0f, 0f)
    var isGrounded = false

    var front = Vector3f(0f, 0f, -1f)
    val up = Vector3f(0f, 1f, 0f)

    private var yaw = -90f
    private var pitch = 0f

    companion object {
        /** Half-width of the body's collision box. */
        const val WIDTH = 0.3f

        /** Height of the body, measured down from the eyes. */
        const val HEIGHT = 1.8f

        /** Eye to feet. Also the reach used when picking a Dropped Item up. */
        const val EYE_TO_FOOT = 1.6f

        private const val GRAVITY = -0.015f
        private const val JUMP_FORCE = 0.25f
        private const val WALK_SPEED = 0.08f

        private const val REACH = 5.0f
        private const val RAY_STEP = 0.1f

        /** How many blocks up the player may look before the view flips over. */
        private const val MAX_PITCH = 89f
    }

    // --- Looking ---

    /** Applies a mouse movement. Deltas are in pixels; [sensitivity] is per pixel. */
    fun look(deltaX: Double, deltaY: Double, sensitivity: Float) {
        yaw += (deltaX * sensitivity).toFloat()
        pitch += (deltaY * sensitivity).toFloat()

        if (pitch > MAX_PITCH) pitch = MAX_PITCH
        if (pitch < -MAX_PITCH) pitch = -MAX_PITCH

        val cosPitch = cos(Math.toRadians(pitch.toDouble()))
        front = Vector3f(
            (cos(Math.toRadians(yaw.toDouble())) * cosPitch).toFloat(),
            sin(Math.toRadians(pitch.toDouble())).toFloat(),
            (sin(Math.toRadians(yaw.toDouble())) * cosPitch).toFloat()
        ).normalize()
    }

    // --- Movement ---

    /** Translates the current key state into horizontal velocity and a possible jump. */
    fun moveInput(forward: Boolean, back: Boolean, left: Boolean, right: Boolean, jump: Boolean) {
        // Movement is horizontal only: ahead and right are derived from yaw alone, so
        // looking up does not walk you upwards.
        val ahead = Vector3f(front.x, 0f, front.z).normalize()
        val rightward = Vector3f(ahead).cross(up).normalize()

        val direction = Vector3f(0f, 0f, 0f)
        if (forward) direction.add(ahead)
        if (back) direction.sub(ahead)
        if (left) direction.sub(rightward)
        if (right) direction.add(rightward)

        if (direction.length() > 0f) {
            direction.normalize().mul(WALK_SPEED)
            velocity.x = direction.x
            velocity.z = direction.z
        } else {
            velocity.x = 0f
            velocity.z = 0f
        }

        if (jump && isGrounded) {
            velocity.y = JUMP_FORCE
            isGrounded = false
        }
    }

    /** Stops horizontal motion. Used while a GUI layer is open and the player is frozen. */
    fun halt() {
        velocity.x = 0f
        velocity.z = 0f
    }

    /**
     * One physics step: gravity, then each axis resolved on its own.
     *
     * Per-axis rather than swept: moving X, checking, then Z, checking, then Y is what
     * stops the player from tunnelling into a corner and what makes sliding along a wall
     * work without a raycast against the wall.
     */
    fun step() {
        velocity.y += GRAVITY

        val next = Vector3f(position)
        next.x += velocity.x
        if (!collides(next)) position.x = next.x

        next.set(position)
        next.z += velocity.z
        if (!collides(next)) position.z = next.z

        next.set(position)
        next.y += velocity.y
        if (!collides(next)) {
            position.y = next.y
            isGrounded = false
        } else {
            if (velocity.y < 0) isGrounded = true
            velocity.y = 0f
        }
    }

    // --- Collision ---

    /** Whether the player's own body would overlap anything Solid at [position]. */
    fun collides(position: Vector3f) = collidesAt(position, WIDTH, HEIGHT)

    /**
     * Whether a body of [width] by [height] centred on [position] would overlap anything
     * Solid. The corners are sampled rather than the centre, or the player would clip
     * through the edge of every block they brush past.
     */
    fun collidesAt(position: Vector3f, width: Float, height: Float): Boolean {
        for (x in listOf(-width, width)) {
            for (z in listOf(-width, width)) {
                for (y in listOf(0f, height)) {
                    if (world.isSolid(position.x + x, position.y - y, position.z + z)) return true
                }
            }
        }
        return false
    }

    /** Whether a block placed at [blockPos] would end up inside the player. */
    fun intersects(blockPos: Vector3i): Boolean {
        val minX = position.x - WIDTH; val maxX = position.x + WIDTH
        val minY = position.y - EYE_TO_FOOT; val maxY = position.y + 0.2f
        val minZ = position.z - WIDTH; val maxZ = position.z + WIDTH

        val blockMinX = blockPos.x.toFloat()
        val blockMinY = blockPos.y.toFloat()
        val blockMinZ = blockPos.z.toFloat()

        return maxX > blockMinX && minX < blockMinX + 1f &&
                maxY > blockMinY && minY < blockMinY + 1f &&
                maxZ > blockMinZ && minZ < blockMinZ + 1f
    }

    // --- Spawning and looking at the world ---

    /**
     * Drops the player onto the surface above (x, z), then pushes them up out of anything
     * they landed inside.
     *
     * The push-up is not defensive programming for its own sake: the spawn column is a
     * fixed 5,5 and can land inside a tree.
     */
    fun spawnAboveGround(x: Float, z: Float) {
        var y = 130f
        for (candidate in 255 downTo 0) {
            if (world.isSolid(x, candidate.toFloat(), z)) {
                y = candidate.toFloat() + 2f
                break
            }
        }
        position.set(x, y, z)

        var attempts = 0
        while (collides(position) && attempts < 20) {
            position.y += 1f
            attempts++
        }
    }

    /** Distance from the player's feet to [point]. */
    fun distanceToFeet(point: Vector3f) =
        Vector3f(position.x, position.y - EYE_TO_FOOT, position.z).distance(point)

    /**
     * The first Solid Cell within [REACH] along the view, and the face it was entered
     * through — the normal tells a placed block which side of the target to go.
     *
     * Marched in small steps rather than intersected analytically: the world is a grid of
     * Cells, so the question is only ever "which cell is this point in", and a step small
     * enough that it cannot skip a Cell answers it exactly.
     */
    fun raycast(): RaycastResult? {
        val current = Vector3f(position)
        val previous = Vector3f(position)

        for (step in 0 until (REACH / RAY_STEP).toInt()) {
            current.add(Vector3f(front).mul(RAY_STEP))

            if (world.isSolid(current.x, current.y, current.z)) {
                val bx = floor(current.x).toInt()
                val by = floor(current.y).toInt()
                val bz = floor(current.z).toInt()

                return RaycastResult(
                    Vector3i(bx, by, bz),
                    Vector3i(
                        floor(previous.x).toInt() - bx,
                        floor(previous.y).toInt() - by,
                        floor(previous.z).toInt() - bz
                    )
                )
            }
            previous.set(current)
        }
        return null
    }
}
