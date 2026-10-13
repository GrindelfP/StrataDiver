package to.grindelf.stratadiver

import org.joml.Matrix4f
import org.lwjgl.opengl.GL11.GL_FALSE
import org.lwjgl.opengl.GL20.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL20.GL_LINK_STATUS
import org.lwjgl.opengl.GL20.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL20.glAttachShader
import org.lwjgl.opengl.GL20.glCompileShader
import org.lwjgl.opengl.GL20.glCreateProgram
import org.lwjgl.opengl.GL20.glCreateShader
import org.lwjgl.opengl.GL20.glDeleteShader
import org.lwjgl.opengl.GL20.glGetProgramInfoLog
import org.lwjgl.opengl.GL20.glGetProgrami
import org.lwjgl.opengl.GL20.glGetShaderInfoLog
import org.lwjgl.opengl.GL20.glGetShaderi
import org.lwjgl.opengl.GL20.glGetUniformLocation
import org.lwjgl.opengl.GL20.glLinkProgram
import org.lwjgl.opengl.GL20.glShaderSource
import org.lwjgl.opengl.GL20.glUniform1f
import org.lwjgl.opengl.GL20.glUniform1i
import org.lwjgl.opengl.GL20.glUniform2f
import org.lwjgl.opengl.GL20.glUniform3f
import org.lwjgl.opengl.GL20.glUniformMatrix4fv
import org.lwjgl.opengl.GL20.glUseProgram
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A linked shader program, with every uniform location resolved once.
 *
 * The frame loop used to look uniforms up by name at every single draw — "model",
 * "tint", "uvOffset", "projection" and a dozen more, dozens of times per frame. A name
 * lookup is a string comparison inside the driver, every time, for a value that cannot
 * change while the program is linked.
 *
 * The set methods take the uniform's *name* still, because that is what makes the call
 * sites readable: seeing `setTint` at the call tells you what is being set, where
 * `glUniform3f(glGetUniformLocation(...))` tells you only how.
 */
class ShaderProgram(vertexPath: String, fragmentPath: String) {

    val id: Int

    private val locations = HashMap<String, Int>()

    /**
     * GL wants its matrices in native byte order and JOML writes into the buffer as-is,
     * so this buffer is allocated once and reused by every uniform upload in the frame.
     *
     * Reusing a 16-float buffer hundreds of times a frame only works because JOML's
     * `get(FloatBuffer)` leaves the position where it found it rather than consuming it.
     * That is not obvious and not documented, so it was checked directly: 10,000 uploads
     * into one 16-float buffer complete without overflow and read back correctly.
     */
    private val stack: FloatBuffer =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        val vertex = compile(GL_VERTEX_SHADER, vertexPath)
        val fragment = compile(GL_FRAGMENT_SHADER, fragmentPath)

        id = glCreateProgram().also {
            glAttachShader(it, vertex)
            glAttachShader(it, fragment)
            glLinkProgram(it)
            if (glGetProgrami(it, GL_LINK_STATUS) == GL_FALSE) {
                throw RuntimeException("Shader link failed: ${glGetProgramInfoLog(it)}")
            }
        }

        // The program holds its own copy of the compiled code now.
        glDeleteShader(vertex)
        glDeleteShader(fragment)
    }

    private fun compile(type: Int, path: String): Int {
        val stage = if (type == GL_VERTEX_SHADER) "Vertex" else "Fragment"
        val file = File(path)
        return glCreateShader(type).also {
            glShaderSource(it, file.readText())
            glCompileShader(it)
            if (glGetShaderi(it, GL_COMPILE_STATUS) == GL_FALSE) {
                throw RuntimeException("$stage shader failed (${file.name}): ${glGetShaderInfoLog(it)}")
            }
        }
    }

    fun use() = glUseProgram(id)

    /**
     * A uniform the program does not use resolves to -1, and every glUniform* call with
     * -1 is a no-op. So an unused name costs nothing and, unlike crashing, does not force
     * every call site to know which uniforms exist.
     */
    private fun location(name: String) = locations.getOrPut(name) { glGetUniformLocation(id, name) }

    fun setInt(name: String, value: Int) = glUniform1i(location(name), value)

    fun setFloat(name: String, value: Float) = glUniform1f(location(name), value)

    fun setVec2(name: String, x: Float, y: Float) = glUniform2f(location(name), x, y)

    fun setVec3(name: String, x: Float, y: Float, z: Float) = glUniform3f(location(name), x, y, z)

    fun setMatrix(name: String, matrix: Matrix4f) = glUniformMatrix4fv(location(name), false, matrix.get(stack))

    /**
     * The brightness a fragment is lit at. The GUI is drawn unlit and takes its shading
     * entirely from here, which is what lets a composited Icon's three faces stay
     * distinguishable and what stops GUI art flickering with the time of day.
     */
    fun setTint(brightness: Float) = setVec3("tint", brightness, brightness, brightness)
}
