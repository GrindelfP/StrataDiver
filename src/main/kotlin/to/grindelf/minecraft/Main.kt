package to.grindelf.minecraft

//import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
//import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
//import org.lwjgl.glfw.GLFW.GLFW_FALSE
//import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
//import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
//import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
//import org.lwjgl.glfw.GLFW.GLFW_RESIZABLE
//import org.lwjgl.glfw.GLFW.GLFW_TRUE
//import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
//import org.lwjgl.glfw.GLFW.glfwCreateWindow
//import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
//import org.lwjgl.glfw.GLFW.glfwDestroyWindow
//import org.lwjgl.glfw.GLFW.glfwInit
//import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
//import org.lwjgl.glfw.GLFW.glfwPollEvents
//import org.lwjgl.glfw.GLFW.glfwShowWindow
//import org.lwjgl.glfw.GLFW.glfwSwapBuffers
//import org.lwjgl.glfw.GLFW.glfwSwapInterval
//import org.lwjgl.glfw.GLFW.glfwTerminate
//import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.system.MemoryUtil.NULL
import java.io.File


fun main() {
    Minecraft().run()
}


class Minecraft {
    private var window: Long = 0
    private var shaderProgram: Int = 0
    private var vao: Int = 0

    // Matrix math for 3D perspective
    private val projection = Matrix4f().perspective(Math.toRadians(70.0).toFloat(), 1280f / 720f, 0.1f, 100f)
    private val view = Matrix4f().lookAt(0f, 2f, 5f, 0f, 0f, 0f, 0f, 1f, 0f)
    private val model = Matrix4f()

    // CAMERA STATE
    private var cameraPos = org.joml.Vector3f(0f, 0f, 3f)
    private var cameraFront = org.joml.Vector3f(0f, 0f, -1f)
    private var cameraUp = org.joml.Vector3f(0f, 1f, 0f)

    private var yaw = -90f
    private var pitch = 0f
    private var lastX = 640.0
    private var lastY = 360.0
    private var firstMouse = true

    fun run() {
        init()
        setupCube()
        loop()
    }

    private fun setupCube() {
        val vertices = floatArrayOf(
            // Positions            // Colors (R, G, B)
            -0.5f, -0.5f, -0.5f, 0.2f, 0.2f, 0.2f, // Back face
            0.5f, -0.5f, -0.5f, 0.2f, 0.2f, 0.2f,
            0.5f, 0.5f, -0.5f, 0.2f, 0.2f, 0.2f,
            -0.5f, 0.5f, -0.5f, 0.2f, 0.2f, 0.2f,

            -0.5f, -0.5f, 0.5f, 0.8f, 0.8f, 0.8f, // Front face
            0.5f, -0.5f, 0.5f, 0.8f, 0.8f, 0.8f,
            0.5f, 0.5f, 0.5f, 0.8f, 0.8f, 0.8f,
            -0.5f, 0.5f, 0.5f, 0.8f, 0.8f, 0.8f,

            -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, // Left face
            -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f,
            -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f,
            -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f,

            0.5f, 0.5f, 0.5f, 0.6f, 0.6f, 0.6f, // Right face
            0.5f, 0.5f, -0.5f, 0.6f, 0.6f, 0.6f,
            0.5f, -0.5f, -0.5f, 0.6f, 0.6f, 0.6f,
            0.5f, -0.5f, 0.5f, 0.6f, 0.6f, 0.6f,

            -0.5f, -0.5f, -0.5f, 0.3f, 0.3f, 0.3f, // Bottom face
            0.5f, -0.5f, -0.5f, 0.3f, 0.3f, 0.3f,
            0.5f, -0.5f, 0.5f, 0.3f, 0.3f, 0.3f,
            -0.5f, -0.5f, 0.5f, 0.3f, 0.3f, 0.3f,

            -0.5f, 0.5f, -0.5f, 0.9f, 0.9f, 0.9f, // Top face
            0.5f, 0.5f, -0.5f, 0.9f, 0.9f, 0.9f,
            0.5f, 0.5f, 0.5f, 0.9f, 0.9f, 0.9f,
            -0.5f, 0.5f, 0.5f, 0.9f, 0.9f, 0.9f
        )

        val indices = intArrayOf(
            0, 1, 2, 2, 3, 0,       // Back
            4, 5, 6, 6, 7, 4,       // Front
            8, 9, 10, 10, 11, 8,    // Left
            12, 13, 14, 14, 15, 12, // Right
            16, 17, 18, 18, 19, 16, // Bottom
            20, 21, 22, 22, 23, 20  // Top
        )

        // Generate VAO and VBO (Standard OpenGL boilerplate)
        vao = glGenVertexArrays()
        val vbo = glGenBuffers()
        val ebo = glGenBuffers()

        glBindVertexArray(vao)

        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)

