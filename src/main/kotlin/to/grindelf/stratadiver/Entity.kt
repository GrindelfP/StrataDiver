package to.grindelf.stratadiver

import org.lwjgl.opengl.ARBVertexArrayObject.glBindVertexArray
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glUniform2f
import org.lwjgl.opengl.GL20.glUniformMatrix4fv


abstract class Entity(
    var pos: org.joml.Vector3f,
    var width: Float = 0.25f,
    var height: Float = 0.25f
) {
    var vel = org.joml.Vector3f(0f, 0f, 0f)
    var isGrounded = false
    var rotation = 0f

    abstract fun render(stack: java.nio.FloatBuffer, shaderProgram: Int, itemVao: Int)

    fun update(world: StrataDiver) {
        // Apply Gravity
        vel.y -= 0.015f // Matches player gravity

        // Horizontal Movement (Friction)
        vel.x *= 0.9f
        vel.z *= 0.9f

        // Collision Resolution (X)
        val nextX = org.joml.Vector3f(pos).add(vel.x, 0f, 0f)
        if (!world.checkCollisionAt(nextX, width, height)) {
            pos.x = nextX.x
        } else { vel.x = 0f }

        // Collision Resolution (Z)
        val nextZ = org.joml.Vector3f(pos).add(0f, 0f, vel.z)
        if (!world.checkCollisionAt(nextZ, width, height)) {
            pos.z = nextZ.z
        } else { vel.z = 0f }

        // Collision Resolution (Y)
        val nextY = org.joml.Vector3f(pos).add(0f, vel.y, 0f)
        if (!world.checkCollisionAt(nextY, width, height)) {
            pos.y = nextY.y
            isGrounded = false
        } else {
            if (vel.y < 0) isGrounded = true
            vel.y = 0f
        }

        rotation += 2.0f // Constant spin for visual flair
    }
}


class DroppedItem(pos: org.joml.Vector3f, val type: BlockType) : Entity(pos, 0.15f, 0.3f) {
    override fun render(stack: java.nio.FloatBuffer, shaderProgram: Int, itemVao: Int) {
        val modelLoc = glGetUniformLocation(shaderProgram, "model")
        val uvOffsetLoc = glGetUniformLocation(shaderProgram, "uvOffset")

        // 1. Position and Animation
        val bobbing = (Math.sin(rotation.toDouble() * 0.05).toFloat() * 0.1f)

        val modelMatrix = org.joml.Matrix4f()
            .translation(pos.x, pos.y + bobbing, pos.z)
            .rotate(Math.toRadians(rotation.toDouble()).toFloat(), 0f, 1f, 0f)
            .scale(0.25f) // Scale the 1x1 cube down to item size

        glUniformMatrix4fv(modelLoc, false, modelMatrix.get(stack))

        // 2. Texture Logic
        glUniform2f(uvOffsetLoc, type.texX.toFloat(), type.texY.toFloat())

        // 3. Draw
        glBindVertexArray(itemVao)
        glDrawArrays(GL_TRIANGLES, 0, 36)
    }
}
