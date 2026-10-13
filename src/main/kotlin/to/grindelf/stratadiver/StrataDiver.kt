package to.grindelf.stratadiver

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_FALSE
import org.lwjgl.opengl.GL11.GL_LINES
import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_LINEAR
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glLineWidth
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glBindVertexArray
import org.lwjgl.opengl.GL30.glGenerateMipmap
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Which layer of the game is taking input.
 *
 * GAMEPLAY owns movement, the camera and interaction with the world. INTERACTIVE owns
 * the cursor-driven GUI, and is active whenever a GUI layer is open. Every input
 * callback routes through this, so the two sets of bindings can never leak into each
 * other: W closes nothing and does not walk you while the inventory is up, but the
 * escape key still quits.
 */
enum class ControlMode { GAMEPLAY, INTERACTIVE }

/**
 * The game: it owns the window and the GL context, decides what happens each frame, and
 * wires everything else together.
 *
 * Everything with behaviour of its own lives elsewhere — [Player] moves and looks,
 * [ChunkStreamer] decides what exists and what is drawn, [ShaderProgram] talks to the
 * driver, [UiPass] draws in screen space, [Hud] and [FpsCounter] decide what the screen
 * says, [SlotLayout] says where the slots are. What is left here is the part that has to
 * be one thing: the order of a frame, and who owns the window.
 */
class StrataDiver {

    companion object {
        /** The clock is frozen. The sun and moon still move; time of day just does not pass. */
        const val CONST_TIME: Boolean = true

        const val QUICK_DAY_SPEED: Float = 0.0174f
        const val MINECRAFT_DAY_SPEED: Float = 0.0052f
        const val DAY_SPEED: Float = QUICK_DAY_SPEED

        private const val WINDOW_WIDTH = 1280f
        private const val WINDOW_HEIGHT = 720f

        private const val MOUSE_SENSITIVITY = 0.1f

        /** How close a Dropped Item must get to the player's feet to be collected. */
        private const val PICKUP_RADIUS = 0.5f

        private const val MAX_STACK = 64
    }

    // --- GL objects ---
    private var window: Long = NULL
    private lateinit var shader: ShaderProgram
    private var atlasTexture: Int = 0
    private lateinit var itemCube: Mesh
    private lateinit var selectionBox: Mesh
    private lateinit var minimap: Mesh

    // --- The game's parts ---
    private val streamer = ChunkStreamer()
    private lateinit var player: Player
    private lateinit var ui: UiPass
    private val fps = FpsCounter()
    private val layout = SlotLayout(WINDOW_WIDTH, WINDOW_HEIGHT)

    // --- Camera ---
    private val projection = Matrix4f().perspective(
        Math.toRadians(70.0).toFloat(), WINDOW_WIDTH / WINDOW_HEIGHT, 0.1f, 100f
    )
    private val view = Matrix4f()
    private val identity = Matrix4f()

    // --- State ---
    private val inventory = Inventory()
    private val entities = mutableListOf<Entity>()
    private var inventoryOpen = false
    private var selectedSlot = 0

    /** The stack the cursor is carrying between slots while a GUI layer is open. */
    private var cursorItem: ItemStack? = null

    /** The Cell the player is looking at this frame, and the face they entered it through. */
    private var aimedAt: RaycastResult? = null

    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var firstMouse = true

    private val controlMode: ControlMode
        get() = if (inventoryOpen) ControlMode.INTERACTIVE else ControlMode.GAMEPLAY

    fun run() {
        init()

        // Place the player *before* the first streaming pass. Streaming works from the
        // camera's Chunk position, so streaming first would mesh the band around the
        // default spawn height and then immediately throw all of it away.
        player.spawnAboveGround(5f, 5f)
        streamer.update(player.position)

        loop()
        cleanup()
    }

    // --- Setup ---

