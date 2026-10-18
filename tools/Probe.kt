package probe

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_TEXTURE_3D
import org.lwjgl.opengl.GL12.glTexImage3D
import org.lwjgl.opengl.GL20.GL_SHADING_LANGUAGE_VERSION
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil

/**
 * What this machine's OpenGL can actually do, asked by doing it rather than by reading a
 * string.
 *
 * The GPU port planned in docs/plan-gpu-path-tracing.md rests on one claim: that macOS caps
 * OpenGL at 4.1 core, so there are no compute shaders and no image load/store, and the
 * tracer has to be a fragment-shader ping-pong over multiple render targets.
 *
 * That claim is load-bearing and it was made from knowledge rather than from this machine.
 * Every part of the design was chosen to be correct under *either* answer — the fragment
 * design works on 4.1 and on 4.5 — but the strict answer has a better shape available, and
 * finding that out in three days is worse than finding it out now.
 *
 * So this asks the questions the plan actually depends on, by attempting them:
 *
 *   1. What version, renderer and GLSL?
 *   2. Is there a compute shader?          (decides the tracer's architecture)
 *   3. Can a float texture be rendered into? (decides where accumulation lives)
 *   4. How many render targets?              (decides whether a G-buffer fits in one pass)
 *   5. Is there a 3D texture big enough for the streamed world?
 *   6. Is there a timer query?               (decides how the frame breakdown measures the GPU)
 *
 * A string saying "ARB_compute_shader" is worth much less than a context that either has
 * one or does not, and much less than an FBO that either completes or does not.
 *
 * Hidden window, no swap, exits on its own. Run with -XstartOnFirstThread: GLFW on macOS
 * puts its event loop and its GL context on the first thread of the process, and a JDK 23
 * main thread is not that thread.
 *
 *   tools/probe.sh
 */
fun main() {

    if (!glfwInit()) throw IllegalStateException("Unable to initialize GLFW")

    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

    // The same context the game asks for, so this reports what the game will get and not
    // what the driver could do if asked more nicely.
    val window = glfwCreateWindow(64, 64, "probe", MemoryUtil.NULL, MemoryUtil.NULL)
    if (window == MemoryUtil.NULL) throw RuntimeException("Failed to create the GLFW window")
    glfwMakeContextCurrent(window)
    GL.createCapabilities()

    println("\n== 1. What this context is ==")
    println("  GL_VERSION      ${glGetString(GL_VERSION)}")
    println("  GL_VENDOR       ${glGetString(GL_VENDOR)}")
    println("  GL_RENDERER     ${glGetString(GL_RENDERER)}")
    println("  GLSL            ${glGetString(GL_SHADING_LANGUAGE_VERSION)}")
    println("  max texture units ${glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS)}")
    println("  max 3D texture size ${glGetInteger(GL_MAX_3D_TEXTURE_SIZE)}")

    // The window's size in *points* against its size in *pixels*. On a Retina display these
    // differ, and a renderer that draws the GUI in points while the framebuffer is in pixels
    // puts every HUD element in the wrong place — a failure that looks like a GUI bug and is
    // entirely a coordinate-system one.
    val w = IntArray(1); val h = IntArray(1)
    glfwGetWindowSize(window, w, h)
    val fw = IntArray(1); val fh = IntArray(1)
    glfwGetFramebufferSize(window, fw, fh)
    println("  window ${w[0]}x${h[0]}, framebuffer ${fw[0]}x${fh[0]}")

    val extensions = extensions()

    println("\n== 2. Compute shaders — the one that decides the architecture ==")
    val compute = "GL_ARB_compute_shader" in extensions
    println("  GL_ARB_compute_shader: ${if (compute) "PRESENT" else "absent"}")
    // 0x91BE: GL_COMPUTE_SHADER_MAX_NUM_WORK_GROUPS is 4.3 and has no constant in LWJGL's
    // GL30, which is the highest this probe's imports reach. Reading it at all is the
    // point — its presence is what the number means.
    if (compute) println("  max compute work groups ${glGetInteger(0x91BE)}")

    println("\n== 3. Can a float image be rendered into? ==")
    val f32 = renderTarget(0x8814 /* GL_RGBA32F */, 0x1908 /* GL_RGBA */, 0x1401 /* GL_UNSIGNED_BYTE */)
    println("  RGBA32F colour-renderable: $f32")
    if (!f32) {
        val f16 = renderTarget(0x881A /* GL_RGBA16F */, 0x1908, 0x1401)
        println("  RGBA16F colour-renderable: $f16")
    }

    println("\n== 4. Render targets — does the G-buffer fit in one pass? ==")
    println("  MAX_COLOR_ATTACHMENTS ${glGetInteger(GL_MAX_COLOR_ATTACHMENTS)}")
    println("  MAX_DRAW_BUFFERS     ${glGetInteger(GL_MAX_DRAW_BUFFERS)}")
    val mrt = renderTargets(
        listOf(
            0x8814 to (0x1908 to 0x1401), // RGBA32F, the accumulator
            0x8814 to (0x1908 to 0x1401), // RGBA32F, albedo
            0x8814 to (0x1908 to 0x1401), // RGBA32F, normal
            0x8814 to (0x1908 to 0x1401)  // RGBA32F, depth
        )
    )
    println("  a 4-target RGBA32F framebuffer: ${if (mrt) "complete" else "INCOMPLETE"}")

    println("\n== 5. The world texture ==")
    // The plan sizes the streamed window at 144x144x112 R8. Testing the exact shape rather
    // than reading GL_MAX_3D_TEXTURE_SIZE, because the limit can be met by a 3D texture
    // that no driver will actually allocate.
    val world = threed(144, 144, 112)
    println("  144 x 144 x 112, R8 single channel: ${if (world) "created" else "FAILED"}")

    println("\n== 6. Measuring the GPU ==")
    val timer = "GL_ARB_timer_query" in extensions
    println("  GL_ARB_timer_query: ${if (timer) "PRESENT" else "absent — a fence sync will do"}")

    println("\n== Verdict ==")
    if (compute) {
        println("  Compute shaders EXIST. The plan's fragment-shader shape still works, but a")
        println("  compute-shader tracer is available and would be the better architecture:")
        println("  one pass, no ping-pong, no MRT, the accumulator is just a buffer.")
        println("  Step 5 should be re-decided against that before anything is written.")
    } else {
        println("  No compute shaders, as assumed. The fragment-shader ping-pong design stands,")
        println("  and OpenGL 4.1 has everything it needs.")
    }
    if (!f32 && !renderTarget(0x881A, 0x1908, 0x1401)) {
        println("  WARNING: no float render target at all. The accumulator would have to live")
        println("  on the CPU, which is the whole thing this plan exists to avoid.")
    }
    println()

    glfwDestroyWindow(window)
    glfwTerminate()
}

