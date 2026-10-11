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
import kotlin.math.floor


fun main() {
    Kubold().run()
}


class Kubold {

    companion object {
        const val CONST_TIME: Boolean = true
        const val QUICK_DAY_SPEED: Float = 0.0174f
        const val MINECRAFT_DAY_SPEED: Float = 0.0052f
        const val DAY_SPEED: Float = QUICK_DAY_SPEED
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


    private val chunks = mutableMapOf<Triple<Int, Int, Int>, Chunk>()
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

    // INVENTORY
    private val inventory = Inventory()
    private var isInventoryOpen = false
    private var selectedSlot = 0 // 0 to 8 (Hotbar)
    private val hotbar = arrayOfNulls<BlockType>(9) // What is in the 9 slots

    // TEXTURES
    private var textureAtlasID: Int = 0

    // MINIMAP
    private var mapVao: Int = 0

    // ENTITIES
    private val entities = mutableListOf<Entity>()
    private var itemVao: Int = 0 // Create a simple 1x1 cube VAO for items in init()


    fun run() {
        init()
        updateChunks()
        safeSpawn()
        setupSelectionBox()
        setupMap()
        loop()
        cleanup()
    }

    private fun safeSpawn() {
        // Dynamically find the ground height at (5, 5)
        val spawnX = 5f
        val spawnZ = 5f
        var spawnY = 130f // Fallback

        // Look down from the top of the world to find the first solid block
        for (y in 255 downTo 0) {
            if (isSolid(spawnX, y.toFloat(), spawnZ)) {
                spawnY = y.toFloat() + 2f // Place camera at eye level (2 blocks up)
                break
            }
        }
        cameraPos.set(spawnX, spawnY, spawnZ)
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

        val uiProjection = org.joml.Matrix4f().ortho(0f, 1280f, 720f, 0f, -1f, 1f)

        while (!glfwWindowShouldClose(window)) {
            // 1. Celestial Timing
            val time = if (CONST_TIME) 2f else glfwGetTime().toFloat() * DAY_SPEED;
            val sunDir = org.joml.Vector3f(
                0.5f,
                Math.sin(time.toDouble()).toFloat(),
                Math.cos(time.toDouble()).toFloat()
            )
            val moonDir = org.joml.Vector3f(sunDir).mul(-1f)

            // 2. Dynamic Sky Color
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

            // --- CRITICAL FIX: Reset isMap for the 3D World ---
            val isMapLoc = glGetUniformLocation(shaderProgram, "isMap")
            glUniform1i(isMapLoc, 0)

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
            val modelMatrix = org.joml.Matrix4f()
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, modelMatrix.get(stack))

            chunks.values.forEach { chunk ->
                if (chunk.isMeshGenerated) {
                    glBindVertexArray(chunk.vao)
                    glDrawArrays(GL_TRIANGLES, 0, chunk.vertexCount)
                }
            }

            // 8.5 Render Entities
            val isEntityLoc = glGetUniformLocation(shaderProgram, "isEntity")
            glUniform1i(isEntityLoc, 1) // Turn ON entity mode

            entities.forEach { entity ->
                entity.render(stack, shaderProgram, itemVao)
            }

            glUniform1i(isEntityLoc, 0) // Turn OFF entity mode (so chunks render correctly)

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

            // --- 10. Render UI (Minimap) ---
//            glDisable(GL_DEPTH_TEST)
//
//            // Prepare Minimap State
//            glUniform1i(isMapLoc, 1) // Turn ON map mode
//            glUniform2f(glGetUniformLocation(shaderProgram, "playerPos"), cameraPos.x, cameraPos.z)
//            glUniform1f(glGetUniformLocation(shaderProgram, "mapZoom"), 50.0f)
//
//            // Use identity matrices for 2D UI overlay
//            val identity = Matrix4f()
//            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, identity.get(stack))
//            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, identity.get(stack))
//            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, identity.get(stack))
//
//            glBindVertexArray(mapVao)
//            glDrawArrays(GL_TRIANGLES, 0, 6)

            // --- 11. Render UI (Crosshair) ---
            // Turn map mode OFF so crosshair doesn't get circular-masked or noise-colored
            glUniform1i(isMapLoc, 0)

            glBindVertexArray(crosshairVao)
            glDrawArrays(GL_LINES, 0, 4)

            glEnable(GL_DEPTH_TEST)

            // 12. RENDER UI Inventory
            if (isInventoryOpen) {
                // Switch to UI Projection
                glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, uiProjection.get(stack))
                glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, Matrix4f().get(stack)) // Identity view

                renderInventory(stack)
            }