    private fun init() {
        if (!glfwInit()) throw IllegalStateException("Unable to initialize GLFW")

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

        window = glfwCreateWindow(1280, 720, "StrataDiver", NULL, NULL)
        if (window == NULL) throw RuntimeException("Failed to create the GLFW window")

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        glfwShowWindow(window)

        GL.createCapabilities()

        glEnable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        shader = ShaderProgram(
            "src/main/resources/shaders/vertex.glsl",
            "src/main/resources/shaders/fragment.glsl"
        )
        atlasTexture = uploadAtlas()
        itemCube = Geometry.upload(UNIT_CUBE, Geometry.POS_UV_NORMAL)!!
        selectionBox = Geometry.upload(SELECTION_BOX, Geometry.POS_UV)!!
        minimap = Geometry.upload(MINIMAP_QUAD, Geometry.POS_UV)!!
        ui = UiPass(shader, WINDOW_WIDTH, WINDOW_HEIGHT)
        player = Player(streamer)

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)
        registerCallbacks()
    }

    private fun registerCallbacks() {
        glfwSetKeyCallback(window) { _, key, _, action, _ ->
            if (action != GLFW_PRESS) return@glfwSetKeyCallback
            when (controlMode) {
                ControlMode.GAMEPLAY -> onGameplayKey(key)
                ControlMode.INTERACTIVE -> onInteractiveKey(key)
            }
        }

        glfwSetCursorPosCallback(window) { _, x, y ->
            if (controlMode != ControlMode.GAMEPLAY) return@glfwSetCursorPosCallback
            if (firstMouse) {
                lastMouseX = x
                lastMouseY = y
                firstMouse = false
            }
            // Moving the mouse right turns you right; moving it up looks up, hence the
            // inverted vertical delta.
            player.look(x - lastMouseX, lastMouseY - y, MOUSE_SENSITIVITY)
            lastMouseX = x
            lastMouseY = y
        }

        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (action != GLFW_PRESS) return@glfwSetMouseButtonCallback
            when (controlMode) {
                ControlMode.GAMEPLAY -> onGameplayClick(button)
                ControlMode.INTERACTIVE -> onInteractiveClick(button)
            }
        }
    }

    /** The Atlas, mipmapped and NEAREST-magnified. */
    private fun uploadAtlas(): Int {
        val size = Atlas.TILES * Atlas.TILE_PX

        val id = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, id)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, Art.buildAtlas())

        // Mipmapped minification kills the shimmer of 16px art seen at distance. The art
        // generator bleeds each tile's outer pixels into a guard ring precisely so this is
        // safe to do inside an atlas.
        glGenerateMipmap(GL_TEXTURE_2D)

        // NEAREST magnification keeps the chunky, unsmoothed look; the mipmap chain only
        // kicks in once a tile is smaller than a screen pixel.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

        return id
    }

    // --- The frame ---

    private fun loop() {
        while (!glfwWindowShouldClose(window)) {
            val time = if (CONST_TIME) 2f else glfwGetTime().toFloat() * DAY_SPEED
            val sunDir = Vector3f(0.5f, sin(time.toDouble()).toFloat(), cos(time.toDouble()).toFloat())
            val moonDir = Vector3f(sunDir).mul(-1f)

            // Sky brightness follows the sun, floored so night is dim rather than a void.
            val daylight = max(0.05f, sunDir.y)
            glClearColor(0.5f * daylight, 0.8f * daylight, daylight + 0.1f, 1.0f)

            readInput()
            tick()
            aimedAt = player.raycast()

            drawFrame(sunDir, moonDir)

            glfwSwapBuffers(window)
            glfwPollEvents()
        }
    }

    /** One step of the world: the player moves, Chunks stream, items fall and get collected. */
    private fun tick() {
        player.step()
        streamer.update(player.position)

        val iterator = entities.iterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            entity.update(streamer)

            if (entity is DroppedItem &&
                player.distanceToFeet(entity.pos) < PICKUP_RADIUS &&
                inventory.addItem(entity.type)
            ) {
                iterator.remove()
                println("Picked up: ${entity.type.name}")
            }
        }
    }

    private fun drawFrame(sunDir: Vector3f, moonDir: Vector3f) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        shader.use()

        // Reset the minimap for the 3D world. The minimap pass is parked — see
        // drawMinimap() — but this must be off or the whole world is drawn as a map.
        shader.setInt("isMap", 0)

        shader.setVec3("sunDir", sunDir.x, sunDir.y, sunDir.z)
        shader.setVec3("moonDir", moonDir.x, moonDir.y, moonDir.z)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, atlasTexture)
        shader.setInt("textureAtlas", 0)

        view.setLookAt(player.position, Vector3f(player.position).add(player.front), player.up)
        shader.setMatrix("projection", projection)
        shader.setMatrix("view", view)

        drawWorld()
        drawSelection()
        drawMinimap()

        // The GUI goes last and flat, once the 3D pass has handed depth testing back.
        Hud.crosshair(ui)
        fps.render(ui)
        Hud.hotbar(ui, layout, inventory, selectedSlot)
        if (inventoryOpen) {
            Hud.inventory(ui, layout, inventory)
            cursorItem?.let { Hud.heldItem(ui, it, cursorX(), cursorY()) }
        }
    }

    private fun drawWorld() {
        shader.setMatrix("model", identity)
        shader.setInt("isEntity", 0)

        // Only the meshed neighbourhood is drawn — never every Chunk the player has ever
        // visited. This is the loop that used to grow without bound.
        for (chunk in streamer.meshed) {
            // A Chunk with no mesh is fully meshed and correctly has nothing to draw: most
            // of the world above the terrain is air.
            val mesh = chunk.mesh ?: continue
            glBindVertexArray(mesh.vao)
            glDrawArrays(GL_TRIANGLES, 0, mesh.vertexCount)
        }

        EntityRenderer.draw(entities, shader, itemCube)
    }

    /** A wireframe around the Cell the player is looking at. */
    private fun drawSelection() {
        val target = aimedAt ?: return

        glLineWidth(2.0f)
        val block = target.blockPos
        shader.setMatrix(
            "model",
            Matrix4f()
                .translation(block.x.toFloat(), block.y.toFloat(), block.z.toFloat())
                .scale(1.005f) // Inflated a hair so the box does not z-fight the face
        )
        // Unlit white: the wireframe must not be shaded by the sun, and must not be
        // discarded by the alpha cutout that is what makes Cutouts work.
        shader.setInt("isGui", 1)
        shader.setTint(1f)
        glBindVertexArray(selectionBox.vao)
        glDrawArrays(GL_LINES, 0, selectionBox.vertexCount)
        shader.setInt("isGui", 0)
    }

    /** Parked. The shader still has a full `isMap` branch and the quad still exists, but
     *  nothing draws it: the minimap was switched off when the GUI moved to screen space. */
    private fun drawMinimap() {
        // shader.setInt("isMap", 1)
        // shader.setFloat("mapZoom", 50.0f)
        // shader.setVec2("playerPos", player.position.x, player.position.z)
        // glBindVertexArray(minimap.vao)
        // glDrawArrays(GL_TRIANGLES, 0, 6)
    }

    // --- Input ---

    private fun readInput() {
        when (controlMode) {
            ControlMode.GAMEPLAY -> {
                player.moveInput(
                    forward = held(GLFW_KEY_W),
                    back = held(GLFW_KEY_S),
                    left = held(GLFW_KEY_A),
                    right = held(GLFW_KEY_D),
                    jump = held(GLFW_KEY_SPACE)
                )
                if (held(GLFW_KEY_ESCAPE)) glfwSetWindowShouldClose(window, true)
            }
            // W, A, S, D and jump are unassigned while a GUI layer is open, so the player
            // is frozen outright rather than drifting on whatever they were holding.
            ControlMode.INTERACTIVE -> player.halt()
        }
    }

    private fun held(key: Int) = glfwGetKey(window, key) == GLFW_PRESS

    private fun onGameplayKey(key: Int) {
        if (key in GLFW_KEY_1..GLFW_KEY_9) selectedSlot = key - GLFW_KEY_1
        if (key == GLFW_KEY_E) openInteractiveLayer()
    }

    private fun onInteractiveKey(key: Int) {
        if (key == GLFW_KEY_E) closeInteractiveLayer()
    }

    private fun openInteractiveLayer() {
        inventoryOpen = true
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)
        firstMouse = true // Or the camera jumps when the cursor is recaptured
    }

    private fun closeInteractiveLayer() {
        inventoryOpen = false
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)
        firstMouse = true
    }

    // --- Touching the world ---

    private fun onGameplayClick(button: Int) {
        val target = aimedAt ?: return

        when (button) {
            GLFW_MOUSE_BUTTON_LEFT -> breakBlock(target.blockPos)
            GLFW_MOUSE_BUTTON_RIGHT -> placeBlock(target)
        }
    }

    private fun breakBlock(block: Vector3i) {
        val type = streamer.blockAt(block.x, block.y, block.z)
        if (type == BlockType.BEDROCK) return // The floor of the world stays put

        streamer.setBlock(block.x, block.y, block.z, BlockType.AIR)

        // A Block Type decides what it Drops: grass Drops dirt, leaves Drop nothing, and
        // everything else Drops itself.
        val drop = type.drop
        if (drop != BlockType.AIR) {
            entities.add(
                DroppedItem(
                    Vector3f(block.x + 0.5f, block.y + 0.5f, block.z + 0.5f),
                    drop
                )
            )
        }
    }

    private fun placeBlock(target: RaycastResult) {
        val placePos = Vector3i(target.blockPos).add(target.faceNormal)
        if (player.intersects(placePos)) return // Never seal the player inside a block

        val type = inventory.hotbar[selectedSlot]?.type ?: BlockType.STONE
        streamer.setBlock(placePos.x, placePos.y, placePos.z, type)
    }

    // --- Touching the inventory ---

    private fun onInteractiveClick(button: Int) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) return

        val slot = layout.slotAt(cursorX(), cursorY()) ?: return
        val slots = when (slot.container) {
            SlotContainer.MAIN -> inventory.main
            SlotContainer.HOTBAR -> inventory.hotbar
        }
        pickUp(slots, slot.index)
    }

    /**
     * The pick up / put down / merge dance of a cursor carrying one stack.
     *
     * Merging leaves the overflow on the cursor rather than dropping it, which is the
     * whole reason a cursor exists: 40 dropped on 50 has to come back as 26 somewhere.
     */
    private fun pickUp(slots: Array<ItemStack?>, index: Int) {
        val held = cursorItem
        if (held == null) {
            cursorItem = slots[index]
            slots[index] = null
            return
        }

        val existing = slots[index]
        when {
            existing == null -> {
                slots[index] = held
                cursorItem = null
            }
            existing.type == held.type && existing.count < MAX_STACK -> {
                val merged = existing.count + held.count
                val added = minOf(merged, MAX_STACK - existing.count)
                slots[index] = ItemStack(existing.type, existing.count + added)
                val overflow = merged - existing.count - added
                cursorItem = if (overflow > 0) ItemStack(held.type, overflow) else null
            }
        }
    }

    private fun cursorX(): Float {
        val out = DoubleArray(1)
        glfwGetCursorPos(window, out, null)
        return out[0].toFloat()
    }

    private fun cursorY(): Float {
        val out = DoubleArray(1)
        glfwGetCursorPos(window, null, out)
        return out[0].toFloat()
    }

    // --- Teardown ---

    private fun cleanup() {
        // Release every live mesh before the context dies, so a normal exit does not leave
        // the driver holding the world's vertex buffers.
        streamer.releaseAll()
        ui.release()
        minimap.release()
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}
