package to.grindelf.stratadiver

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_BACK
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
import org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glLineWidth
import org.lwjgl.opengl.GL11.glPixelStorei
import org.lwjgl.opengl.GL11.glReadBuffer
import org.lwjgl.opengl.GL11.glReadPixels
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL11.glTexSubImage2D
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.glBindVertexArray
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
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
 * The entry point, and the game.
 *
 * This file is both: the JVM entry class is `to.grindelf.stratadiver.StrataDiverKt`, so
 * there is no separate Main to hunt for and no second place to look to learn what the
 * game is.
 */
fun main() {
    StrataDiver().run()
}

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
        /**
         * The frame's stages, in the order they happen, for [FrameTimes].
         *
         * Split where the splits are real rather than where they would be tidy. `trace` and
         * `film` are separate because they used to be invisible to each other: the film was
         * costing 40 ms a frame entirely outside the window the resolution controller was
         * measuring, and the only reason anyone found out is that these two are now two
         * numbers instead of one.
         */
        val STAGES = listOf("world", "snd", "trace", "film", "present", "gui")

        const val STAGE_WORLD = 0
        const val STAGE_SOUND = 1
        const val STAGE_TRACE = 2
        const val STAGE_FILM = 3
        const val STAGE_PRESENT = 4
        const val STAGE_GUI = 5
        /** The clock is frozen. The sun and moon still move; time of day just does not pass. */
        const val CONST_TIME: Boolean = true

        const val QUICK_DAY_SPEED: Float = 0.0174f
        const val MINECRAFT_DAY_SPEED: Float = 0.0052f
        const val DAY_SPEED: Float = QUICK_DAY_SPEED

        /**
         * The window's size in *points*, and how much framebuffer each point carries.
         *
         * Everything the game draws is positioned in framebuffer pixels, and on a Retina
         * display that is not the same number. A 1280x720 window on this machine has a
         * 2560x1440 framebuffer, so a GUI laid out in 1280x720 coordinates occupies the
         * bottom-left quarter of the screen while the world fills all of it — every
         * element correct in itself and the whole HUD in the wrong place.
         *
         * So the two numbers are kept apart deliberately: [WINDOW_WIDTH] is what is asked
         * for, and [screenWidth]/[screenHeight] are what the display actually gave, read
         * from the driver after the context exists. Guessing the ratio is the mistake —
         * moving the game to a non-Retina monitor, or to a Mac with a different one, would
         * silently move the HUD again.
         */
        private const val WINDOW_WIDTH = 1280f
        private const val WINDOW_HEIGHT = 720f

        private const val MOUSE_SENSITIVITY = 0.1f

        /** The Minimap's disc, and its inset from the screen's top-right corner. */
        private const val MINIMAP_DIAMETER = 208f
        private const val MINIMAP_MARGIN = 12f

        /** How close a Dropped Item must get to the player's feet to be collected. */
        private const val PICKUP_RADIUS = 0.5f

        /**
         * How much slower the path tracer renders than the window is, before it starts
         * adapting on its own.
         *
         * A path tracer is not a thing you can render at any resolution at any speed; it is
         * a thing you render at whatever resolution the machine can afford and average until
         * it is good enough. Two is a reasonable middle: soft shadows and colour bleed are
         * low-frequency and survive being traced small, while the 16x16 art is re-sampled
         * per ray and does not care about the resolution at all.
         */
        private const val DEFAULT_PATH_SCALE = 2

        /** What the adaptive resolution aims for, per frame, in milliseconds. */
        private const val PATH_BUDGET_MS = 55.0

        /**
     * How far the adaptive resolution is allowed to wander, in either direction. */
        private const val PATH_SCALE_LIMIT = 8

        /** How many frames of tracing stand between two decisions about the resolution. */
        private const val SCALE_DECISION_FRAMES = 24

        /**
         * The lens.
         *
         * One number, stated once, because the rasterizer's projection matrix and the
         * tracer's ray generator are two descriptions of the same lens and nothing is
         * watching them to notice a disagreement.
         */
        private const val FOV_DEGREES = 70f

        private const val MAX_STACK = 64

        /**
         * Seconds of walking per footstep — one human stride. Multiplied by the distance
         * covered in that time, so it is a stride rather than a speed.
         */
        private const val STRIDE_SECONDS = 0.75f

        /** The frame rate the stride length is calculated against. */
        private const val NOMINAL_FPS = 60f
    }

    // --- GL objects ---
    private var window: Long = NULL
    private lateinit var shader: ShaderProgram
    private var atlasTexture: Int = 0

    // --- The game's parts ---
    private val streamer = ChunkStreamer()
    private lateinit var player: Player
    private lateinit var ui: UiPass
    private lateinit var sound: SoundEngine
    private val fps = FpsCounter()
    private val frameTimes = FrameTimes(STAGES)
    private lateinit var layout: SlotLayout

    /**
     * The framebuffer's real size in pixels, read from the driver once the context exists.
     *
     * Not the window size, and not the requested size: the number the GPU actually has.
     */
    private var screenWidth = WINDOW_WIDTH
    private var screenHeight = WINDOW_HEIGHT

    // --- Walking ---
    //
    // Footsteps are triggered by ground covered, never by frames or by a timer: a timer
    // drifts out of step with the actual stride and speeds up when the player does.
    private var stepDistance = 0f

    /**
     * How much ground the player covers between footsteps.
     *
     * Derived from [Player.WALK_SPEED] and the nominal frame rate rather than written as a
     * magic number, so that changing the walk speed does not silently turn the footsteps
     * into either a machine gun or a slow march. A human stride is roughly 0.75 Cells.
     */
    private val strideLength: Float =
        Player.WALK_SPEED * NOMINAL_FPS * STRIDE_SECONDS

    /** The fall distance at which a landing reaches full volume. */
    private val landingFullVolume = 12f

    // --- Camera ---
    //
    // No projection or view matrix. The rasterizer needed both, and keeping them after it
    // went meant a second description of the lens that nothing consulted — the exact
    // arrangement FOV_DEGREES was introduced to prevent, except that the guard had been
    // deleted along with the thing it was guarding. The tracer generates rays directly and
    // reads [FOV_DEGREES]; the GUI has its own orthographic projection inside UiPass.

    // --- State ---
    private val inventory = Inventory()
    private val entities = mutableListOf<Entity>()
    private var inventoryOpen = false
    private var selectedSlot = 0

    /** The stack the cursor is carrying between slots while a GUI layer is open. */
    private var cursorItem: ItemStack? = null

    /** The Minimap's model: Surfaces, colours and freshness. No GL of its own. */
    private lateinit var minimap: Minimap
    private var minimapTexture = 0

    // --- Path tracing ---

    /**
     * The renderer. Pure Kotlin, no GL: it takes a camera and a world and produces an
     * average of what light does when it is allowed to travel.
     */
    private lateinit var tracer: PathTracer

    /** Puts that average on the screen. */
    private lateinit var tracedImage: TracedImage

    /** The tracer's resolution divisor. See [DEFAULT_PATH_SCALE]. */
    private var pathScale = DEFAULT_PATH_SCALE

    /**
     * What the last frame of tracing cost, and the running average of that.
     *
     * The scale adapts on this because a path tracer has no frame budget to spend by
     * default — it will happily spend 400ms a frame and look wonderful and be unplayable.
     * Adapting on a measured cost rather than a guessed one is the difference between this
     * working on a laptop and this working only on the machine it was written on.
     */
    private var traceMillis = 0.0
    private var traceMillisAveraged = 0.0
    private var traceSamplesAveraged = 0
    private var lastTraceVersion = -1
    private var lastTraceEntities = -1
    private val lastEye = Vector3f()
    private val lastLook = Vector3f()
    private var haveLastEye = false

    /**
     * How bright the day is, decided once a frame by the game and read by both renderers.
     *
     * Floored rather than allowed to reach zero: a night in this world is dim, not a void,
     * and a renderer that took the floor to zero would be obeying an instruction nobody
     * meant to give it.
     */
    private var daylight = 1f

    /**
     * How much of the map's own world to generate per frame.
     *
     * A 64-block disc reaches five Chunks across and sixteen layers deep — around 400 of
     * them, and each runs Perlin noise over 4096 Cells. Asking for all of it in one frame
     * is a visible hitch at spawn. A handful per frame turns it into something the player
     * cannot notice, and because it is nearest-first the middle fills in first: which is
     * the part being looked at.
     */
    private val minimapChunkBudget = 12

    /** The Cell the player is looking at this frame, and the face they entered it through. */
    private var aimedAt: RaycastResult? = null

    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var firstMouse = true

    private val controlMode: ControlMode
        get() = if (inventoryOpen) ControlMode.INTERACTIVE else ControlMode.GAMEPLAY

    /**
     * Runs the game for [frames] frames and writes a PNG of the last one, then returns.
     *
     * This is a rendering test rig, and it exists because of the project's oldest lesson:
     * "it compiles" is not verification, and for a renderer the only real verification is
     * looking at the picture. Every claim this renderer has ever made — that a shaft lets
     * the Sun through, that leaves are see-through, that the HUD draws its own art — was
     * checked by a person looking at a window, which is not available on demand and does
     * not scale to a change of this size.
     *
     * Reading the framebuffer back is not a screenshot in the operating system's sense:
     * it is the default framebuffer, after the GUI has been drawn over the picture, so what
     * lands in the file is exactly what a player would have seen on that frame. Which is
     * the entire point — it is the one question no offline check can answer on its own,
     * and having it answerable in a second rather than in a playtest is worth more than the
     * elegance of asserting on numbers instead.
     */
    fun capture(frames: Int, path: String) {
        init()
        player.spawnAboveGround(5f, 5f)
        streamer.update(player.position)

        for (i in 0 until frames) {
            val time = if (CONST_TIME) 2f else glfwGetTime().toFloat() * DAY_SPEED
            val sunDir = Vector3f(0.5f, sin(time.toDouble()).toFloat(), cos(time.toDouble()).toFloat())
            daylight = max(0.05f, sunDir.y)
            glClearColor(0.5f * daylight, 0.8f * daylight, daylight + 0.1f, 1.0f)
            readInput()
            tick()
            aimedAt = player.raycast()
            drawFrame(sunDir)

            // Read before the swap, not after. After a swap the back buffer's contents are
            // undefined — the first capture came back with a stale composite of two frames,
            // which looked exactly like a renderer bug and was entirely the harness's.
            if (i == frames - 1) writePng(path)
            glfwSwapBuffers(window)
            glfwPollEvents()
            if (i % 10 == 0) println("  frame $i of $frames")
        }

        println("wrote $path")
        cleanup()
    }

    /** Reads the default framebuffer back and writes it as a PNG. */
    private fun writePng(path: String) {
        val w = screenWidth.toInt()
        val h = screenHeight.toInt()
        val pixels = ByteBuffer.allocateDirect(w * h * 4)
            .order(ByteOrder.nativeOrder())
        glFinish()
        glReadBuffer(GL_BACK)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels)

        // GL reads bottom-up and PNG is top-down.
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val from = ((h - 1 - y) * w + x) * 4
                val r = pixels.get(from).toInt() and 0xFF
                val g = pixels.get(from + 1).toInt() and 0xFF
                val b = pixels.get(from + 2).toInt() and 0xFF
                image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        ImageIO.write(image, "png", File(path))
    }

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

        // The size the GPU actually gave us, which on a Retina display is not the size that
        // was asked for. Everything below is laid out in these pixels: the GUI, the traced
        // image's stretch, and the viewport. Read after the context exists, because before
        // it there is no framebuffer to ask.
        val fw = IntArray(1)
        val fh = IntArray(1)
        glfwGetFramebufferSize(window, fw, fh)
        screenWidth = fw[0].toFloat()
        screenHeight = fh[0].toFloat()
        glViewport(0, 0, fw[0], fh[0])
        println("framebuffer ${fw[0]}x${fh[0]} (window asked for ${WINDOW_WIDTH.toInt()}x${WINDOW_HEIGHT.toInt()})")

        layout = SlotLayout(screenWidth, screenHeight)

        glEnable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        shader = ShaderProgram(
            "src/main/resources/shaders/ui.vert",
            "src/main/resources/shaders/ui.frag"
        )
        atlasTexture = uploadAtlas()
        tracer = PathTracer(streamer, Art.buildAtlasPixels())
        tracedImage = TracedImage(screenWidth, screenHeight)
        minimap = Minimap(streamer)
        minimapTexture = uploadMinimapTexture()
        ui = UiPass(shader, atlasTexture, screenWidth, screenHeight)
        player = Player(streamer)

        // Audio is optional by design: a failure here is logged and the game runs quietly.
        sound = SoundEngine()
        sound.start()

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

        // An unattended game that keeps making noise is genuinely irritating, and the game
        // is not simulating anything the player can see. Stopped and silenced rather than
        // suspended, because LWJGL binds no resume for the suspend call — so this is the
        // muting fallback rather than the device suspension originally preferred.
        glfwSetWindowFocusCallback(window) { _, focused ->
            sound.isPaused = !focused
        }
    }

    /**
     * The map's texture: one pixel per Cell, rewritten in place as the world changes.
     *
     * Uploaded once here with its storage, then updated with `glTexSubImage2D` rather than
     * reallocated — the minimap is redrawn several times a second for the life of the
     * process, and reallocating it each time would make that a per-frame allocation for
     * no reason.
     *
     * NEAREST on both filters, deliberately. The disc is 208px across and the texture is
     * 64, so linear filtering would interpolate between Cells that do not touch and draw
     * terrain that does not exist — at 2.25 screen pixels per Cell, blur is a lie.
     */
    private fun uploadMinimapTexture(): Int {
        val id = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, id)
        glTexImage2D(
            GL_TEXTURE_2D, 0, GL_RGBA, minimap.cells, minimap.cells, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, minimap.toRgbaBytes()
        )
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        // Clamp, not repeat: at the disc's edge the corners are discarded anyway, and a
        // repeat wrap would smear one edge into the other.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return id
    }

    /** Pushes the current Surfaces to the GPU, in place. */
    private fun uploadMinimapPixels() {
        glBindTexture(GL_TEXTURE_2D, minimapTexture)
        glTexSubImage2D(
            GL_TEXTURE_2D, 0, 0, 0, minimap.cells, minimap.cells,
            GL_RGBA, GL_UNSIGNED_BYTE, minimap.toRgbaBytes()
        )
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
            frameTimes.begin()

            val time = if (CONST_TIME) 2f else glfwGetTime().toFloat() * DAY_SPEED
            val sunDir = Vector3f(0.5f, sin(time.toDouble()).toFloat(), cos(time.toDouble()).toFloat())

            // Sky brightness follows the sun, floored so night is dim rather than a void.
            daylight = max(0.05f, sunDir.y)
            glClearColor(0.5f * daylight, 0.8f * daylight, daylight + 0.1f, 1.0f)

            readInput()
            tick()
            aimedAt = player.raycast()
            frameTimes.stage(STAGE_WORLD)

            // The listener follows the eyes, so a block broken to the left is heard from
            // the left rather than from inside the player's skull.
            sound.updateListener(player.position, player.front)
            sound.isGuiOpen = controlMode == ControlMode.INTERACTIVE
            frameTimes.stage(STAGE_SOUND)

            drawFrame(sunDir)

            glfwSwapBuffers(window)
            glfwPollEvents()
            frameTimes.end()
        }
    }

    /** One step of the world: the player moves, Chunks stream, items fall and get collected. */
    private fun tick() {
        val before = Vector3f(player.position)
        player.step()
        streamer.update(player.position)
        updateMinimap()
        updateFootsteps(before)
        updateLanding()

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
                // The item's own Material, not the block it came from: grass Drops dirt,
                // and what you pick up is dirt.
                entity.type.material?.let {
                    sound.play(Occasion.PICKUP, it, entity.pos, gain = 0.7f)
                }
            }
        }
    }

    /**
     * Plays a footstep every [strideLength] of ground covered, while grounded and moving.
     *
     * Measured on horizontal movement only — climbing and falling should not change how
     * often the player's feet fall, and a stair climb is silent per-step for the same
     * reason.
     */
    private fun updateFootsteps(before: Vector3f) {
        if (!player.isGrounded) {
            stepDistance = 0f
            return
        }

        val dx = player.position.x - before.x
        val dz = player.position.z - before.z
        stepDistance += kotlin.math.sqrt(dx * dx + dz * dz)

        if (stepDistance >= strideLength) {
            // Carry the remainder, so a slow drift into a step does not fire one late and
            // then immediately fire another to catch up.
            stepDistance -= strideLength
            materialUnderfoot()?.let { sound.play(Occasion.FOOTSTEP, it, gain = 0.45f) }
        }
    }

    private fun updateLanding() {
        // The threshold lives in Player, beside the physics that produces the wobble it
        // suppresses — see Player.MIN_FALL_TO_LAND.
        val fall = player.consumeLanding() ?: return

        // Louder and lower for a longer fall, so a ten-block drop reads differently from
        // stepping off a kerb without needing a second buffer.
        val intensity = (fall / landingFullVolume).coerceIn(0f, 1f)
        materialUnderfoot()?.let {
            sound.play(
                Occasion.LANDING, it,
                gain = 0.5f + 0.5f * intensity,
                pitch = 1f - 0.15f * intensity
            )
        }
    }

    /** The Material of the Cell under the player's feet, or null if there is no floor. */
    private fun materialUnderfoot(): Material? {
        val y = kotlin.math.floor(player.position.y - Player.HEIGHT).toInt()
        val x = kotlin.math.floor(player.position.x).toInt()
        val z = kotlin.math.floor(player.position.z).toInt()
        return streamer.blockAt(x, y, z).material
    }

    /**
     * Brings the Minimap up to date, and tells the world to make whatever it is missing.
     *
     * Generation happens here rather than inside the map because the map is the thing that
     * needs it: it reaches far past the streamer's vertical band, so a full-height read of
     * a 64-block disc requires Chunks the streamer will never ask for on its own.
     */
    private fun updateMinimap() {
        val centreX = kotlin.math.floor(player.position.x).toInt()
        val centreZ = kotlin.math.floor(player.position.z).toInt()

        val complete = streamer.ensureSurfaceKnown(
            centreX, centreZ,
            Minimap.coverageRadius(minimap.cells),
            minimapChunkBudget
        )

        // Only draw once the map's whole area is known. Rebuilding into a partly-generated
        // world every frame would mean uploading a picture that is mostly Unknown, and
        // would keep the texture busy for as long as the player stands near a frontier.
        // The disc stays as last drawn until there is something new worth showing.
        if (!complete) return

        // Reported rather than diffed: hashing 4096 pixels every frame to notice a change
        // that a flag already knows about is work for nothing.
        if (minimap.refreshIfDue(player.position, System.nanoTime())) uploadMinimapPixels()
    }

    private fun drawFrame(sunDir: Vector3f) {
        drawTracedWorld(sunDir)
        frameTimes.stage(STAGE_PRESENT)

        // The GUI goes last and flat, over the picture.
        Hud.minimap(ui, minimapTexture, minimap, player, MINIMAP_DIAMETER, MINIMAP_MARGIN)
        Hud.crosshair(ui)
        fps.render(ui)
        frameTimes.render(ui, 10f, 56f, 220f)
        Hud.hotbar(ui, layout, inventory, selectedSlot)
        if (inventoryOpen) {
            Hud.inventory(ui, layout, inventory)
            cursorItem?.let { Hud.heldItem(ui, it, cursorX(), cursorY()) }
        }
        frameTimes.stage(STAGE_GUI)
    }

    // --- Path traced ---

    /**
     * The world, as the average of a great many rays finding their way through it.
     *
     * Three things happen here that have nothing to do with tracing and everything to do with
     * the fact that this is a *progressive* renderer, and each of them is a way of being
     * wrong that produces a plausible picture rather than an obvious failure:
     *
     *  - The average is thrown away whenever the eye moves or the world changes. Keeping it
     *    would produce a sharp, converged, confidently wrong picture — the failure mode this
     *    whole renderer is least well defended against, because it looks like success.
     *  - The resolution adapts to a *measured* cost. Nothing here is tuned to a machine.
     *  - Everything is drawn by the tracer, including the dropped items and the wireframe.
     *    There is no depth buffer behind the picture for them to sort against, so a hybrid
     *    would be guessing, and a guess about occlusion is exactly the bug that costs a day.
     */
    private fun drawTracedWorld(sunDir: Vector3f) {
        val width = (screenWidth / pathScale).toInt().coerceAtLeast(16)
        val height = (screenHeight / pathScale).toInt().coerceAtLeast(16)

        val scene = TraceScene(
            Camera(player.position, player.front, player.up, FOV_DEGREES, screenWidth / screenHeight),
            sunDir,
            daylight,
            entities,
            aimedAt?.blockPos
        )

        if (sceneHasMoved()) {
            tracer.reset()
            lastEye.set(player.position)
            lastLook.set(player.front)
            lastTraceVersion = streamer.version
            lastTraceEntities = entities.size
            haveLastEye = true
            traceSamplesAveraged = 0
        }
        tracer.resize(width, height)

        val started = System.nanoTime()
        tracer.sample(scene)
        traceMillis = (System.nanoTime() - started) / 1e6
        adaptPathScale()
        frameTimes.stage(STAGE_TRACE)

        tracedImage.update(tracer.rgba(), tracer.width, tracer.height)
        frameTimes.stage(STAGE_FILM)

        tracedImage.draw()
    }

    /**
     * Whether the average on the screen describes a scene that still exists.
     *
     * Compared rather than inferred, because every alternative here is a guess about when
     * something changed, and a guess that is wrong in the direction of "nothing changed"
     * shows up as a picture that is subtly smeared and never sharpens — which looks exactly
     * like a renderer merely converging slowly, and is the failure this renderer is least
     * well defended against.
     */
    private fun sceneHasMoved(): Boolean {
        if (!haveLastEye) return true
        if (streamer.version != lastTraceVersion) return true
        // An item appearing or being collected changes what is in the world. An item
        // *spinning* is deliberately not in this list: it changes every frame, so including
        // it would mean the average never gets past one sample while anything lies on the
        // ground. The cost of leaving it out is a faint trail behind a dropped item, which is
        // a far smaller price than never converging.
        if (entities.size != lastTraceEntities) return true
        if (Vector3f(player.position).sub(lastEye).length() > 1e-4f) return true
        if (Vector3f(player.front).sub(lastLook).length() > 1e-4f) return true
        return false
    }

    /**
     * Trades resolution for frame time, on measurement.
     *
     * Averaged over [SCALE_DECISION_FRAMES] frames rather than judged on the last one. A
     * single frame is not a measurement of anything: the first after a reset is cold and the
     * last before one is cheap, so deciding on either moves the resolution for the wrong
     * reason and then oscillates — a picture that is sharp one frame and coarse the next,
     * which is worse than either.
     */
    private fun adaptPathScale() {
        traceMillisAveraged = if (traceSamplesAveraged == 0) traceMillis
        else traceMillisAveraged * 0.85 + traceMillis * 0.15
        traceSamplesAveraged++
        if (traceSamplesAveraged < SCALE_DECISION_FRAMES) return

        val moving = when {
            traceMillisAveraged > PATH_BUDGET_MS -> 1
            traceMillisAveraged < PATH_BUDGET_MS / 3 -> -1
            else -> 0
        }
        traceSamplesAveraged = 0

        if (moving != 0) {
            val wanted = (pathScale + moving).coerceIn(1, PATH_SCALE_LIMIT)
            if (wanted != pathScale) {
                pathScale = wanted
                // resize() discards the average, so the picture restarts at the new size
                // rather than being stretched from the old one.
                tracer.resize((screenWidth / pathScale).toInt().coerceAtLeast(16),
                    (screenHeight / pathScale).toInt().coerceAtLeast(16))
            }
        }
    }

    // --- Rasterized ---

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
        // The world changed under the Minimap. Marking it here rather than leaving the map
        // to poll is what makes "break a block, see it on the map" immediate instead of
        // up to a quarter-second late.
        minimap.invalidate()

        // Inside the function, after the bedrock guard, so striking the unbreakable floor
        // is silent rather than a thud for something that did not happen. Positioned,
        // because where you broke something is part of what you heard.
        type.material?.let {
            sound.play(
                Occasion.BREAK, it,
                Vector3f(block.x + 0.5f, block.y + 0.5f, block.z + 0.5f)
            )
        }

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

        val type = inventory.hotbar[selectedSlot]?.type ?: return // Nothing in hand, nothing placed
        streamer.setBlock(placePos.x, placePos.y, placePos.z, type)
        minimap.invalidate()

        // After the "would seal the player inside" guard: a refused placement made no sound.
        type.material?.let {
            sound.play(
                Occasion.PLACE, it,
                Vector3f(placePos.x + 0.5f, placePos.y + 0.5f, placePos.z + 0.5f)
            )
        }
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
        // Nothing to release: the world's Cells are never discarded and hold no GPU memory,
        // because there are no meshes.
        tracedImage.release()
        ui.release()
        sound.stop()
        glfwDestroyWindow(window)
        glfwTerminate()
    }
}