        // Position attribute
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * 4, 0)
        glEnableVertexAttribArray(0)
        // Color attribute
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 6 * 4, (3 * 4).toLong())
        glEnableVertexAttribArray(1)

        // Load Shaders (Simplified loader)
        val vShader = glCreateShader(GL_VERTEX_SHADER).also {
            glShaderSource(it, File("src/main/resources/shaders/vertex.glsl").readText())
            glCompileShader(it)
        }
        val fShader = glCreateShader(GL_FRAGMENT_SHADER).also {
            glShaderSource(it, File("src/main/resources/shaders/fragment.glsl").readText())
            glCompileShader(it)
        }
        shaderProgram = glCreateProgram().also {
            glAttachShader(it, vShader)
            glAttachShader(it, fShader)
            glLinkProgram(it)
        }
    }

    private fun loop() {
        while (!glfwWindowShouldClose(window)) {
            processInput()

            // Update view matrix based on camera
            view.setLookAt(cameraPos, org.joml.Vector3f(cameraPos).add(cameraFront), cameraUp)

            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

            glUseProgram(shaderProgram)

            // Pass matrices to shader
            val stack = BufferUtils.createFloatBuffer(16)
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, projection.get(stack))
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, view.get(stack))
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, model.get(stack))

            glBindVertexArray(vao)
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0)

            glfwSwapBuffers(window)
            glfwPollEvents()
        }
    }

    private fun processInput() {
        val speed = 0.05f
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS)
            cameraPos.add(org.joml.Vector3f(cameraFront).mul(speed))
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS)
            cameraPos.sub(org.joml.Vector3f(cameraFront).mul(speed))
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS)
            cameraPos.sub(org.joml.Vector3f(cameraFront).cross(cameraUp).normalize().mul(speed))
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS)
            cameraPos.add(org.joml.Vector3f(cameraFront).cross(cameraUp).normalize().mul(speed))
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS)
            glfwSetWindowShouldClose(window, true)
    }

    private fun init() {
        if (!glfwInit()) throw IllegalStateException("Unable to initialize GLFW")

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)

        // macOS specific hint for Apple Silicon
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE)

        window = glfwCreateWindow(1280, 720, "Minecraft", NULL, NULL)
        if (window == NULL) throw RuntimeException("Failed to create the GLFW window")

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)

        GL.createCapabilities()

        // Set the background color to a "Sky Blue"
        glClearColor(0.5f, 0.8f, 1.0f, 1.0f)

        // Enable Depth Testing so blocks behind other blocks are hidden
        glEnable(GL_DEPTH_TEST)


        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)

        glfwSetCursorPosCallback(window) { _, xpos, ypos ->
            if (firstMouse) {
                lastX = xpos
                lastY = ypos
                firstMouse = false
            }

            val xOffset = xpos - lastX
            val yOffset = lastY - ypos // Reversed: y-coordinates range from bottom to top
            lastX = xpos
            lastY = ypos

            val sensitivity = 0.1f
            yaw += (xOffset * sensitivity).toFloat()
            pitch += (yOffset * sensitivity).toFloat()

            if (pitch > 89f) pitch = 89f
            if (pitch < -89f) pitch = -89f

            val direction = org.joml.Vector3f()
            direction.x = Math.cos(Math.toRadians(yaw.toDouble())).toFloat() * Math.cos(Math.toRadians(pitch.toDouble())).toFloat()
            direction.y = Math.sin(Math.toRadians(pitch.toDouble())).toFloat()
            direction.z = Math.sin(Math.toRadians(yaw.toDouble())).toFloat() * Math.cos(Math.toRadians(pitch.toDouble())).toFloat()
            cameraFront = direction.normalize()
        }
    }

    private fun cleanup() {
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}
