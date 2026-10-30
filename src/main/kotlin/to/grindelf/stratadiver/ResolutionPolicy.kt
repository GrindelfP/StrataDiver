package to.grindelf.stratadiver

/** Bounded, hysteretic resolution response to measured CPU work (not swap/vsync time). */
class ResolutionPolicy(
    private val budgetMillis: Double,
    private val minimumDivisor: Int = 1,
    private val maximumDivisor: Int = 2,
    private val decisionFrames: Int = 6,
    private val headroom: Double = 0.80
) {
    private var frames = 0
    private var smoothedMillis = 0.0

    init {
        require(budgetMillis > 0 && minimumDivisor > 0 && maximumDivisor >= minimumDivisor)
        require(decisionFrames > 0 && headroom in 0.0..1.0)
    }

    /** Returns at most one divisor step after a complete decision window. */
    fun observe(divisor: Int, measuredCpuMillis: Double): Int {
        require(divisor in minimumDivisor..maximumDivisor)
        require(measuredCpuMillis.isFinite() && measuredCpuMillis >= 0)
        smoothedMillis = if (frames == 0) measuredCpuMillis else smoothedMillis * 0.7 + measuredCpuMillis * 0.3
        frames++
        if (frames < decisionFrames) return divisor
        frames = 0
        return when {
            smoothedMillis > budgetMillis && divisor < maximumDivisor -> divisor + 1
            smoothedMillis < budgetMillis * headroom && divisor > minimumDivisor -> divisor - 1
            else -> divisor
        }
    }
}
