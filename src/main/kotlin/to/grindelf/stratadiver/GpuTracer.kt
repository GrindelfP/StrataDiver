package to.grindelf.stratadiver

import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_NEAREST
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glBindTexture
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glGenTextures
import org.lwjgl.opengl.GL11.glTexImage2D
import org.lwjgl.opengl.GL11.glTexParameteri
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL12.GL_TEXTURE_3D
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.GL_TEXTURE1
import org.lwjgl.opengl.GL13.GL_TEXTURE2
import org.lwjgl.opengl.GL13.GL_TEXTURE3
import org.lwjgl.opengl.GL13.GL_TEXTURE4
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL30.GL_RGBA32F
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The path tracer, on the GPU.
 *
 * **Why it exists.** The CPU tracer spends about 6 microseconds a pixel-sample, which at
 * 1280x720 and 60 fps is a budget of roughly 430 cycles for an entire path — camera ray,
 * two or three bounces, and the three to six shadow rays hanging off them. There is no
 * amount of tuning inside that; it is a different machine doing a different job. The GPU
 * has been sitting completely idle throughout, and the renderer is one that spends all day
 * asking what is in a Cell.
 *
 * **Why ping-pong rather than compute.** This machine's OpenGL is 4.1 — see tools/Probe.kt,
 * which is kept so the next machine can be asked rather than assumed. Compute shaders are
 * 4.3 and do not exist on Apple's driver, so the tracer is a fullscreen pass that reads the
 * previous average and writes the next one. That is not a compromise forced by the API: at
 * this resolution the work is not ALU-starved, it is starved of somewhere to do it.
 *
 * **Where the CPU tracer went.** Nowhere. It is still the oracle, and the two agreeing on
 * the closed-form cases is now a check rather than a hope.
 *
 * **Textures.** Two RGBA32F accumulators swapped each frame, plus a stencil-free world
 * volume owned by [WorldVolume] and the Atlas. The picture leaves the accumulator as floats
 * and does not become bytes until [present], so the number the tracer produced is still a
 * thing that can be read.
 */
