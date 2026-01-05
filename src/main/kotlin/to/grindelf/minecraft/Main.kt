package to.grindelf.minecraft

import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.system.MemoryStack
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
    private var cameraPos = org.joml.Vector3f(5f, 15f, 5f)
    private var cameraFront = org.joml.Vector3f(0f, 0f, -1f)
    private var cameraUp = org.joml.Vector3f(0f, 1f, 0f)

    private var yaw = -90f
    private var pitch = 0f
    private var lastX = 640.0
    private var lastY = 360.0
    private var firstMouse = true

    // CHUNK properties
    private var chunk = Chunk(0, 0, 0)
    private var vertexCount = 0


    private val chunks = mutableMapOf<Pair<Int, Int>, Chunk>()
    private val renderDistance = 4 // Chunks in every direction

    // PHYSICS
    private var velocity = org.joml.Vector3f(0f, 0f, 0f)
    private val gravity = -0.015f
    private val jumpForce = 0.25f
    private var isGrounded = false

    // Player dimensions
    private val playerWidth = 0.3f
    private val playerHeight = 1.8f

    // CROSSHAIR
    private var crosshairVao: Int = 0

    // SELECTION UI
    private var selectionVao: Int = 0
    private var selectedBlock: RaycastResult? = null

    // Inventory: BlockType ID -> Count
    private val inventory = mutableMapOf<Byte, Int>()

    fun run() {
        init()
        updateChunks() // Generates the 9x9 starting area
        setupSelectionBox()
        loop()
        cleanup()
    }

    private fun setupChunk() {
        // 1. Generate the mesh data from our Chunk object
        val vertices = MeshGenerator.generateChunkMesh(chunk)
        vertexCount = vertices.size / 6 // 6 floats per vertex (3 for Position, 3 for Color)

        // 2. Generate and Bind Vertex Array Object (VAO)
        // The VAO stores the layout of our data
        vao = glGenVertexArrays()
        glBindVertexArray(vao)

        // 3. Generate and Bind Vertex Buffer Object (VBO)
        // The VBO stores the actual vertex data on the GPU
        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        // 4. Define Vertex Attribute Layout
        // Stride is 6 * 4 bytes (6 floats total, each float is 4 bytes)
        val stride = 6 * 4

        // Position Attribute (Location 0 in vertex.glsl)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(0)

        // Color Attribute (Location 1 in vertex.glsl)
        // Offset is 3 * 4 bytes (it starts after the 3 position floats)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, (3 * 4).toLong())
        glEnableVertexAttribArray(1)

        // 5. Shader Compilation Logic
        val vShader = glCreateShader(GL_VERTEX_SHADER).also {
            glShaderSource(it, File("src/main/resources/shaders/vertex.glsl").readText())
            glCompileShader(it)
            // Check for compilation errors
            if (glGetShaderi(it, GL_COMPILE_STATUS) == GL_FALSE) {
                throw RuntimeException("Vertex Shader Compilation Failed: ${glGetShaderInfoLog(it)}")
            }
        }

        val fShader = glCreateShader(GL_FRAGMENT_SHADER).also {
            glShaderSource(it, File("src/main/resources/shaders/fragment.glsl").readText())
            glCompileShader(it)
            // Check for compilation errors
            if (glGetShaderi(it, GL_COMPILE_STATUS) == GL_FALSE) {
                throw RuntimeException("Fragment Shader Compilation Failed: ${glGetShaderInfoLog(it)}")
            }
        }

        // 6. Link Shaders into a Program
        shaderProgram = glCreateProgram().also {
            glAttachShader(it, vShader)
            glAttachShader(it, fShader)
            glLinkProgram(it)
            // Check for linking errors
            if (glGetProgrami(it, GL_LINK_STATUS) == GL_FALSE) {
                throw RuntimeException("Shader Program Linking Failed: ${glGetProgramInfoLog(it)}")
            }
        }

        // 7. Cleanup: Once linked, individual shaders can be deleted
        glDeleteShader(vShader)
        glDeleteShader(fShader)
    }

