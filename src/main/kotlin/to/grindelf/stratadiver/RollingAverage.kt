package to.grindelf.stratadiver

/** Fixed-capacity arithmetic rolling mean; each reported mean covers exactly [count] samples. */
class RollingAverage(private val capacity: Int) {
    private val values: DoubleArray
    private var next = 0
    var count: Int = 0
        private set
    private var sum = 0.0

    init {
        require(capacity > 0)
        values = DoubleArray(capacity)
    }

    fun add(value: Double) {
        require(value.isFinite())
        if (count == capacity) sum -= values[next] else count++
        values[next] = value
        sum += value
        next = (next + 1) % capacity
    }

    fun average(): Double = if (count == 0) 0.0 else sum / count
}
