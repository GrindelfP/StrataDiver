package to.grindelf.stratadiver

/** Aggregates completed CPU frame-wall measurements without confusing them with GPU time. */
class FrameBenchmark(private val capacity: Int) {
    private val samples = DoubleArray(capacity)
    private var count = 0

    init { require(capacity > 0) }

    fun record(milliseconds: Double) {
        require(milliseconds.isFinite() && milliseconds >= 0.0)
        if (count < capacity) samples[count++] = milliseconds
    }

    fun count(): Int = count

    fun percentile(percentile: Double): Double {
        require(percentile in 0.0..100.0)
        if (count == 0) return Double.NaN
        val sorted = samples.copyOf(count).sorted()
        val index = kotlin.math.ceil(percentile / 100.0 * sorted.size).toInt()
            .minus(1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    fun report(): String = if (count == 0) "no measured frames" else
        "frames=$count cpu-frame-wall-ms median=${"%.3f".format(percentile(50.0))} " +
            "p95=${"%.3f".format(percentile(95.0))}"
}
