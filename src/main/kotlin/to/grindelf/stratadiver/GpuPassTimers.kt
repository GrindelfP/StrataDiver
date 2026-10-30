package to.grindelf.stratadiver

import org.lwjgl.opengl.GL15.GL_QUERY_RESULT
import org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE
import org.lwjgl.opengl.GL15.glBeginQuery
import org.lwjgl.opengl.GL15.glDeleteQueries
import org.lwjgl.opengl.GL15.glEndQuery
import org.lwjgl.opengl.GL15.glGenQueries
import org.lwjgl.opengl.GL15.glGetQueryObjecti
import org.lwjgl.opengl.GL33.GL_TIME_ELAPSED
import org.lwjgl.opengl.GL33.glGetQueryObjectui64

/** Non-blocking GL_TIME_ELAPSED ring. Results are consumed only after availability. */
class GpuPassTimers(private val enabled: Boolean = java.lang.Boolean.getBoolean("stratadiver.gpuTimers")) {
    private val passes = listOf("trace", "resolve", "present")
    private val ringSize = 8
    private val queries = Array(passes.size) { IntArray(ringSize) { glGenQueries() } }
    private val written = IntArray(passes.size)
    private val pending = Array(passes.size) { BooleanArray(ringSize) }
    private val active = IntArray(passes.size) { -1 }
    // Keep the full captured distribution; the query ring is only for non-blocking
    // transport and must not silently reduce a 600-frame benchmark to its last 8 frames.
    private val nanos = Array(passes.size) { LongArray(4096) }
    private val counts = IntArray(passes.size)
    private val captureSample = Array(passes.size) { BooleanArray(ringSize) }
    private var capturing = false

    /** Exclude setup/warm-up work from a benchmark's GPU distribution. */
    fun beginCapture() {
        capturing = true
    }

    fun begin(pass: Int) {
        if (!enabled) return
        val slot = written[pass] % ringSize
        // Avoid overwriting an uncollected query. At most one query per pass per frame;
        // collection runs every frame and is allowed to lag by the full ring.
        if (pending[pass][slot]) {
            if (glGetQueryObjecti(queries[pass][slot], GL_QUERY_RESULT_AVAILABLE) == 0) return
            collect(pass, slot)
        }
        active[pass] = slot
        captureSample[pass][slot] = capturing
        glBeginQuery(GL_TIME_ELAPSED, queries[pass][slot])
    }

    fun end(pass: Int) {
        if (!enabled) return
        if (active[pass] < 0) return
        glEndQuery(GL_TIME_ELAPSED)
        pending[pass][active[pass]] = true
        active[pass] = -1
        written[pass]++
    }

    fun poll() {
        if (!enabled) return
        for (pass in passes.indices) for (slot in 0 until ringSize) {
            if (pending[pass][slot] && glGetQueryObjecti(queries[pass][slot], GL_QUERY_RESULT_AVAILABLE) != 0)
                collect(pass, slot)
        }
    }

    private fun collect(pass: Int, slot: Int) {
        val result = glGetQueryObjectui64(queries[pass][slot], GL_QUERY_RESULT)
        if (captureSample[pass][slot] && counts[pass] < nanos[pass].size)
            nanos[pass][counts[pass]++] = result
        pending[pass][slot] = false
    }

    fun report(): String {
        if (!enabled) return "GPU timers disabled (launch with -Dstratadiver.gpuTimers=true)"
        poll()
        return passes.indices.joinToString("  ") { p ->
            val values = nanos[p].take(counts[p]).sorted()
            if (values.isEmpty()) "${passes[p]}=unavailable" else {
                val median = values[kotlin.math.ceil(values.size * 0.50).toInt() - 1]
                val p95 = values[kotlin.math.ceil(values.size * 0.95).toInt() - 1]
                "${passes[p]}-gpu-ms median=${"%.3f".format(median / 1e6)} " +
                    "p95=${"%.3f".format(p95 / 1e6)} n=${values.size}"
            }
        }
    }

    fun release() {
        queries.forEach { glDeleteQueries(it) }
    }
}