//    private fun setupCube() {
//        val vertices = floatArrayOf(
//            // Positions            // Colors (R, G, B)
//            -0.5f, -0.5f, -0.5f, 0.2f, 0.2f, 0.2f, // Back face
//            0.5f, -0.5f, -0.5f, 0.2f, 0.2f, 0.2f,
//            0.5f, 0.5f, -0.5f, 0.2f, 0.2f, 0.2f,
//            -0.5f, 0.5f, -0.5f, 0.2f, 0.2f, 0.2f,
//
//            -0.5f, -0.5f, 0.5f, 0.8f, 0.8f, 0.8f, // Front face
//            0.5f, -0.5f, 0.5f, 0.8f, 0.8f, 0.8f,
//            0.5f, 0.5f, 0.5f, 0.8f, 0.8f, 0.8f,
//            -0.5f, 0.5f, 0.5f, 0.8f, 0.8f, 0.8f,
//
//            -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, // Left face
//            -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f,
//            -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f,
//            -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
//
//            0.5f, 0.5f, 0.5f, 0.6f, 0.6f, 0.6f, // Right face
//            0.5f, 0.5f, -0.5f, 0.6f, 0.6f, 0.6f,
//            0.5f, -0.5f, -0.5f, 0.6f, 0.6f, 0.6f,
//            0.5f, -0.5f, 0.5f, 0.6f, 0.6f, 0.6f,
//
//            -0.5f, -0.5f, -0.5f, 0.3f, 0.3f, 0.3f, // Bottom face
//            0.5f, -0.5f, -0.5f, 0.3f, 0.3f, 0.3f,
//            0.5f, -0.5f, 0.5f, 0.3f, 0.3f, 0.3f,
//            -0.5f, -0.5f, 0.5f, 0.3f, 0.3f, 0.3f,
//
//            -0.5f, 0.5f, -0.5f, 0.9f, 0.9f, 0.9f, // Top face
//            0.5f, 0.5f, -0.5f, 0.9f, 0.9f, 0.9f,
//            0.5f, 0.5f, 0.5f, 0.9f, 0.9f, 0.9f,
//            -0.5f, 0.5f, 0.5f, 0.9f, 0.9f, 0.9f
//        )
//
//        val indices = intArrayOf(
//            0, 1, 2, 2, 3, 0,       // Back
//            4, 5, 6, 6, 7, 4,       // Front
//            8, 9, 10, 10, 11, 8,    // Left
//            12, 13, 14, 14, 15, 12, // Right
//            16, 17, 18, 18, 19, 16, // Bottom
//            20, 21, 22, 22, 23, 20  // Top
//        )
//
//        // Generate VAO and VBO (Standard OpenGL boilerplate)
//        vao = glGenVertexArrays()
//        val vbo = glGenBuffers()
//        val ebo = glGenBuffers()
//
//        glBindVertexArray(vao)
//
//        glBindBuffer(GL_ARRAY_BUFFER, vbo)
//        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)
//
//        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)
//        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)
//
//        // Position attribute
//        glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * 4, 0)
//        glEnableVertexAttribArray(0)
//        // Color attribute
//        glVertexAttribPointer(1, 3, GL_FLOAT, false, 6 * 4, (3 * 4).toLong())
//        glEnableVertexAttribArray(1)
//
//        // Load Shaders (Simplified loader)
//        val vShader = glCreateShader(GL_VERTEX_SHADER).also {
//            glShaderSource(it, File("src/main/resources/shaders/vertex.glsl").readText())
//            glCompileShader(it)
//        }
//        val fShader = glCreateShader(GL_FRAGMENT_SHADER).also {
//            glShaderSource(it, File("src/main/resources/shaders/fragment.glsl").readText())
//            glCompileShader(it)
//        }
//        shaderProgram = glCreateProgram().also {
//            glAttachShader(it, vShader)
//            glAttachShader(it, fShader)
//            glLinkProgram(it)
//        }
//    }

    private fun loop() {
        val stack = BufferUtils.createFloatBuffer(16)

        while (!glfwWindowShouldClose(window)) {
            // 1. Logic Updates
            processInput()
            update()
            updateChunks() // Ensure the world generates around the player

            // 2. Physics/Interaction Updates
            selectedBlock = raycast()

            // 3. Prepare for Rendering
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glUseProgram(shaderProgram)

            // 4. Update Global Matrices (View & Projection)
            // This makes the camera follow your yaw, pitch, and position
            view.setLookAt(
                cameraPos,
                org.joml.Vector3f(cameraPos).add(cameraFront),
                cameraUp
            )

            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, projection.get(stack))
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, view.get(stack))

            // 5. Render World Chunks
            chunks.values.forEach { chunk ->
                if (chunk.isMeshGenerated) {
                    // Since our MeshGenerator uses world-space coordinates,
                    // the model matrix for chunks is simply the Identity matrix.
                    val modelMatrix = org.joml.Matrix4f()
                    glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, modelMatrix.get(stack))

                    glBindVertexArray(chunk.vao)
                    glDrawArrays(GL_TRIANGLES, 0, chunk.vertexCount)
                }
            }

            // 6. Render Selection Box (Highlighting the block you look at)
            if (selectedBlock != null) {
                glLineWidth(2.0f)
                val bPos = selectedBlock!!.blockPos
                // Slightly scale up to prevent "Z-fighting" (flickering) against the block face
                val selectionModel = org.joml.Matrix4f()
                    .translation(bPos.x.toFloat(), bPos.y.toFloat(), bPos.z.toFloat())
                    .scale(1.005f)

                glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, selectionModel.get(stack))
                glBindVertexArray(selectionVao)
                glDrawArrays(GL_LINES, 0, 24)
            }

            // 7. Render UI (Crosshair)
            glDisable(GL_DEPTH_TEST) // Draw over everything

            // Reset matrices to Identity for 2D screen-space drawing
            val identity = org.joml.Matrix4f()
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, identity.get(stack))
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, identity.get(stack))
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, identity.get(stack))

            glBindVertexArray(crosshairVao)
            glDrawArrays(GL_LINES, 0, 4)

            glEnable(GL_DEPTH_TEST) // Re-enable for the next frame

            // 8. Swap and Poll
            glfwSwapBuffers(window)
            glfwPollEvents()
        }
    }


    private fun processInput() {
        val walkSpeed = 0.08f
        val moveDir = org.joml.Vector3f(0f, 0f, 0f)

        // Calculate forward/right vectors based only on Yaw (no flying up/down)
        val forward = org.joml.Vector3f(cameraFront.x, 0f, cameraFront.z).normalize()
        val right = org.joml.Vector3f(forward).cross(cameraUp).normalize()

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) moveDir.add(forward)
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) moveDir.sub(forward)
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) moveDir.sub(right)
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) moveDir.add(right)

        if (moveDir.length() > 0) {
            moveDir.normalize().mul(walkSpeed)
            velocity.x = moveDir.x
            velocity.z = moveDir.z
        } else {
            velocity.x = 0f
            velocity.z = 0f
        }

        // Jump logic
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS && isGrounded) {
            velocity.y = jumpForce
            isGrounded = false
        }

        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS)
            glfwSetWindowShouldClose(window, true)
    }


    private fun update() {
        // Apply gravity
        velocity.y += gravity

        // Resolve X
        val nextX = org.joml.Vector3f(cameraPos).add(velocity.x, 0f, 0f)
        if (!checkCollision(nextX)) {
            cameraPos.x = nextX.x
        }

        // Resolve Z
        val nextZ = org.joml.Vector3f(cameraPos).add(0f, 0f, velocity.z)
        if (!checkCollision(nextZ)) {
            cameraPos.z = nextZ.z
        }

        // Resolve Y (Vertical)
        val nextY = org.joml.Vector3f(cameraPos).add(0f, velocity.y, 0f)
        if (!checkCollision(nextY)) {
            cameraPos.y = nextY.y
            isGrounded = false
        } else {
            // If we hit something while falling, we are grounded
            if (velocity.y < 0) isGrounded = true
            velocity.y = 0f
        }
    }