            // 13. Finalize Frame
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

        glfwSetKeyCallback(window) { _, key, _, action, _ ->
            if (action == GLFW_PRESS) {
                // Toggle Inventory
                if (key == GLFW_KEY_E) {
                    isInventoryOpen = !isInventoryOpen
                    // Unlock mouse when inventory is open
                    glfwSetInputMode(window, GLFW_CURSOR,
                        if (isInventoryOpen) GLFW_CURSOR_NORMAL else GLFW_CURSOR_DISABLED)
                }

                // Hotbar selection with 1-9 keys
                if (key in GLFW_KEY_1..GLFW_KEY_9) {
                    selectedSlot = key - GLFW_KEY_1
                }
            }
        }
    }


    private fun update() {
        // Apply gravity
        velocity.y += gravity

        // Resolve X
        val nextX = org.joml.Vector3f(cameraPos).add(velocity.x, 0f, 0f)
        if (!checkCollisionPlayer(nextX)) {
            cameraPos.x = nextX.x
        }

        // Resolve Z
        val nextZ = org.joml.Vector3f(cameraPos).add(0f, 0f, velocity.z)
        if (!checkCollisionPlayer(nextZ)) {
            cameraPos.z = nextZ.z
        }

        // Resolve Y (Vertical)
        val nextY = org.joml.Vector3f(cameraPos).add(0f, velocity.y, 0f)
        if (!checkCollisionPlayer(nextY)) {
            cameraPos.y = nextY.y
            isGrounded = false
        } else {
            // If we hit something while falling, we are grounded
            if (velocity.y < 0) isGrounded = true
            velocity.y = 0f
        }


        val it = entities.iterator()
        while (it.hasNext()) {
            val entity = it.next()
            entity.update(this)

            // Calculate distance to player's feet
            val playerFeetY = cameraPos.y - 1.6f
            val dist = org.joml.Vector3f(cameraPos.x, playerFeetY, cameraPos.z).distance(entity.pos)

            if (dist < 0.5f && entity is DroppedItem) {
                if (inventory.addItem(entity.type)) { // Uses the new Inventory class logic
                    it.remove()
                    println("Picked up: ${entity.type.name}")
                }
            }
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

        window = glfwCreateWindow(1280, 720, "Kubold", NULL, NULL)
        if (window == NULL) throw RuntimeException("Failed to create the GLFW window")

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)

        GL.createCapabilities()

        textureAtlasID = generateProceduralTexture()

        glClearColor(0.5f, 0.8f, 1.0f, 1.0f)
        glEnable(GL_DEPTH_TEST)
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


        itemVao = setupItemVao()

        setupUI()

        // --- Mouse Movement Callback ---
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

        // --- Mouse Click Callback (Updated for 3D Chunks) ---
        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (action == GLFW_PRESS && selectedBlock != null) {
                val result = selectedBlock!!
                // FIX: Pass Y coordinate to find the correct vertical chunk
                val targetChunk = getChunkAt(
                    result.blockPos.x.toFloat(),
                    result.blockPos.y.toFloat(),
                    result.blockPos.z.toFloat()
                )

                if (targetChunk != null) {
                    // Get local coordinates inside the 16x16x16 chunk
                    val lx = (result.blockPos.x % 16 + 16) % 16
                    val ly = (result.blockPos.y % 16 + 16) % 16 // Local Y fix
                    val lz = (result.blockPos.z % 16 + 16) % 16

                    if (button == GLFW_MOUSE_BUTTON_LEFT) {
                        val blockId = targetChunk.getBlock(lx, ly, lz)
                        if (blockId != BlockType.BEDROCK.id) {
                            val type = BlockType.getById(blockId)
                            targetChunk.setBlock(lx, ly, lz, BlockType.AIR)
                            buildChunkMesh(targetChunk)

                            // Spawn the item at the center of the broken block
                            entities.add(DroppedItem(org.joml.Vector3f(
                                result.blockPos.x + 0.5f,
                                result.blockPos.y + 0.5f,
                                result.blockPos.z + 0.5f
                            ), type))
                        }
                    } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                        val placePos = org.joml.Vector3i(result.blockPos).add(result.faceNormal)
                        // FIX: Pass Y coordinate for placement chunk lookup
                        val placeChunk = getChunkAt(
                            placePos.x.toFloat(),
                            placePos.y.toFloat(),
                            placePos.z.toFloat()
                        )
                        if (placeChunk != null) {
                            val plx = (placePos.x % 16 + 16) % 16
                            val ply = (placePos.y % 16 + 16) % 16 // Local Y fix
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


    private fun getChunkAt(x: Float, y: Float, z: Float): Chunk? {
        val cx = floor(x / 16.0).toInt()
        val cy = floor(y / 16.0).toInt()
        val cz = floor(z / 16.0).toInt()
        return chunks[Triple(cx, cy, cz)]
    }

    // Update your existing isSolid to check the correct chunk
    private fun isSolid(x: Float, y: Float, z: Float): Boolean {
        val chunk = getChunkAt(x, y, z) ?: return false

        val lx = (floor(x).toInt() % 16 + 16) % 16
        val ly = (floor(y).toInt() % 16 + 16) % 16
        val lz = (floor(z).toInt() % 16 + 16) % 16

        return chunk.getBlock(lx, ly, lz) != BlockType.AIR.id
    }

    private fun checkCollisionPlayer(pos: org.joml.Vector3f): Boolean {
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


    fun checkCollisionAt(pos: org.joml.Vector3f, width: Float, height: Float): Boolean {
        // Check corners of the bounding box
        for (xOffset in listOf(-width, width)) {
            for (zOffset in listOf(-width, width)) {
                for (yOffset in listOf(0f, height)) {
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
        val playerCX = floor(cameraPos.x / 16.0).toInt()
        val playerCZ = floor(cameraPos.z / 16.0).toInt()

        for (x in -renderDistance..renderDistance) {
            for (z in -renderDistance..renderDistance) {
                // Stack 16 chunks vertically for every horizontal coordinate
                for (y in 0 until 16) {
                    val targetPos = Triple(playerCX + x, y, playerCZ + z)
                    if (!chunks.containsKey(targetPos)) {
                        val newChunk = Chunk(targetPos.first, targetPos.second, targetPos.third)
                        buildChunkMesh(newChunk)
                        chunks[targetPos] = newChunk
                    }
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
                    tx == 3 && ty == 0 -> {                             // Bedrock
                        val darkNoise = (Math.random() * 30).toInt()
                        r = (20 + darkNoise).toByte()
                        g = (20 + darkNoise).toByte()
                        b = (25 + darkNoise).toByte()
                    }
                    tx == 0 && ty == 1 -> {
                        val barkNoise = (Math.random() * 15).toInt()
                        r = (60 + barkNoise).toByte()
                        g = (40 + barkNoise).toByte()
                        b = (30 + barkNoise).toByte()
                    }
                    else -> { r = 120; g = 120; b = 120 }
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

    private fun setupMap() {
        val aspect = 1280f / 720f
        val size = 0.4f // Size of the map
        val offset = 0.05f // Margin from the edge

        // We adjust the X coordinates by dividing by the aspect ratio
        val right = (1.0f - offset) / aspect
        val left = (1.0f - offset - size) / aspect
        val top = 1.0f - offset
        val bottom = 1.0f - offset - size

        val vertices = floatArrayOf(
            left,  top,    0f,   0f, 1f,
            left,  bottom, 0f,   0f, 0f,
            right, bottom, 0f,   1f, 0f,

            left,  top,    0f,   0f, 1f,
            right, bottom, 0f,   1f, 0f,
            right, top,    0f,   1f, 1f
        )

        mapVao = glGenVertexArrays()
        glBindVertexArray(mapVao)
        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        val stride = 5 * 4
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12L)
        glEnableVertexAttribArray(1)
    }

    private fun setupItemVao(): Int {
        // 8 floats per vertex: X, Y, Z, U, V, NX, NY, NZ
        val v = floatArrayOf(
            // Front face (Z+)
            0f, 0f, 1f,  0f, 0f,  0f, 0f, 1f,   1f, 0f, 1f,  1f, 0f,  0f, 0f, 1f,
            1f, 1f, 1f,  1f, 1f,  0f, 0f, 1f,   1f, 1f, 1f,  1f, 1f,  0f, 0f, 1f,
            0f, 1f, 1f,  0f, 1f,  0f, 0f, 1f,   0f, 0f, 1f,  0f, 0f,  0f, 0f, 1f,

            // Back face (Z-)
            0f, 0f, 0f,  0f, 0f,  0f, 0f,-1f,   0f, 1f, 0f,  0f, 1f,  0f, 0f,-1f,
            1f, 1f, 0f,  1f, 1f,  0f, 0f,-1f,   1f, 1f, 0f,  1f, 1f,  0f, 0f,-1f,
            1f, 0f, 0f,  1f, 0f,  0f, 0f,-1f,   0f, 0f, 0f,  0f, 0f,  0f, 0f,-1f,

            // Left face (X-)
            0f, 1f, 1f,  1f, 1f, -1f, 0f, 0f,   0f, 1f, 0f,  0f, 1f, -1f, 0f, 0f,
            0f, 0f, 0f,  0f, 0f, -1f, 0f, 0f,   0f, 0f, 0f,  0f, 0f, -1f, 0f, 0f,
            0f, 0f, 1f,  1f, 0f, -1f, 0f, 0f,   0f, 1f, 1f,  1f, 1f, -1f, 0f, 0f,

            // Right face (X+)
            1f, 1f, 1f,  1f, 1f,  1f, 0f, 0f,   1f, 0f, 1f,  1f, 0f,  1f, 0f, 0f,
            1f, 0f, 0f,  0f, 0f,  1f, 0f, 0f,   1f, 0f, 0f,  0f, 0f,  1f, 0f, 0f,
            1f, 1f, 0f,  0f, 1f,  1f, 0f, 0f,   1f, 1f, 1f,  1f, 1f,  1f, 0f, 0f,

            // Top face (Y+)
            0f, 1f, 0f,  0f, 1f,  0f, 1f, 0f,   0f, 1f, 1f,  0f, 0f,  0f, 1f, 0f,
            1f, 1f, 1f,  1f, 0f,  0f, 1f, 0f,   1f, 1f, 1f,  1f, 0f,  0f, 1f, 0f,
            1f, 1f, 0f,  1f, 1f,  0f, 1f, 0f,   0f, 1f, 0f,  0f, 1f,  0f, 1f, 0f,

            // Bottom face (Y-)
            0f, 0f, 0f,  0f, 1f,  0f,-1f, 0f,   1f, 0f, 0f,  1f, 1f,  0f,-1f, 0f,
            1f, 0f, 1f,  1f, 0f,  0f,-1f, 0f,   1f, 0f, 1f,  1f, 0f,  0f,-1f, 0f,
            0f, 0f, 1f,  0f, 0f,  0f,-1f, 0f,   0f, 0f, 0f,  0f, 1f,  0f,-1f, 0f
        )

        val vao = glGenVertexArrays()
        glBindVertexArray(vao)
        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, v, GL_STATIC_DRAW)

        val stride = 8 * 4
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12L)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 20L)
        glEnableVertexAttribArray(2)

        return vao
    }


    private fun renderInventory(stack: java.nio.FloatBuffer) {
        glDisable(GL_DEPTH_TEST)
        val modelLoc = glGetUniformLocation(shaderProgram, "model")
        val uvOffsetLoc = glGetUniformLocation(shaderProgram, "uvOffset")
        glUniform1i(glGetUniformLocation(shaderProgram, "isEntity"), 1)

        val startX = 400f
        val startY = 300f
        val slotSize = 50f

        // 1. Render Main Inventory (9x3)
        for (i in 0 until 27) {
            val row = i / 9
            val col = i % 9
            val x = startX + col * slotSize
            val y = startY + row * slotSize

            drawItemInSlot(inventory.main[i], x, y, stack, modelLoc, uvOffsetLoc)
        }

        // 2. Render Hotbar (9x1) - positioned lower
        val hotbarY = 650f
        for (i in 0 until 9) {
            val x = startX + i * slotSize
            drawItemInSlot(inventory.hotbar[i], x, hotbarY, stack, modelLoc, uvOffsetLoc)
        }
    }

    private fun drawItemInSlot(item: ItemStack?, x: Float, y: Float, stack: java.nio.FloatBuffer, modelLoc: Int, uvOffsetLoc: Int) {
        val type = item?.type ?: return
        val modelMatrix = org.joml.Matrix4f()
            .translation(x, y, 0f)
            .rotate(Math.toRadians(20.0).toFloat(), 1f, 0f, 0f)
            .rotate(Math.toRadians(45.0).toFloat(), 0f, 1f, 0f)
            .scale(25f) // Adjust size for slots

        glUniformMatrix4fv(modelLoc, false, modelMatrix.get(stack))
        glUniform2f(uvOffsetLoc, type.texX.toFloat(), type.texY.toFloat())
        glBindVertexArray(itemVao)
        glDrawArrays(GL_TRIANGLES, 0, 36) // Assuming cube VAO
    }


    private fun cleanup() {
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}
