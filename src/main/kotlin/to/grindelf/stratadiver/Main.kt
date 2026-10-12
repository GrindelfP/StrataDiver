package to.grindelf.stratadiver

import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glDeleteBuffers
import org.lwjgl.opengl.GL30.glDeleteVertexArrays
import org.lwjgl.opengl.GL30.glGenVertexArrays
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil.NULL
import java.io.File
import kotlin.math.floor


fun main() {
    StrataDiver().run()
}

enum class ControlMode {
    GAMEPLAY,
    INTERACTIVE
}

class StrataDiver {

    companion object {
        const val CONST_TIME: Boolean = true
        const val QUICK_DAY_SPEED: Float = 0.0174f
        const val MINECRAFT_DAY_SPEED: Float = 0.0052f
        const val DAY_SPEED: Float = QUICK_DAY_SPEED

        // The world is 16 stacked Chunks of 16 Cells, from Y=0 to Y=256.
        const val WORLD_HEIGHT_CHUNKS: Int = 16
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

    /**
     * The subset of [chunks] that currently holds GPU memory.
     *
     * Everything else has had its mesh released but keeps its Cells. This is the
     * reason frame cost stays flat as you explore: only what is near the player
     * is ever drawn, and leaving a Chunk behind genuinely frees it instead of
     * quietly growing an unbounded backlog that gets submitted every frame.
     */
    private val meshedChunks = mutableSetOf<Chunk>()

    private val renderDistance = 4 // Chunks in every direction, horizontally

    // Vertical reach. Far smaller than the world's 16 layers because terrain lives
    // in a narrow altitude band — loading all of them was most of the cost for no
    // visible gain.
    private val verticalRenderDistance = 3

    // Streaming budget. Meshing is the expensive part of crossing a chunk
    // boundary, so only this many per frame: the gap at the edge of vision fills
    // in over a couple of frames instead of the game hitching.
    private val maxChunkMeshesPerFrame = 8

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
    private var heldItem: ItemStack? = null
    private val hotbar = arrayOfNulls<BlockType>(9) // What is in the 9 slots

    // CONTROL ROUTING
    // Input is split into two modes. GAMEPLAY owns WASD, the camera and world
    // interaction. INTERACTIVE owns cursor-driven GUI interaction and is active
    // whenever any GUI layer (inventory now, pause menu later) is open. Every
    // input callback routes through this property so gameplay and menu bindings
    // can never leak into each other.
    private val controlMode: ControlMode
        get() = if (isInventoryOpen) ControlMode.INTERACTIVE else ControlMode.GAMEPLAY

    // TEXTURES
    private var textureAtlasID: Int = 0

    // MINIMAP
    private var mapVao: Int = 0

    // UI QUAD (unit quad for 2D GUI rendering)
    private var uiQuadVao: Int = 0

    // ENTITIES
    private val entities = mutableListOf<Entity>()
    private var itemVao: Int = 0 // Create a simple 1x1 cube VAO for items in init()


    fun run() {
        init()
        // Place the player *before* the first streaming pass. Streaming works from
        // the camera's chunk position, so streaming first would mesh the band
        // around the default spawn height and then immediately throw all of it away.
        safeSpawn()
        updateChunks()
        setupSelectionBox()
        setupMap()
        loop()
        cleanup()
    }

    private fun safeSpawn() {
        val spawnX = 5f
        val spawnZ = 5f
        var spawnY = 130f

        for (y in 255 downTo 0) {
            if (isSolid(spawnX, y.toFloat(), spawnZ)) {
                spawnY = y.toFloat() + 2f
                break
            }
        }
        cameraPos.set(spawnX, spawnY, spawnZ)

        // Push up if spawned inside blocks (trees, steep terrain)
        var attempts = 0
        while (checkCollisionPlayer(cameraPos) && attempts < 20) {
            cameraPos.y += 1f
            attempts++
        }
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
            glUniform1i(glGetUniformLocation(shaderProgram, "isEntity"), 0)
            val modelMatrix = org.joml.Matrix4f()
            glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, modelMatrix.get(stack))

            // Only the meshed neighbourhood is drawn — never every Chunk the player
            // has ever visited. This is the loop that used to grow without bound.
            for (chunk in meshedChunks) {
                if (chunk.vertexCount == 0) continue
                glBindVertexArray(chunk.vao)
                glDrawArrays(GL_TRIANGLES, 0, chunk.vertexCount)
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
                // Unlit white: the wireframe must not be shaded by the sun or discarded.
                glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 1)
                glUniform3f(glGetUniformLocation(shaderProgram, "tint"), 1f, 1f, 1f)
                glBindVertexArray(selectionVao)
                glDrawArrays(GL_LINES, 0, 24)
                glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 0)
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