//    private fun processInput() {
//        val speed = 0.05f
//        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS)
//            cameraPos.add(org.joml.Vector3f(cameraFront).mul(speed))
//        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS)
//            cameraPos.sub(org.joml.Vector3f(cameraFront).mul(speed))
//        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS)
//            cameraPos.sub(org.joml.Vector3f(cameraFront).cross(cameraUp).normalize().mul(speed))
//        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS)
//            cameraPos.add(org.joml.Vector3f(cameraFront).cross(cameraUp).normalize().mul(speed))
//        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS)
//            glfwSetWindowShouldClose(window, true)
//    }

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

        GL.createCapabilities()
        glClearColor(0.5f, 0.8f, 1.0f, 1.0f)
        glEnable(GL_DEPTH_TEST)
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)

        // --- NEW SHADER COMPILATION BLOCK ---
        val vShader = glCreateShader(GL_VERTEX_SHADER).also {
            glShaderSource(it, File("src/main/resources/shaders/vertex.glsl").readText())
            glCompileShader(it)
            if (glGetShaderi(it, GL_COMPILE_STATUS) == GL_FALSE)
                throw RuntimeException("Vertex Shader Error: ${glGetShaderInfoLog(it)}")
        }

        val fShader = glCreateShader(GL_FRAGMENT_SHADER).also {
            glShaderSource(it, File("src/main/resources/shaders/fragment.glsl").readText())
            glCompileShader(it)
            if (glGetShaderi(it, GL_COMPILE_STATUS) == GL_FALSE)
                throw RuntimeException("Fragment Shader Error: ${glGetShaderInfoLog(it)}")
        }

        shaderProgram = glCreateProgram().also {
            glAttachShader(it, vShader)
            glAttachShader(it, fShader)
            glLinkProgram(it)
        }
        glDeleteShader(vShader)
        glDeleteShader(fShader)

        setupUI()

        glfwSetCursorPosCallback(window) { _, xpos, ypos ->
            if (firstMouse) {
                lastX = xpos
                lastY = ypos
                firstMouse = false
            }

            val xOffset = xpos - lastX
            val yOffset = lastY - ypos // Y is inverted in screen space
            lastX = xpos
            lastY = ypos

            val sensitivity = 0.1f
            yaw += (xOffset * sensitivity).toFloat()
            pitch += (yOffset * sensitivity).toFloat()

            // Constrain pitch so you don't flip your neck
            if (pitch > 89f) pitch = 89f
            if (pitch < -89f) pitch = -89f

            // Recalculate the direction vector
            val direction = org.joml.Vector3f()
            direction.x = Math.cos(Math.toRadians(yaw.toDouble())).toFloat() * Math.cos(Math.toRadians(pitch.toDouble())).toFloat()
            direction.y = Math.sin(Math.toRadians(pitch.toDouble())).toFloat()
            direction.z = Math.sin(Math.toRadians(yaw.toDouble())).toFloat() * Math.cos(Math.toRadians(pitch.toDouble())).toFloat()

            cameraFront = direction.normalize()
        }

        // Put this at the very end of your init() function
        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (action == GLFW_PRESS && selectedBlock != null) {
                val result = selectedBlock!!

                // 1. Find which chunk the block belongs to
                val targetChunk = getChunkAt(result.blockPos.x.toFloat(), result.blockPos.z.toFloat())

                if (targetChunk != null) {
                    // 2. Convert global world coordinates to local chunk coordinates (0-15)
                    val lx = (result.blockPos.x % 16 + 16) % 16
                    val ly = result.blockPos.y
                    val lz = (result.blockPos.z % 16 + 16) % 16

                    if (button == GLFW_MOUSE_BUTTON_LEFT) {
                        // Destroy block
                        val id = targetChunk.getBlock(lx, ly, lz)
                        inventory[id] = inventory.getOrDefault(id, 0) + 1

                        targetChunk.setBlock(lx, ly, lz, BlockType.AIR)
                        buildChunkMesh(targetChunk) // Re-generate the mesh for this chunk

                        println("Destroyed block at $lx, $ly, $lz. Inventory: $inventory")
                    } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                        // Place block logic
                        val placePos = org.joml.Vector3i(result.blockPos).add(result.faceNormal)

                        // Find chunk for the NEW block (it might be in a neighboring chunk!)
                        val placeChunk = getChunkAt(placePos.x.toFloat(), placePos.z.toFloat())
                        if (placeChunk != null) {
                            val plx = (placePos.x % 16 + 16) % 16
                            val ply = placePos.y
                            val plz = (placePos.z % 16 + 16) % 16

                            placeChunk.setBlock(plx, ply, plz, BlockType.STONE)
                            buildChunkMesh(placeChunk)
                        }
                    }
                }
            }
        }
    }


    private fun setupUI() {
        val size = 0.03f // Size of the crosshair
        val vertices = floatArrayOf(
            -size, 0f, 0f, 1f, 1f, 1f, // Horizontal line
            size, 0f, 0f, 1f, 1f, 1f,
            0f, -size, 0f, 1f, 1f, 1f, // Vertical line
            0f, size, 0f, 1f, 1f, 1f
        )

        crosshairVao = glGenVertexArrays()
        val vbo = glGenBuffers()
        glBindVertexArray(crosshairVao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * 4, 0)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 6 * 4, (3 * 4).toLong())
        glEnableVertexAttribArray(1)
    }


    private fun getChunkAt(x: Float, z: Float): Chunk? {
        val cx = Math.floor(x.toDouble() / 16.0).toInt()
        val cz = Math.floor(z.toDouble() / 16.0).toInt()
        return chunks[Pair(cx, cz)]
    }

    // Update your existing isSolid to check the correct chunk
    private fun isSolid(x: Float, y: Float, z: Float): Boolean {
        val cx = Math.floor(x.toDouble() / 16.0).toInt()
        val cz = Math.floor(z.toDouble() / 16.0).toInt()
        val chunk = chunks[Pair(cx, cz)] ?: return false // If chunk isn't loaded, it's not solid

        // Local coordinates inside the chunk (0-15)
        val lx = (Math.floor(x.toDouble()).toInt() % 16 + 16) % 16
        val ly = Math.floor(y.toDouble()).toInt()
        val lz = (Math.floor(z.toDouble()).toInt() % 16 + 16) % 16

        return chunk.getBlock(lx, ly, lz) != BlockType.AIR.id
    }

    private fun checkCollision(pos: org.joml.Vector3f): Boolean {
        // Check multiple points around the player box to ensure they don't clip into corners
        for (xOffset in listOf(-playerWidth, playerWidth)) {
            for (zOffset in listOf(-playerWidth, playerWidth)) {
                for (yOffset in listOf(0f, playerHeight)) { // Check feet and head
                    if (isSolid(pos.x + xOffset, pos.y - yOffset, pos.z + zOffset)) return true
                }
            }
        }
        return false
    }


    private fun raycast(): RaycastResult? {
        val rayPos = org.joml.Vector3f(cameraPos)
        val step = 0.1f
        val reach = 5.0f

        val currentPos = org.joml.Vector3f(rayPos)
        val lastPos = org.joml.Vector3f(rayPos)

        for (i in 0 until (reach / step).toInt()) {
            currentPos.add(org.joml.Vector3f(cameraFront).mul(step))

            if (isSolid(currentPos.x, currentPos.y, currentPos.z)) {
                val bx = Math.floor(currentPos.x.toDouble()).toInt()
                val by = Math.floor(currentPos.y.toDouble()).toInt()
                val bz = Math.floor(currentPos.z.toDouble()).toInt()

                val target = org.joml.Vector3i(bx, by, bz)

                val lx = Math.floor(lastPos.x.toDouble()).toInt()
                val ly = Math.floor(lastPos.y.toDouble()).toInt()
                val lz = Math.floor(lastPos.z.toDouble()).toInt()
                val normal = org.joml.Vector3i(lx - bx, ly - by, lz - bz)

                return RaycastResult(target, normal)
            }
            lastPos.set(currentPos)
        }
        return null
    }


    private fun setupSelectionBox() {
        val vertices = floatArrayOf(
            // Lines for a 1x1x1 cube
            0f,0f,0f, 0f,0f,0f, 1f,0f,0f, 0f,0f,0f, 1f,1f,0f, 0f,0f,0f, 0f,1f,0f, 0f,0f,0f,
            0f,0f,1f, 0f,0f,0f, 1f,0f,1f, 0f,0f,0f, 1f,1f,1f, 0f,0f,0f, 0f,1f,1f, 0f,0f,0f,
            0f,0f,0f, 0f,0f,0f, 0f,0f,1f, 0f,0f,0f, 1f,0f,0f, 0f,0f,0f, 1f,0f,1f, 0f,0f,0f,
            1f,1f,0f, 0f,0f,0f, 1f,1f,1f, 0f,0f,0f, 0f,1f,0f, 0f,0f,0f, 0f,1f,1f, 0f,0f,0f
        )
        selectionVao = glGenVertexArrays()
        val vbo = glGenBuffers()
        glBindVertexArray(selectionVao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        // Position
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * 4, 0)
        glEnableVertexAttribArray(0)
        // Use the 3 zeros in the data as a "black" color (0,0,0)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 6 * 4, (3 * 4).toLong())
        glEnableVertexAttribArray(1)
    }


    private fun updateChunks() {
        val playerCX = Math.floor(cameraPos.x.toDouble() / 16.0).toInt()
        val playerCZ = Math.floor(cameraPos.z.toDouble() / 16.0).toInt()

        for (x in -renderDistance..renderDistance) {
            for (z in -renderDistance..renderDistance) {
                val targetPos = Pair(playerCX + x, playerCZ + z)
                if (!chunks.containsKey(targetPos)) {
                    val newChunk = Chunk(targetPos.first, 0, targetPos.second)
                    buildChunkMesh(newChunk)
                    chunks[targetPos] = newChunk
                }
            }
        }
    }

    private fun buildChunkMesh(c: Chunk) {
        val vertices = MeshGenerator.generateChunkMesh(c)
        c.vertexCount = vertices.size / 6

        c.vao = glGenVertexArrays()
        glBindVertexArray(c.vao)
        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        val stride = 6 * 4
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, (3 * 4).toLong())
        glEnableVertexAttribArray(1)

        c.isMeshGenerated = true
    }


    private fun cleanup() {
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}

//
//enum class BlockType(val id: Byte) {
//    AIR(0),
//    GRASS(1),
//    DIRT(2),
//    STONE(3)
//}
