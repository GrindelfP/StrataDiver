package to.grindelf.stratadiver

import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL30.glBindVertexArray

/**
 * Whether a body of [width] by [height] centred on [at] would overlap anything Solid.
 * The corners are sampled, not the centre, or a body would clip through the edge of
 * every Cell it brushes past.
 */
fun SolidWorld.isSolidAt(at: Vector3f, width: Float, height: Float): Boolean {
    for (x in listOf(-width, width)) {
        for (z in listOf(-width, width)) {
            for (y in listOf(0f, height)) {
                if (isSolid(at.x + x, at.y - y, at.z + z)) return true
            }
        }
    }
    return false
}

/**
 * Anything with a position in the world that moves on its own.
 *
 * Knows only that the world can say whether a Cell is Solid. It has no idea a window or a
 * shader program exist, so how an Entity moves is checkable without either.
 */
abstract class Entity(
    var pos: Vector3f,
    var width: Float = 0.25f,
    var height: Float = 0.25f
) {
    var vel = Vector3f(0f, 0f, 0f)
    var isGrounded = false
    var rotation = 0f

    /** The world-space matrix this Entity is drawn with. */
    abstract fun modelMatrix(): Matrix4f

    /** The Tile this Entity is drawn from. */
    abstract fun tile(): TileRef

    /**
     * The Block Type this Entity is made of, if it is made of one at all.
     *
     * Null for anything that is not a thing you could have picked up and put down. The path
     * tracer needs it for emission and for the name of what it is looking at; the
     * rasterizer has never needed it, because everything was a Tile and a cube.
     */
    open val blockType: BlockType? get() = null

    /**
     * Where the Entity is actually drawn, which is not always where it is simulated.
     *
     * A dropped item bobs as it spins. The rasterizer learned that from [modelMatrix] and
     * the path tracer needs the same number, so it is stated once here rather than worked
     * out twice in two places that would then disagree.
     */
    open fun drawCentre(): Vector3f = pos

    /**
     * Half the width of the box this Entity is drawn from, in Cells.
     *
     * Deliberately not [width] and [height]: those are the collision box, which is bigger
     * than the picture because it has to stop the player walking through a thing that looks
     * small.
     */
    open fun halfExtent(): Float = 0.125f

    fun update(world: SolidWorld) {
        vel.y -= 0.015f // Matches player gravity

        // Friction: items skitter to a stop rather than sliding forever.
        vel.x *= 0.9f
        vel.z *= 0.9f

        // Each axis on its own, for the same reason the player's do: it is what stops an
        // item tunnelling into a corner and what lets it slide along a wall.
        if (!world.isSolidAt(moved(vel.x, 0f, 0f), width, height)) pos.x += vel.x else vel.x = 0f
        if (!world.isSolidAt(moved(0f, 0f, vel.z), width, height)) pos.z += vel.z else vel.z = 0f
        if (!world.isSolidAt(moved(0f, vel.y, 0f), width, height)) {
            pos.y += vel.y
            isGrounded = false
        } else {
            if (vel.y < 0) isGrounded = true
            vel.y = 0f
        }

        rotation += 2.0f // Constant spin for visual flair
    }

    private fun moved(dx: Float, dy: Float, dz: Float) = Vector3f(pos.x + dx, pos.y + dy, pos.z + dz)
}

/** An item that fell out of a broken Cell and is waiting to be picked up. */
class DroppedItem(pos: Vector3f, val type: BlockType) : Entity(pos, 0.15f, 0.3f) {

    override val blockType: BlockType get() = type

    /** How high the item rides at this moment of its spin. */
    private val bob: Float get() = (Math.sin(rotation.toDouble()) * 0.05).toFloat() * 0.1f

    override fun drawCentre(): Vector3f = Vector3f(pos.x, pos.y + bob, pos.z)

    override fun modelMatrix(): Matrix4f {
        val centre = drawCentre()
        return Matrix4f()
            .translation(centre.x, centre.y, centre.z)
            .rotate(Math.toRadians(rotation.toDouble()).toFloat(), 0f, 1f, 0f)
            .scale(0.25f) // Scale the unit cube down to item size
    }

    override fun tile() = type.primary
}

/**
 * Draws every Entity from the one cube mesh they all share.
 *
 * [lightOf] reports the packed light of the Cell an Entity is sitting in, so a dropped
 * item in a lit room is lit and one at the bottom of a dark tunnel is not. Passed in rather
 * than reached for, because [Entity] knows nothing about the world beyond whether a Cell is
 * Solid and this is the same arrangement as [SolidWorld]: the caller supplies the one fact
 * that is needed and the callee stays ignorant of where it came from.
 *
 * The item cube is uploaded in the eight-float format, without per-vertex light, so its
 * vertices carry none and the light arrives through the `lightLevel` uniform instead.
 */
object EntityRenderer {

    fun draw(entities: List<Entity>, shader: ShaderProgram, cube: Mesh, lightOf: (Vector3f) -> Int) {
        shader.setInt("isEntity", 1)
        for (entity in entities) {
            val tile = entity.tile()
            val packed = lightOf(entity.pos)
            shader.setMatrix("model", entity.modelMatrix())
            shader.setVec2("uvOffset", tile.tx.toFloat(), tile.ty.toFloat())
            shader.setVec2(
                "lightLevel",
                Light.day(packed) / Light.MAX.toFloat(),
                Light.torch(packed) / Light.MAX.toFloat()
            )
            glBindVertexArray(cube.vao)
            glDrawArrays(GL_TRIANGLES, 0, 36)
        }
        shader.setInt("isEntity", 0)
    }
}