/** Every extension this context has, read the core-profile way. */
private fun extensions(): Set<String> {
    val count = glGetInteger(GL_NUM_EXTENSIONS)
    val out = HashSet<String>(count * 2)
    for (i in 0 until count) glGetStringi(GL_EXTENSIONS, i)?.let { out.add(it) }
    return out
}

/**
 * Whether a framebuffer with one [format] attachment is complete.
 *
 * Completeness rather than an extension string, because "ARB_color_buffer_float is
 * advertised" and "this exact framebuffer renders" have been different answers before, and
 * the second one is the one the tracer would find out at runtime.
 */
private fun renderTarget(internalFormat: Int, format: Int, type: Int): Boolean {
    val fbo = glGenFramebuffers()
    glBindFramebuffer(GL_FRAMEBUFFER, fbo)
    val texture = texture(internalFormat, format, type, 4, 4)
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
    glBindFramebuffer(GL_FRAMEBUFFER, 0)
    glDeleteFramebuffers(fbo)
    glDeleteTextures(texture)
    return status == GL_FRAMEBUFFER_COMPLETE
}

/** The same, for several attachments at once — which is the G-buffer's actual question. */
private fun renderTargets(formats: List<Pair<Int, Pair<Int, Int>>>): Boolean {
    val fbo = glGenFramebuffers()
    glBindFramebuffer(GL_FRAMEBUFFER, fbo)
    val textures = mutableListOf<Int>()
    formats.forEachIndexed { i, (internalFormat, ft) ->
        val texture = texture(internalFormat, ft.first, ft.second, 4, 4)
        textures.add(texture)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, texture, 0)
    }
    glDrawBuffers(
        textures.indices.map { GL_COLOR_ATTACHMENT0 + it }.toIntArray()
    )
    val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
    glBindFramebuffer(GL_FRAMEBUFFER, 0)
    glDeleteFramebuffers(fbo)
    textures.forEach { glDeleteTextures(it) }
    return status == GL_FRAMEBUFFER_COMPLETE
}

private fun texture(internalFormat: Int, format: Int, type: Int, w: Int, h: Int): Int {
    // A real buffer rather than a null pointer. LWJGL's overloads resolve `MemoryUtil.NULL`
    // to the ByteBuffer signature and fail the cast at runtime, and the pixel count it wants
    // is computed from the format/type pair rather than from the internal format — which is
    // why this is four bytes per pixel and not sixteen.
    val data = MemoryUtil.memAlloc(w * h * 4)
    val id = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, id)
    glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, data)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    MemoryUtil.memFree(data)
    return id
}

private fun threed(w: Int, h: Int, d: Int): Boolean {
    val data = MemoryUtil.memAlloc(w * h * d)
    val id = glGenTextures()
    glBindTexture(GL_TEXTURE_3D, id)
    glTexImage3D(
        GL_TEXTURE_3D, 0, 0x8229 /* GL_R8 */, w, h, d, 0,
        0x1903 /* GL_RED */, 0x1401 /* GL_UNSIGNED_BYTE */, data
    )
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    val err = glGetError()
    glDeleteTextures(id)
    MemoryUtil.memFree(data)
    return err == GL_NO_ERROR
}