            // --- 11. RENDER UI ---
            // The crosshair is drawn in the orthographic GUI pass below, not in the world
            // pass: a world-space quad pinned to the camera sits exactly on the near
            // plane and gets clipped, which is why it never appeared.
            glEnable(GL_DEPTH_TEST)

            renderCrosshair(stack)
            renderFps(stack)

            // 12. RENDER UI (Hotbar always, Inventory when open)
            renderHotbar(stack)
            if (isInventoryOpen) {
                renderInventory(stack)
                renderHeldItem(stack)
            }

            // 13. Finalize Frame
            glfwSwapBuffers(window)
            glfwPollEvents()
        }
    }

    private fun processInput() {
        when (controlMode) {
            ControlMode.GAMEPLAY -> processGameplayInput()
            ControlMode.INTERACTIVE -> processInteractiveInput()
        }
    }

    private fun processGameplayInput() {
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

    private fun processInteractiveInput() {
        // WASD / jump are unassigned while a GUI layer is open; freeze the player.
        velocity.x = 0f
        velocity.z = 0f
    }

    private fun handleGameplayKey(key: Int) {
        // Hotbar selection with 1-9 keys
        if (key in GLFW_KEY_1..GLFW_KEY_9) {
            selectedSlot = key - GLFW_KEY_1
        }
        // Open a GUI layer -> switch to interactive mode
        if (key == GLFW_KEY_E) enterInteractiveLayer()
    }

    private fun handleInteractiveKey(key: Int) {
        // Close the GUI layer -> return to gameplay mode
        if (key == GLFW_KEY_E) exitInteractiveLayer()
    }

    private fun enterInteractiveLayer() {
        isInventoryOpen = true
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)
        // Avoid a camera jump when the cursor is recaptured later
        firstMouse = true
    }

    private fun exitInteractiveLayer() {
        isInventoryOpen = false
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)
        firstMouse = true
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

        window = glfwCreateWindow(1280, 720, "StrataDiver", NULL, NULL)
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
        uiQuadVao = setupUIQuad()

        setupUI()

        // --- Key Callback (routed by control mode) ---
        glfwSetKeyCallback(window) { _, key, _, action, _ ->
            if (action != GLFW_PRESS) return@glfwSetKeyCallback
            when (controlMode) {
                ControlMode.GAMEPLAY -> handleGameplayKey(key)
                ControlMode.INTERACTIVE -> handleInteractiveKey(key)
            }
        }

        // --- Mouse Movement Callback ---
        glfwSetCursorPosCallback(window) { _, xpos, ypos ->
            if (controlMode != ControlMode.GAMEPLAY) return@glfwSetCursorPosCallback
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

        // --- Mouse Click Callback (routed by control mode) ---
        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (action != GLFW_PRESS) return@glfwSetMouseButtonCallback
            when (controlMode) {
                ControlMode.GAMEPLAY -> handleGameplayClick(button)
                ControlMode.INTERACTIVE -> handleInventoryClick(button)
            }
        }
    }

    private fun handleGameplayClick(button: Int) {
        if (selectedBlock == null) return
        val result = selectedBlock!!
        // Pass Y coordinate to find the correct vertical chunk
        val targetChunk = getOrCreateChunk(
            result.blockPos.x.toFloat(),
            result.blockPos.y.toFloat(),
            result.blockPos.z.toFloat()
        )

        if (targetChunk != null) {
            // Get local coordinates inside the 16x16x16 chunk
            val lx = (result.blockPos.x % 16 + 16) % 16
            val ly = (result.blockPos.y % 16 + 16) % 16
            val lz = (result.blockPos.z % 16 + 16) % 16

            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                val blockId = targetChunk.getBlock(lx, ly, lz)
                if (blockId != BlockType.BEDROCK.id) {
                    val type = BlockType.getById(blockId)
                    targetChunk.setBlock(lx, ly, lz, BlockType.AIR)
                    buildChunkMesh(targetChunk)

                    // A Block Type decides what it Drops: grass Drops dirt, leaves Drop
                    // nothing (AIR), and everything else Drops itself.
                    val drop = type.drop
                    if (drop != BlockType.AIR) {
                        entities.add(DroppedItem(org.joml.Vector3f(
                            result.blockPos.x + 0.5f,
                            result.blockPos.y + 0.5f,
                            result.blockPos.z + 0.5f
                        ), drop))
                    }
                }
            } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                val placePos = org.joml.Vector3i(result.blockPos).add(result.faceNormal)
                // Prevent placing a block inside the player
                val playerMinX = cameraPos.x - playerWidth
                val playerMaxX = cameraPos.x + playerWidth
                val playerMinY = cameraPos.y - 1.6f
                val playerMaxY = cameraPos.y + 0.2f
                val playerMinZ = cameraPos.z - playerWidth
                val playerMaxZ = cameraPos.z + playerWidth
                val blockMinX = placePos.x.toFloat()
                val blockMaxX = placePos.x.toFloat() + 1f
                val blockMinY = placePos.y.toFloat()
                val blockMaxY = placePos.y.toFloat() + 1f
                val blockMinZ = placePos.z.toFloat()
                val blockMaxZ = placePos.z.toFloat() + 1f
                val intersectsPlayer = playerMaxX > blockMinX && playerMinX < blockMaxX &&
                        playerMaxY > blockMinY && playerMinY < blockMaxY &&
                        playerMaxZ > blockMinZ && playerMinZ < blockMaxZ
                if (!intersectsPlayer) {
                    val placeChunk = getOrCreateChunk(
                        placePos.x.toFloat(),
                        placePos.y.toFloat(),
                        placePos.z.toFloat()
                    )
                    if (placeChunk != null) {
                        val plx = (placePos.x % 16 + 16) % 16
                        val ply = (placePos.y % 16 + 16) % 16
                        val plz = (placePos.z % 16 + 16) % 16
                        val placeType = inventory.hotbar[selectedSlot]?.type ?: BlockType.STONE
                        placeChunk.setBlock(plx, ply, plz, placeType)
                        buildChunkMesh(placeChunk)
                    }
                }
            }
        }
    }

    private fun setupUI() {
        // A quad showing the crosshair tile. The tile is a cutout, so the transparent
        // pixels are discarded and only the cross itself is drawn.
        val size = 0.035f
        val t = Atlas.CROSSHAIR
        val u0 = t.tx / Atlas.TILES.toFloat()
        val v0 = t.ty / Atlas.TILES.toFloat()
        val u1 = (t.tx + 1) / Atlas.TILES.toFloat()
        val v1 = (t.ty + 1) / Atlas.TILES.toFloat()

        val vertices = floatArrayOf(
            -size, -size, 0f, u0, v0,
             size, -size, 0f, u1, v0,
             size,  size, 0f, u1, v1,
            -size, -size, 0f, u0, v0,
             size,  size, 0f, u1, v1,
            -size,  size, 0f, u0, v1
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


    /**
     * The Chunk containing this position, generated on first touch.
     *
     * Returning null means only "outside the world" — never "not loaded yet".
     * That distinction matters: with a finite vertical band, a missing Chunk used
     * to read as open air, which would let physics and the raycast see straight
     * through ground the streamer simply had not reached. Generation is
     * deterministic, so a Chunk born here is identical to one the streamer builds
     * moments later — the player can never stand on terrain that reshapes after.
     */
    private fun getOrCreateChunk(x: Float, y: Float, z: Float): Chunk? {
        val cx = floor(x / Chunk.SIZE).toInt()
        val cy = floor(y / Chunk.SIZE).toInt()
        val cz = floor(z / Chunk.SIZE).toInt()
        if (cy < 0 || cy >= WORLD_HEIGHT_CHUNKS) return null
        return chunks.getOrPut(Triple(cx, cy, cz)) { Chunk(cx, cy, cz) }
    }

    // Update your existing isSolid to check the correct chunk
    private fun isSolid(x: Float, y: Float, z: Float): Boolean {
        val chunk = getOrCreateChunk(x, y, z) ?: return false

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



    // UV of the centre of Atlas.LINE, used by the selection wireframe. The old 0.9,0.9
    // pointed into a tile that is now a cutout, so every fragment was discarded.
    private val LINE_U = 0.5f / Atlas.TILES
    private val LINE_V = 3.5f / Atlas.TILES

    private fun setupSelectionBox() {
        // 5 floats per vertex: X, Y, Z, U, V
        val vertices = floatArrayOf(
            // Bottom square
            0f,0f,0f, LINE_U,LINE_V,  1f,0f,0f, LINE_U,LINE_V,
            1f,0f,0f, LINE_U,LINE_V,  1f,0f,1f, LINE_U,LINE_V,
            1f,0f,1f, LINE_U,LINE_V,  0f,0f,1f, LINE_U,LINE_V,
            0f,0f,1f, LINE_U,LINE_V,  0f,0f,0f, LINE_U,LINE_V,

            // Top square
            0f,1f,0f, LINE_U,LINE_V,  1f,1f,0f, LINE_U,LINE_V,
            1f,1f,0f, LINE_U,LINE_V,  1f,1f,1f, LINE_U,LINE_V,
            1f,1f,1f, LINE_U,LINE_V,  0f,1f,1f, LINE_U,LINE_V,
            0f,1f,1f, LINE_U,LINE_V,  0f,1f,0f, LINE_U,LINE_V,

            // Vertical pillars
            0f,0f,0f, LINE_U,LINE_V,  0f,1f,0f, LINE_U,LINE_V,
            1f,0f,0f, LINE_U,LINE_V,  1f,1f,0f, LINE_U,LINE_V,
            1f,0f,1f, LINE_U,LINE_V,  1f,1f,1f, LINE_U,LINE_V,
            0f,0f,1f, LINE_U,LINE_V,  0f,1f,1f, LINE_U,LINE_V
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


    /**
     * Brings the meshed neighbourhood back in line with where the player is standing.
     *
     * Releasing is unconditional and immediate; building is rationed. Together they
     * keep both the GPU footprint and the per-frame draw list bounded by the render
     * distance rather than by how far the player has ever walked.
     */
    private fun updateChunks() {
        val playerCX = floor(cameraPos.x / Chunk.SIZE).toInt()
        val playerCY = floor(cameraPos.y / Chunk.SIZE).toInt()
        val playerCZ = floor(cameraPos.z / Chunk.SIZE).toInt()

        // Everything within reach right now.
        val wanted = HashSet<Triple<Int, Int, Int>>()
        for (x in -renderDistance..renderDistance) {
            for (z in -renderDistance..renderDistance) {
                for (y in -verticalRenderDistance..verticalRenderDistance) {
                    val cy = playerCY + y
                    if (cy < 0 || cy >= WORLD_HEIGHT_CHUNKS) continue
                    wanted.add(Triple(playerCX + x, cy, playerCZ + z))
                }
            }
        }

        // Release what fell out of reach. Its Cells stay in `chunks`.
        val iterator = meshedChunks.iterator()
        while (iterator.hasNext()) {
            val c = iterator.next()
            if (c.position !in wanted) {
                c.releaseMesh()
                iterator.remove()
            }
        }

        // Build what came into reach, nearest first so the world closes in around
        // the player rather than filling in from the far edge inward.
        //
        // `meshedPositions` is snapshotted rather than querying the set per wanted
        // Chunk: with ~570 in reach, scanning the set each time would be a few
        // hundred thousand comparisons every single frame.
        val meshedPositions = HashSet<Triple<Int, Int, Int>>(meshedChunks.size * 2)
        for (c in meshedChunks) meshedPositions.add(c.position)

        var built = 0
        val missing = wanted
            .filter { it !in meshedPositions }
            .sortedBy { (cx, cy, cz) ->
                val dx = cx - playerCX
                val dy = cy - playerCY
                val dz = cz - playerCZ
                dx * dx + dy * dy + dz * dz
            }
        for (pos in missing) {
            if (built >= maxChunkMeshesPerFrame) break
            val c = chunks.getOrPut(pos) { Chunk(pos.first, pos.second, pos.third) }
            buildChunkMesh(c)
            meshedChunks.add(c)
            built++
        }
    }

    private fun buildChunkMesh(c: Chunk) {
        // Release the previous mesh first — this also clears vertexCount, so it has
        // to happen before the new count is computed, not after.
        c.releaseMesh()

        val vertices = MeshGenerator.generateChunkMesh(c)
        // IMPORTANT: Now using 8 floats per vertex (Pos:3, UV:2, Normal:3)
        c.vertexCount = vertices.size / 8

        // Most of the world is air — most of the vertical band above the terrain is
        // nothing but. An all-air Chunk has no geometry at all, so it gets no GPU
        // object and no draw call; it is still recorded as meshed so the streamer
        // does not rebuild it on every single frame.
        if (c.vertexCount == 0) {
            c.isMeshGenerated = true
            meshedChunks.add(c)
            return
        }

        c.vao = glGenVertexArrays()
        glBindVertexArray(c.vao)
        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)
        c.vbo = vbo

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
        // Register here rather than at the call sites: breaking or placing a block
        // also rebuilds the mesh, and a mesh nobody tracks would be drawn as nothing
        // and never freed.
        meshedChunks.add(c)
    }


    private fun generateProceduralTexture(): Int {
        val atlasSize = Atlas.TILES * Atlas.TILE_PX
        val buffer = Art.buildAtlas()

        val id = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, id)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, atlasSize, atlasSize, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer)

        // Mipmapped minification kills the shimmer of 16px art seen at distance. The art
        // generator bleeds each tile's outer pixels into a guard ring precisely so this
        // is safe to do inside an atlas.
        glGenerateMipmap(GL_TEXTURE_2D)

        // NEAREST magnification keeps the chunky, unsmoothed look; the mipmap chain only
        // kicks in once a tile is smaller than a screen pixel.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        return id
    }

    private fun setupUIQuad(): Int {
        val vertices = floatArrayOf(
            -0.5f, -0.5f, 0f,  0f, 0f,
             0.5f, -0.5f, 0f,  1f, 0f,
             0.5f,  0.5f, 0f,  1f, 1f,
            -0.5f, -0.5f, 0f,  0f, 0f,
             0.5f,  0.5f, 0f,  1f, 1f,
            -0.5f,  0.5f, 0f,  0f, 1f
        )

        val vao = glGenVertexArrays()
        glBindVertexArray(vao)
        val vbo = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        val stride = 5 * 4
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12L)
        glEnableVertexAttribArray(1)

        return vao
    }

    private fun drawUIQuad(
        stack: java.nio.FloatBuffer,
        x: Float, y: Float, w: Float, h: Float,
        tile: TileRef,
        shaderProgram: Int,
        tint: Float = 1f
    ) {
        val modelLoc = glGetUniformLocation(shaderProgram, "model")
        val uvOffsetLoc = glGetUniformLocation(shaderProgram, "uvOffset")
        val modelMatrix = org.joml.Matrix4f()
            .translate(x + w / 2f, y + h / 2f, 0f)
            .scale(w, h, 1f)
        glUniformMatrix4fv(modelLoc, false, modelMatrix.get(stack))
        glUniform2f(uvOffsetLoc, tile.tx.toFloat(), tile.ty.toFloat())
        glUniform3f(glGetUniformLocation(shaderProgram, "tint"), tint, tint, tint)
        glBindVertexArray(uiQuadVao)
        glDrawArrays(GL_TRIANGLES, 0, 6)
    }

    /**
     * Draws an isometric Icon for [type], assembled from three of its own tiles — top,
     * north and east — each shaded with its own brightness so the three faces stay
     * distinguishable. No atlas space is spent per block: every block gets an Icon free.
     */
    private fun drawBlockIcon(
        stack: java.nio.FloatBuffer,
        x: Float, y: Float, size: Float,
        type: BlockType,
        shaderProgram: Int
    ) {
        val modelLoc = glGetUniformLocation(shaderProgram, "model")
        val uvOffsetLoc = glGetUniformLocation(shaderProgram, "uvOffset")

        /**
         * Maps the unit quad onto the parallelogram p0-p1-?-p3, where the fourth corner
         * is p1 + p3 - p0. The unit quad's vertices are
         * (-.5,-.5) (.5,-.5) (.5,.5) (-.5,.5), so p0/p1/p3 are the corners opposite
         * that final diagonal vertex.
         */
        fun face(
            p0x: Float, p0y: Float, p1x: Float, p1y: Float, p3x: Float, p3y: Float,
            tile: TileRef, brightness: Float
        ) {
            val a = p1x - p0x; val b = p1y - p0y
            val c = p3x - p0x; val d = p3y - p0y
            val e = (p1x + p3x) / 2f
            val f = (p1y + p3y) / 2f

            // JOML's set(column, row, value): x' = m00*x + m10*y + m30, y' = m01*x + m11*y + m31.
            // So the x-row (column 0) carries (a, b) and the y-row (column 1) carries (c, d).
            val m = org.joml.Matrix4f()
            m.set(0, 0, a); m.set(0, 1, b)
            m.set(1, 0, c); m.set(1, 1, d)
            m.set(3, 0, e); m.set(3, 1, f)
            glUniformMatrix4fv(modelLoc, false, m.get(stack))
            glUniform2f(uvOffsetLoc, tile.tx.toFloat(), tile.ty.toFloat())
            glUniform3f(glGetUniformLocation(shaderProgram, "tint"), brightness, brightness, brightness)
            glBindVertexArray(uiQuadVao)
            glDrawArrays(GL_TRIANGLES, 0, 6)
        }

        // Classic 2:1 isometric cube. The silhouette is deliberately taller than the
        // old 2:1 face proportions: a cube's Icon should read as a cube at 40px,
        // so the two side faces are given roughly a third of the height each.
        val w = size
        val h = size / 4f        // vertical half-diagonal of the top diamond
        val sideH = size * 0.55f // height of the side faces
        val totalH = 2f * h + sideH
        val cx = x + size / 2f
        val cy = y + (size - totalH) / 2f + h // centre the silhouette in the box
        val left = cx - w / 2f
        val right = cx + w / 2f

        // Left (north) face: top-left -> top-right, hanging down.
        face(left, cy, cx, cy + h, left, cy + sideH, type.tile(Face.NORTH), 0.72f)
        // Right (east) face, mirrored.
        face(cx, cy + h, right, cy, cx, cy + h + sideH, type.tile(Face.EAST), 0.55f)
        // Top face, the diamond: bottom corner first so the fourth vertex is the apex.
        face(cx, cy + h, right, cy, left, cy, type.tile(Face.TOP), 1.0f)
    }

    /**
     * Screen-centre crosshair, drawn in the orthographic GUI pass. The crosshair tile is
     * a cutout, so only the cross itself is opaque and the rest is discarded.
     */
    private fun renderCrosshair(stack: java.nio.FloatBuffer) {
        val size = 20f
        val cx = 1280f / 2f
        val cy = 720f / 2f

        glDisable(GL_DEPTH_TEST)
        glUniformMatrix4fv(
            glGetUniformLocation(shaderProgram, "projection"), false,
            org.joml.Matrix4f().ortho(0f, 1280f, 720f, 0f, -1f, 1f).get(stack)
        )
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, org.joml.Matrix4f().get(stack))
        glUniform1i(glGetUniformLocation(shaderProgram, "isEntity"), 1)
        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 1)
        drawUIQuad(stack, cx - size / 2f, cy - size / 2f, size, size, Atlas.CROSSHAIR, shaderProgram)
        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 0)
        glEnable(GL_DEPTH_TEST)
    }

    /** Rolling average frames per second, for the HUD readout. */
    private val frameTimes = FloatArray(30)
    private var frameTimeIndex = 0
    private var frameTimeCount = 0
    private var lastFrameNanos: Long = 0L
    private var displayedFps = 0f
    private var lastFpsUpdateNanos = 0L

    // How often the readout refreshes. The average is sampled every frame, but a number
    // changing 60+ times a second is unreadable, so it is only redrawn at this interval.
    private val fpsRefreshMs = 250L

    /**
     * Samples the frame time every frame but only returns a new value every
     * [FPS_REFRESH_MS] milliseconds, so the digits stay legible.
     */
    private fun updateFps(): Float {
        val now = System.nanoTime()
        if (lastFrameNanos != 0L) {
            val dt = (now - lastFrameNanos) / 1_000_000_000f
            frameTimes[frameTimeIndex] = dt
            frameTimeIndex = (frameTimeIndex + 1) % frameTimes.size
            if (frameTimeCount < frameTimes.size) frameTimeCount++
        }
        lastFrameNanos = now

        if (now - lastFpsUpdateNanos >= fpsRefreshMs * 1_000_000L) {
            if (frameTimeCount > 0) {
                var sum = 0f
                for (i in 0 until frameTimeCount) sum += frameTimes[i]
                val avg = sum / frameTimeCount
                displayedFps = if (avg > 0f) 1f / avg else 0f
            }
            lastFpsUpdateNanos = now
        }
        return displayedFps
    }

    /**
     * FPS readout, top-left. Drawn as seven-segment digits assembled from solid quads,
     * so it needs no font and no extra atlas tiles.
     */
    private fun renderFps(stack: java.nio.FloatBuffer) {
        val fps = updateFps()
        if (fps <= 0f) return
        val text = fps.toInt().toString()
        val digits = if (text.length >= 2) text else "0$text"

        val digitW = 22f
        val digitH = 32f
        val spacing = 6f
        var x = 10f
        val y = 10f

        glDisable(GL_DEPTH_TEST)
        glUniformMatrix4fv(
            glGetUniformLocation(shaderProgram, "projection"), false,
            org.joml.Matrix4f().ortho(0f, 1280f, 720f, 0f, -1f, 1f).get(stack)
        )
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, org.joml.Matrix4f().get(stack))
        glUniform1i(glGetUniformLocation(shaderProgram, "isEntity"), 1)
        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 1)

        for (ch in digits) {
            if (ch == ' ') {
                x += digitW + spacing
                continue
            }
            drawSevenSegmentDigit(stack, x, y, digitW, digitH, ch - '0')
            x += digitW + spacing
        }

        // "FPS" label, three letters drawn from the same segment font.
        var lx = x + spacing
        for (ch in "FPS") {
            drawLetter(stack, lx, y + digitH - 12f, digitW, 12f, ch)
            lx += digitW + spacing
        }

        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 0)
        glEnable(GL_DEPTH_TEST)
    }

    // Seven-segment layout:
    //
    //     aaa
    //    f   b
    //    f   b
    //     ggg
    //    e   c
    //    e   c
    //     ddd
    //
    // Written as segment names rather than bitmasks. Hand-written masks were wrong for
    // nine of the ten digits, which is exactly the class of bug naming makes obvious.
    private val SEGMENT_DIGITS = arrayOf(
        "abcdef",  // 0
        "bc",      // 1
        "abdeg",   // 2
        "abcdg",   // 3
        "bcfg",    // 4
        "acdfg",   // 5
        "acdefg",  // 6
        "abc",     // 7
        "abcdefg", // 8
        "abcdfg"   // 9
    )

    /** Bitmask for [digit], with bit 6 = segment a, down to bit 0 = segment g. */
    private fun segmentMask(digit: Int): Int {
        val name = SEGMENT_DIGITS[digit]
        var mask = 0
        for (ch in name) {
            val index = "abcdefg".indexOf(ch)
            mask = mask or (1 shl (6 - index))
        }
        return mask
    }

    private fun drawSevenSegmentDigit(
        stack: java.nio.FloatBuffer, x: Float, y: Float, w: Float, h: Float, digit: Int
    ) {
        if (digit !in 0..9) return
        val seg = segmentMask(digit)
        val t = (h * 0.14f).coerceAtLeast(2f) // segment thickness
        val half = (h - t) / 2f

        // [x, y, w, h] per segment a..g
        val bars = arrayOf(
            floatArrayOf(x + t, y, w - 2 * t, t),                 // a top
            floatArrayOf(x + w - t, y + t, t, half - t),          // b upper right
            floatArrayOf(x + w - t, y + half + t, t, half - t),   // c lower right
            floatArrayOf(x + t, y + h - t, w - 2 * t, t),         // d bottom
            floatArrayOf(x, y + half + t, t, half - t),           // e lower left
            floatArrayOf(x, y + t, t, half - t),                  // f upper left
            floatArrayOf(x + t, y + h / 2f - t / 2f, w - 2 * t, t) // g middle
        )
        for (i in bars.indices) {
            if ((seg shr (6 - i)) and 1 == 0) continue
            val b = bars[i]
            drawUIQuad(stack, b[0], b[1], b[2], b[3], Atlas.LINE, shaderProgram, 0.85f)
        }
    }

    /** Minimal letterforms for the FPS label, built from bars. */
    private fun drawLetter(
        stack: java.nio.FloatBuffer, x: Float, y: Float, w: Float, h: Float, ch: Char
    ) {
        val t = (h * 0.22f).coerceAtLeast(1.5f)
        fun bar(bx: Float, by: Float, bw: Float, bh: Float) =
            drawUIQuad(stack, x + bx, y + by, bw, bh, Atlas.LINE, shaderProgram, 0.7f)

        when (ch) {
            'F' -> {
                bar(0f, 0f, t, h); bar(0f, 0f, w, t); bar(0f, h / 2f, w - t, t)
            }
            'P' -> {
                bar(0f, 0f, t, h); bar(0f, 0f, w - t, t); bar(w - t, 0f, t, h / 2f)
                bar(0f, h / 2f - t / 2f, w - t, t)
            }
            'S' -> {
                bar(t, 0f, w - 2 * t, t); bar(0f, t, t, h / 2f - t)
                bar(t, h / 2f - t / 2f, w - 2 * t, t)
                bar(w - t, h / 2f, t, h / 2f - t); bar(t, h - t, w - 2 * t, t)
            }
            else -> Unit
        }
    }

    private fun renderHotbar(stack: java.nio.FloatBuffer) {
        val slotSize = 50f
        val totalWidth = 9 * slotSize
        val startX = (1280f - totalWidth) / 2f
        val startY = 720f - slotSize - 10f

        glDisable(GL_DEPTH_TEST)
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, org.joml.Matrix4f().ortho(0f, 1280f, 720f, 0f, -1f, 1f).get(stack))
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, org.joml.Matrix4f().get(stack))
        glUniform1i(glGetUniformLocation(shaderProgram, "isEntity"), 1)
        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 1)

        for (i in 0 until 9) {
            val x = startX + i * slotSize
            drawUIQuad(stack, x, startY, slotSize, slotSize, Atlas.SLOT, shaderProgram)
            if (i == selectedSlot) {
                drawUIQuad(stack, x, startY, slotSize, slotSize, Atlas.SLOT_SELECTED, shaderProgram)
            }
            val item = inventory.hotbar[i]
            if (item != null) {
                drawBlockIcon(stack, x + 5f, startY + 5f, slotSize - 10f, item.type, shaderProgram)
            }
        }

        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 0)
        glEnable(GL_DEPTH_TEST)
    }

    private fun handleInventoryClick(button: Int) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) return

        val mx = doubleArrayOf(0.0)
        val my = doubleArrayOf(0.0)
        glfwGetCursorPos(window, mx, my)
        val mouseX = mx[0].toFloat()
        val mouseY = my[0].toFloat()
        val slotSize = 50f
        val totalWidth = 9 * slotSize
        val startX = (1280f - totalWidth) / 2f
        // The 27-slot grid sits above the always-on hotbar, like in Minecraft
        val mainStartY = 720f - slotSize - 10f - 3 * slotSize - 20f
        val hotbarY = 720f - slotSize - 10f

        for (i in 0 until 27) {
            val row = i / 9
            val col = i % 9
            val x = startX + col * slotSize
            val y = mainStartY + row * slotSize
            if (mouseX in x..(x + slotSize) && mouseY in y..(y + slotSize)) {
                handleSlotClick(inventory.main, i)
                return
            }
        }

        for (i in 0 until 9) {
            val x = startX + i * slotSize
            if (mouseX in x..(x + slotSize) && mouseY in hotbarY..(hotbarY + slotSize)) {
                handleSlotClick(inventory.hotbar, i)
                return
            }
        }
    }

    private fun handleSlotClick(slots: Array<ItemStack?>, index: Int) {
        if (heldItem == null) {
            heldItem = slots[index]
            slots[index] = null
        } else {
            val existing = slots[index]
            if (existing == null) {
                slots[index] = heldItem
                heldItem = null
            } else if (existing.type == heldItem!!.type && existing.count < 64) {
                val total = existing.count + heldItem!!.count
                val toAdd = minOf(total, 64 - existing.count)
                slots[index] = ItemStack(existing.type, existing.count + toAdd)
                val remaining = total - existing.count - toAdd
                heldItem = if (remaining > 0) ItemStack(heldItem!!.type, remaining) else null
            }
        }
    }

    private fun renderHeldItem(stack: java.nio.FloatBuffer) {
        val item = heldItem ?: return
        val mx = doubleArrayOf(0.0)
        val my = doubleArrayOf(0.0)
        glfwGetCursorPos(window, mx, my)
        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 1)
        drawBlockIcon(stack, mx[0].toFloat() - 20f, my[0].toFloat() - 20f, 40f, item.type, shaderProgram)
        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 0)
    }

    private fun renderInventory(stack: java.nio.FloatBuffer) {
        val slotSize = 50f
        val totalWidth = 9 * slotSize
        val startX = (1280f - totalWidth) / 2f
        // The 27-slot grid sits above the always-on hotbar, like in Minecraft
        val mainStartY = 720f - slotSize - 10f - 3 * slotSize - 20f

        glDisable(GL_DEPTH_TEST)
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, org.joml.Matrix4f().ortho(0f, 1280f, 720f, 0f, -1f, 1f).get(stack))
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, org.joml.Matrix4f().get(stack))
        glUniform1i(glGetUniformLocation(shaderProgram, "isEntity"), 1)
        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 1)

        // Panel behind the grid only — stops above the always-on hotbar so the hotbar stays visible.
        // The panel is lighter than the slots, which are lighter than a stone Icon, so every
        // block reads as a distinct shape rather than dissolving into the background.
        val hotbarY = 720f - slotSize - 10f
        val panelBottom = hotbarY - 10f
        drawUIQuad(stack, startX - 30f, mainStartY - 20f, totalWidth + 60f, panelBottom - mainStartY + 20f, Atlas.PANEL, shaderProgram)

        for (i in 0 until 27) {
            val row = i / 9
            val col = i % 9
            val x = startX + col * slotSize
            val y = mainStartY + row * slotSize
            drawUIQuad(stack, x, y, slotSize, slotSize, Atlas.SLOT, shaderProgram)
            val item = inventory.main[i]
            if (item != null) {
                drawBlockIcon(stack, x + 5f, y + 5f, slotSize - 10f, item.type, shaderProgram)
            }
        }

        glUniform1i(glGetUniformLocation(shaderProgram, "isGui"), 0)
        glEnable(GL_DEPTH_TEST)
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


    private fun cleanup() {
        // Release every live mesh before the context dies, so a normal exit does
        // not leave the driver holding the world's vertex buffers.
        meshedChunks.forEach { it.releaseMesh() }
        meshedChunks.clear()
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}
