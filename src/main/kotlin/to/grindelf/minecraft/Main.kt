package to.grindelf.minecraft

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

    companion object {
        const val DAY_SPEED: Float = 0.2f
    }

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

    // TEXTURES
    private var textureAtlasID: Int = 0

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


    private fun loop() {
        val stack = BufferUtils.createFloatBuffer(16)

        while (!glfwWindowShouldClose(window)) {
            // 1. Celestial Timing
            // Speed up or slow down time by changing the multiplier (0.2f is slow, 1.0f is fast)
            val time = glfwGetTime().toFloat() * DAY_SPEED

            // Calculate Sun Position (Orbiting on the Y/Z plane)
            val sunDir = org.joml.Vector3f(
                0.5f,                             // Fixed tilt so shadows aren't perfectly aligned
                Math.sin(time.toDouble()).toFloat(), // Up/Down movement
                Math.cos(time.toDouble()).toFloat()  // Forward/Backward movement
            )

            // Moon is directly opposite the Sun
            val moonDir = org.joml.Vector3f(sunDir).mul(-1f)

            // 2. Dynamic Sky Color
            // Blend sky color from bright blue to dark navy based on Sun height (sunDir.y)
            val dayIntensity = Math.max(0.05f, sunDir.y)
            val skyR = 0.5f * dayIntensity
            val skyG = 0.8f * dayIntensity
            val skyB = 1.0f * (dayIntensity + 0.1f)
            glClearColor(skyR, skyG, skyB, 1.0f)

            // 3. Logic & Physics Updates
            processInput()
            update()
            updateChunks()
            selectedBlock = raycast()

            // 4. Start Rendering
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glUseProgram(shaderProgram)

            // 5. Update Light Uniforms
            glUniform3f(glGetUniformLocation(shaderProgram, "sunDir"), sunDir.x, sunDir.y, sunDir.z)
            glUniform3f(glGetUniformLocation(shaderProgram, "moonDir"), moonDir.x, moonDir.y, moonDir.z)

            // 6. Texture Binding
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, textureAtlasID)
            glUniform1i(glGetUniformLocation(shaderProgram, "textureAtlas"), 0)

            // 7. Update Camera Matrices
            view.setLookAt(cameraPos, org.joml.Vector3f(cameraPos).add(cameraFront), cameraUp)
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, projection.get(stack))
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, view.get(stack))

            // 8. Render World Chunks
            val modelMatrix = org.joml.Matrix4f() // Identity for chunks
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, modelMatrix.get(stack))

            chunks.values.forEach { chunk ->
                if (chunk.isMeshGenerated) {
                    glBindVertexArray(chunk.vao)
                    glDrawArrays(GL_TRIANGLES, 0, chunk.vertexCount)
                }
            }

            // 9. Render Selection Box
            if (selectedBlock != null) {
                glLineWidth(2.0f)
                val bPos = selectedBlock!!.blockPos
                val selectionModel = org.joml.Matrix4f()
                    .translation(bPos.x.toFloat(), bPos.y.toFloat(), bPos.z.toFloat())
                    .scale(1.005f)

                glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, selectionModel.get(stack))
                glBindVertexArray(selectionVao)
                glDrawArrays(GL_LINES, 0, 24)
            }

            // 10. Render UI (Crosshair)
            glDisable(GL_DEPTH_TEST)
            val identity = org.joml.Matrix4f()
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, identity.get(stack))
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, identity.get(stack))
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, identity.get(stack))

            glBindVertexArray(crosshairVao)
            glDrawArrays(GL_LINES, 0, 4)
            glEnable(GL_DEPTH_TEST)

            // 11. Finalize Frame
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


    private fun init() {
        if (!glfwInit()) throw IllegalStateException("Unable to initialize GLFW")

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE)

        window = glfwCreateWindow(1280, 720, "Minecraft", NULL, NULL)
        if (window == NULL) throw RuntimeException("Failed to create the GLFW window")

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)

        // CRITICAL: Initialize LWJGL capabilities before calling ANY gl... functions
        GL.createCapabilities()

        // Now it is safe to call OpenGL functions
        textureAtlasID = generateProceduralTexture()

        glClearColor(0.5f, 0.8f, 1.0f, 1.0f)
        glEnable(GL_DEPTH_TEST)

        // Transparent UI support (helpful for textures later)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)

        // --- Shader Compilation ---
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

        setupUI() // Make sure setupUI is updated to the 5-float stride!

        // Mouse Movement Callback
        glfwSetCursorPosCallback(window) { _, xpos, ypos ->
            if (firstMouse) {
                lastX = xpos
                lastY = ypos
                firstMouse = false
            }
            val xOffset = xpos - lastX
            val yOffset = lastY - ypos
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

        // Mouse Click Callback
        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (action == GLFW_PRESS && selectedBlock != null) {
                val result = selectedBlock!!
                val targetChunk = getChunkAt(result.blockPos.x.toFloat(), result.blockPos.z.toFloat())

                if (targetChunk != null) {
                    val lx = (result.blockPos.x % 16 + 16) % 16
                    val ly = result.blockPos.y
                    val lz = (result.blockPos.z % 16 + 16) % 16

                    if (button == GLFW_MOUSE_BUTTON_LEFT) {
                        targetChunk.setBlock(lx, ly, lz, BlockType.AIR)
                        buildChunkMesh(targetChunk)
                    } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                        val placePos = org.joml.Vector3i(result.blockPos).add(result.faceNormal)
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
        val size = 0.03f
        val vertices = floatArrayOf(
            // Pos X, Y, Z      // U, V (Empty/White part of atlas)
            -size, 0f, 0f,      0.9f, 0.9f,
            size, 0f, 0f,      0.9f, 0.9f,
            0f, -size, 0f,     0.9f, 0.9f,
            0f,  size, 0f,     0.9f, 0.9f
        )

        crosshairVao = glGenVertexArrays()
        glBindVertexArray(crosshairVao)
        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        val stride = 5 * 4
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12L)
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
        // 5 floats per vertex: X, Y, Z, U, V
        val vertices = floatArrayOf(
            // Bottom square
            0f,0f,0f, 0.9f,0.9f,  1f,0f,0f, 0.9f,0.9f,
            1f,0f,0f, 0.9f,0.9f,  1f,0f,1f, 0.9f,0.9f,
            1f,0f,1f, 0.9f,0.9f,  0f,0f,1f, 0.9f,0.9f,
            0f,0f,1f, 0.9f,0.9f,  0f,0f,0f, 0.9f,0.9f,

            // Top square
            0f,1f,0f, 0.9f,0.9f,  1f,1f,0f, 0.9f,0.9f,
            1f,1f,0f, 0.9f,0.9f,  1f,1f,1f, 0.9f,0.9f,
            1f,1f,1f, 0.9f,0.9f,  0f,1f,1f, 0.9f,0.9f,
            0f,1f,1f, 0.9f,0.9f,  0f,1f,0f, 0.9f,0.9f,

            // Vertical pillars
            0f,0f,0f, 0.9f,0.9f,  0f,1f,0f, 0.9f,0.9f,
            1f,0f,0f, 0.9f,0.9f,  1f,1f,0f, 0.9f,0.9f,
            1f,0f,1f, 0.9f,0.9f,  1f,1f,1f, 0.9f,0.9f,
            0f,0f,1f, 0.9f,0.9f,  0f,1f,1f, 0.9f,0.9f
        )

        selectionVao = glGenVertexArrays()
        glBindVertexArray(selectionVao)
        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        val stride = 5 * 4
        // Position
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(0)
        // UVs (instead of color)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, (3 * 4).toLong())
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
        // IMPORTANT: Now using 8 floats per vertex (Pos:3, UV:2, Normal:3)
        c.vertexCount = vertices.size / 8

        c.vao = glGenVertexArrays()
        glBindVertexArray(c.vao)
        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        // Stride is 8 floats * 4 bytes = 32 bytes
        val stride = 8 * 4

        // 1. Position: Location 0, Size 3
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(0)

        // 2. Texture UV: Location 1, Size 2
        // Offset: starts after 3 position floats (12 bytes)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, (3 * 4).toLong())
        glEnableVertexAttribArray(1)

        // 3. Normals: Location 2, Size 3
        // Offset: starts after 3 pos + 2 UV floats (20 bytes)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, (5 * 4).toLong())
        glEnableVertexAttribArray(2)

        c.isMeshGenerated = true
    }


    private fun generateProceduralTexture(): Int {
        val atlasSize = 256
        val tileSize = 64 // 256 / 4 tiles = 64 pixels per tile
        val buffer = BufferUtils.createByteBuffer(atlasSize * atlasSize * 4)

        for (y in 0 until atlasSize) {
            for (x in 0 until atlasSize) {
                val tx = x / tileSize
                val ty = y / tileSize

                val r: Byte; val g: Byte; val b: Byte

                when {
                    tx == 0 && ty == 0 -> { r = 50; g = 110; b = 40 }   // Grass
                    tx == 1 && ty == 0 -> { r = 90; g = 60; b = 30 }    // Dirt
                    tx == 2 && ty == 0 -> { r = 100; g = 100; b = 100 } // Stone
                    else -> { r = 120; g = 120; b = 120 }               // Default
                }

                // Add subtle noise so it's not a flat block of color
                val noise = (Math.random() * 20).toInt().toByte()
                buffer.put((r + noise).toByte())
                buffer.put((g + noise).toByte())
                buffer.put((b + noise).toByte())
                buffer.put(255.toByte()) // Opacity
            }
        }
        buffer.flip()

        val id = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, id)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, atlasSize, atlasSize, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)

        return id
    }


    private fun cleanup() {
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}