class GpuTracer(
    private val volume: WorldVolume,
    atlasPixels: IntArray,
    private val atlasSize: Int = Atlas.TILES * Atlas.TILE_PX
) {

    private val traceProgram = ShaderProgram(
        "src/main/resources/shaders/trace.vert",
        "src/main/resources/shaders/trace.frag"
    )
    private val accumProgram = ShaderProgram(
        "src/main/resources/shaders/fullscreen.vert",
        "src/main/resources/shaders/accum.frag"
    )
    private val presentProgram = ShaderProgram(
        "src/main/resources/shaders/fullscreen.vert",
        "src/main/resources/shaders/present.frag"
    )
    private val denoiseProgram = ShaderProgram(
        "src/main/resources/shaders/fullscreen.vert",
        "src/main/resources/shaders/denoise.frag"
    )
    private val emaProgram = ShaderProgram(
        "src/main/resources/shaders/fullscreen.vert",
        "src/main/resources/shaders/ema.frag"
    )

    /** The fullscreen quad every pass draws. Position only; UVs come from the shader. */
    private val quad = Geometry.upload(TRACE_QUAD, Geometry.POS_UV)!!

    private var width = 0
    private var height = 0

    /** The two accumulators. [read] holds the average, [write] is where the next one goes. */
    private var read = 0
    private var write = 0

    /** The framebuffer both passes render into. One target, swapped, not two. */
    private var target = 0
    private var targetTexture = 0

    /**
     * The geometry and material of the first thing the camera ray met, one frame's worth.
     *
     * Written by the trace pass alongside the sample it produced and read by the denoise
     * pass, which uses them to decide where a pixel's neighbourhood ends. A zero depth
     * means the ray met no surface at all.
     */
    private var gbufferA = 0  // normal.xyz, depth
    private var gbufferB = 0  // albedo.rgb, 1

    /** The average after the denoise pass has had it. This is what goes to the screen. */
    private var denoised = 0

    /** Temporal history buffer for EMA smoothing (read). Updated after denoise, read by present. */
    private var history = 0
    /** Ping-pong target for EMA pass to avoid read-write hazard on history texture. */
    private var historyNext = 0

    private val atlasTexture = uploadAtlas(atlasPixels)

    private val DEBUG_READBACK = java.lang.Boolean.getBoolean("stratadiver.gpuDebug")
    private val DEBUG_MODE = Integer.getInteger("stratadiver.gpuMode", 0)

    /**
     * Whether the guided denoise pass runs. On by default, and off with
     * `-Dstratadiver.noDenoise` so the raw average can be compared against the cleaned one
     * without editing anything.
     */
    private val DENOISE = !java.lang.Boolean.getBoolean("stratadiver.noDenoise")

    /** Display the raw progressive average, bypassing both denoise and temporal EMA. */
    private val RAW_TRACE = java.lang.Boolean.getBoolean("stratadiver.rawTrace")

    /**
     * How much edge to put back, as a fraction of the difference between a pixel and its
     * neighbourhood mean. Tunable with `-Dstratadiver.sharpen=N`; 0 turns it off. A fixed
     * amount rather than an adaptive one, because the image is 1:1 with the window and the
     * radius is one pixel, so there is nothing to adapt to.
     */
    private val SHARPEN = Integer.getInteger("stratadiver.sharpen", 0) / 100f

    /**
     * EMA temporal accumulation alpha. Higher = more history, less responsive.
     * Default 0.92 (~12-frame half-life). Tunable with `-Dstratadiver.temporalAlpha=N`.
     */
    private val TEMPORAL_ALPHA = System.getProperty("stratadiver.temporalAlpha", "0.92").toFloat()

    /**
     * Which colour attachments each pass writes to.
     *
     * The trace pass writes three (radiance, normal+depth, albedo) and the accumulation and
     * denoise passes write one each, and `glDrawBuffers` is what tells the driver which
     * attachments a shader's `location = N` outputs land in. Left at its default of
     * attachment 0 only, an MRT shader silently drops the other two into nowhere.
     */
    private val mrtDrawBuffers = MemoryUtil.memAllocInt(3)
    private val singleDrawBuffer = MemoryUtil.memAllocInt(1)

    /** Samples in the current average. Zero means nothing has been drawn. */
    var sampleCount = 0
        private set

    /**
     * Three tables of eight `ivec4`, one per property.
     *
     * Three rather than one table of `ivec4(opaque, emission, attenuation, _)` uploaded to
     * all three uniforms, which is compact and wrong: uploading the same buffer to
     * `uEmission` gives it `uEmission[i].x == opaque`, and the shader reads `.x` of each. A
     * renderer that lightens nothing because a leaf reports itself opaque is not a bug any
     * offline check would have caught, so the tables are separate and each says what it is.
     */
    private val opaqueTable = IntArray(8 * 4)
    private val emissionTable = IntArray(8 * 4)
    private val attenuationTable = IntArray(8 * 4)
    private val tileUV = FloatArray(48 * 4)


    init {
        // Every Block Type's behaviour the shader needs, uploaded rather than written into
        // the shader, so adding one is a one-line change on the Kotlin side and the shader
        // can never disagree with the game about what a leaf is.
        val types = BlockType.entries
        require(types.size <= 8) { "GpuTracer's uniform tables hold eight Block Types" }
        for ((index, type) in types.withIndex()) {
            opaqueTable[index * 4] = if (type.opaque) 1 else 0
            emissionTable[index * 4] = type.emission
            attenuationTable[index * 4] = type.attenuation
            for (faceIndex in 0 until 6) {
                // AIR has no Tile and `BlockType.tile` throws on it, because the CPU
                // tracer never asks an empty Cell for its art. This table asks for every
                // Block Type's every face up front, including AIR's, so the hole is filled
                // here rather than at runtime -- a shader reading an unset entry would be a
                // black speckle on a ray that somehow reached it.
                val tile = if (!type.hasArt) Atlas.LINE else type.tile(Face.entries[faceIndex])
                tileUV[(index * 6 + faceIndex) * 4] = tile.tx.toFloat()
                tileUV[(index * 6 + faceIndex) * 4 + 1] = tile.ty.toFloat()
            }
        }
    }

    private fun uploadAtlas(pixels: IntArray): Int {
        val data = ByteBuffer
            .allocateDirect(atlasSize * atlasSize * 4)
            .order(ByteOrder.nativeOrder())
        for (i in pixels.indices) {
            val p = pixels[i]
            data.put(((p ushr 16) and 0xFF).toByte())
            data.put(((p ushr 8) and 0xFF).toByte())
            data.put((p and 0xFF).toByte())
            data.put(((p ushr 24) and 0xFF).toByte())
        }
        data.flip()
        val id = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, id)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, atlasSize, atlasSize, 0, GL_RGBA, GL_UNSIGNED_BYTE, data)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return id
    }

    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        allocate()
        sampleCount = 0
    }

    private fun allocate() {
        if (width <= 0 || height <= 0) return

        for (id in intArrayOf(read, write, targetTexture, gbufferA, gbufferB, denoised, history, historyNext)) if (id != 0) glDeleteTextures(id)
        if (target != 0) glDeleteFramebuffers(target)

        read = floatTexture()
        write = floatTexture()
        targetTexture = floatTexture()
        gbufferA = floatTexture()
        gbufferB = floatTexture()
        denoised = floatTexture()
        history = floatTexture()
        historyNext = floatTexture()

        mrtDrawBuffers.clear()
        mrtDrawBuffers.put(intArrayOf(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2))
        mrtDrawBuffers.flip()
        singleDrawBuffer.clear()
        singleDrawBuffer.put(GL_COLOR_ATTACHMENT0)
        singleDrawBuffer.flip()

        target = glGenFramebuffers()
        glBindFramebuffer(GL_FRAMEBUFFER, target)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0)
        // Checked rather than assumed. An incomplete framebuffer makes every draw into it
        // a silent no-op, so the picture stays black forever with no error anywhere — the
        // single most expensive kind of GPU bug to diagnose from a screenshot.
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("GpuTracer framebuffer incomplete: 0x${status.toString(16)}")
        }

        clearAccumulator(read)
        clearAccumulator(write)
        clearAccumulator(history)
        clearAccumulator(historyNext)
    }

    private fun floatTexture(): Int {
        val id = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, id)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, null as FloatBuffer?)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return id
    }

    private fun clearAccumulator(id: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, target)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, id, 0)
        glViewport(0, 0, width, height)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    /**
     * Throws the average away, because the eye has moved or the world has changed and the
     * samples taken so far describe a scene that no longer exists.
     *
     * Keeping them would produce a sharp, converged, confidently wrong picture — the failure
     * this whole renderer is least defended against, because it looks like success.
     */
    fun reset() {
        sampleCount = 0
        clearAccumulator(history)
        clearAccumulator(historyNext)
    }

    /** Traces one sample per pixel and folds it into the average. */
    fun sample(
        eye: Vector3f,
        front: Vector3f,
        up: Vector3f,
        fovDegrees: Float,
        aspect: Float,
        sunDir: Vector3f,
        moonDir: Vector3f,
        daylight: Float
    ) {
        if (width <= 0 || height <= 0) return

        glBindFramebuffer(GL_FRAMEBUFFER, target)
        glViewport(0, 0, width, height)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        // --- Pass 1: trace one sample per pixel into the spare accumulator, with the
        // geometry and material of the first hit written alongside it ---
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, write, 0)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, gbufferA, 0)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, gbufferB, 0)
        glDrawBuffers(mrtDrawBuffers)
        traceProgram.use()

        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, atlasTexture)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_3D, volume.texture)

        traceProgram.setInt("uAtlas", 0)
        traceProgram.setInt("uVolume", 1)
        traceProgram.setVec3("uEye", eye.x, eye.y, eye.z)
        traceProgram.setVec3("uFront", front.x, front.y, front.z)
        traceProgram.setVec3("uUp", up.x, up.y, up.z)
        val tanHalf = Math.tan(Math.toRadians((fovDegrees / 2.0).toDouble())).toFloat()
        traceProgram.setFloat("uTanHalf", tanHalf)
        traceProgram.setFloat("uAspect", aspect)
        traceProgram.setVec2("uResolution", width.toFloat(), height.toFloat())
        traceProgram.setVec3i("uOrigin", volume.originX, volume.originY, volume.originZ)
        traceProgram.setVec3i("uSize", volume.sizeX, volume.sizeY, volume.sizeZ)
        traceProgram.setVec3("uSunDir", sunDir.x, sunDir.y, sunDir.z)
        traceProgram.setVec3("uMoonDir", moonDir.x, moonDir.y, moonDir.z)
        traceProgram.setFloat("uDaylight", daylight)
        traceProgram.setInt("uSampleIndex", sampleCount)
        traceProgram.setInt("uSampleCount", sampleCount)
        traceProgram.setInt("uDebug", DEBUG_MODE)
        traceProgram.setInt("uTypeCount", BlockType.entries.size)

        // The array overloads, with the count implied by the array's length. These tables
        // are eight Block Types and never change once built, so uploading them every frame
        // is 32 ints and 192 floats of bandwidth spent to avoid a second shader program.
        glUniform4iv(traceProgram.uniform("uOpaque"), opaqueTable)
        glUniform4iv(traceProgram.uniform("uEmission"), emissionTable)
        glUniform4iv(traceProgram.uniform("uAttenuation"), attenuationTable)
        glUniform4fv(traceProgram.uniform("uTileUV"), tileUV)

        glBindVertexArray(quad.vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)

        // --- Pass 2: average this sample with everything already traced ---
        //
        // Two inputs, and reading only the previous average is the version of this that
        // looks right and renders black: the first frame divides nothing by nothing and
        // every frame after adds the same stale sum to itself.
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0)
        glDrawBuffers(singleDrawBuffer)
        accumProgram.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, read)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, write)
        accumProgram.setInt("uPrevious", 0)
        accumProgram.setInt("uSample", 1)
        accumProgram.setFloat("uCount", sampleCount.toFloat())
        glBindVertexArray(quad.vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)

        // The target is now the average; it becomes the next pass's input.
        val oldRead = read
        read = targetTexture
        targetTexture = oldRead

        sampleCount++

        // --- Pass 3: the average, cleaned. The G-buffer pass 1 wrote is still current,
        // and the average describes the same scene it did the last time the camera moved,
        // so it is the right thing to smooth by. ---
        if (DENOISE) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, denoised, 0)
            glDrawBuffers(singleDrawBuffer)
            denoiseProgram.use()
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, read)
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, gbufferA)
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, gbufferB)
            denoiseProgram.setInt("uImage", 0)
            denoiseProgram.setInt("uGbufferA", 1)
            denoiseProgram.setInt("uGbufferB", 2)
            denoiseProgram.setVec2("uTexel", 1f / width, 1f / height)
            glBindVertexArray(quad.vao)
            glDrawArrays(GL_TRIANGLES, 0, 6)
        }

        // --- Pass 4: temporal EMA — historyNext = lerp(history, denoised, alpha) ---
        // This smooths the image over time, reducing grain while moving.
        // On reset (camera/world change), history is cleared so no ghosting persists.
        // Ping-pong between history and historyNext to avoid read-write hazard.
        val source = if (DENOISE) denoised else read
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, historyNext, 0)
        glDrawBuffers(singleDrawBuffer)
        emaProgram.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, source)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, history)
        emaProgram.setInt("uCurrent", 0)
        emaProgram.setInt("uHistory", 1)
        emaProgram.setFloat("uAlpha", TEMPORAL_ALPHA)
        glBindVertexArray(quad.vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)

        // Swap: historyNext becomes the new history for next frame and for present
        val tmp = history
        history = historyNext
        historyNext = tmp

        // Clean up texture bindings to prevent leakage into present pass
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, 0)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, 0)

        glBindFramebuffer(GL_FRAMEBUFFER, 0)

        // Everything below is a check on the GPU rather than on this file. A trace pass
        // that writes nothing, or an accumulation that reads the wrong texture, produces a
        // black screen and no error — and the only way to tell those apart from a world
        // that really is dark is to read a number back and look at it.
        if (DEBUG_READBACK && sampleCount == 1) {
            val bytes = readback()
            var sum = 0L
            var max = 0
            for (i in bytes.indices step 4) {
                sum += (bytes[i].toInt() and 0xFF)
                if (bytes[i].toInt() and 0xFF > max) max = bytes[i].toInt() and 0xFF
            }
            println("  [gpu] first sample: mean red ${sum / (bytes.size / 4)}, max $max")
        }
    }

    /** Puts the average on the default framebuffer, through the film. */
    fun present(screenWidth: Int, screenHeight: Int) {
        presentProgram.use()
        // The trace pass set the viewport to its own size, which is smaller than the window
        // by exactly the resolution divisor. Drawing to the default framebuffer without
        // putting the viewport back means the picture lands in a corner the size of the
        // trace buffer and everything else keeps whatever the driver had — which is a black
        // screen with a small correct patch in it, and reads as "the tracer is broken"
        // rather than "the viewport was left behind".
        glViewport(0, 0, screenWidth, screenHeight)

        // Clean slate: unbind all texture units, then bind only history
        for (i in 0..3) {
            glActiveTexture(GL_TEXTURE0 + i)
            glBindTexture(GL_TEXTURE_2D, 0)
            glBindTexture(GL_TEXTURE_3D, 0)
        }
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, if (RAW_TRACE) read else history)
        presentProgram.setInt("uImage", 0)
        presentProgram.setFloat("uExposure", PathTracer.EXPOSURE)
        presentProgram.setVec2("uTexel", 1f / screenWidth, 1f / screenHeight)
        presentProgram.setFloat("uSharpen", if (RAW_TRACE) 0f else SHARPEN)
        glBindVertexArray(quad.vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
    }

    /**
     * The average, as bytes, for anything that needs to look at it off-screen.
     *
     * Reads the GPU back rather than recomputing it, so what comes out is what was on the
     * screen and not a second implementation's idea of it.
     */
    fun readback(): ByteArray {
        val out = ByteArray(width * height * 4)
        val buffer = MemoryUtil.memAlloc(width * height * 4)
        // Attached first, deliberately. The framebuffer's attachment is whatever the last
        // pass left on it, and after the swap that is the *other* accumulator — so a
        // readback that trusted it reads a texture nobody has written this frame and
        // reports the renderer as black when it is not.
        glBindFramebuffer(GL_FRAMEBUFFER, target)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, read, 0)
        glReadBuffer(GL_COLOR_ATTACHMENT0)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        for (i in 0 until width * height * 4) out[i] = buffer.get(i)
        MemoryUtil.memFree(buffer)
        return out
    }

    fun release() {
        quad.release()
        for (id in intArrayOf(read, write, targetTexture, gbufferA, gbufferB, denoised, history, historyNext)) if (id != 0) glDeleteTextures(id)
        if (target != 0) glDeleteFramebuffers(target)
        MemoryUtil.memFree(mrtDrawBuffers)
        MemoryUtil.memFree(singleDrawBuffer)
    }
}

/**
 * A unit quad in clip space, for a pass that draws the whole screen.
 *
 * Separate from [UNIT_QUAD], which is a 0..1 quad for the GUI's orthographic projection.
 * This one is already in clip coordinates, because a fullscreen pass has no model matrix and
 * expressing it as one would mean multiplying by an identity to get to where it started.
 */
val TRACE_QUAD = floatArrayOf(
    -1f, -1f, 0f, 0f, 0f,
    1f, -1f, 0f, 1f, 0f,
    1f, 1f, 0f, 1f, 1f,
    -1f, -1f, 0f, 0f, 0f,
    1f, 1f, 0f, 1f, 1f,
    -1f, 1f, 0f, 0f, 1f
)
