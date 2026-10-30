package to.grindelf.stratadiver

/** Exact response of an EMA whose current-frame weight is [currentWeight]. */
object TemporalResponse {
    fun historyWeight(currentWeight: Double, frames: Int): Double {
        require(currentWeight in 0.0..1.0)
        require(frames >= 0)
        return (1.0 - currentWeight).pow(frames)
    }

    /** Number of frames for old history to fall to one half; infinity at full current weight. */
    fun halfLifeFrames(currentWeight: Double): Double {
        require(currentWeight in 0.0..1.0)
        return when (currentWeight) {
            0.0 -> Double.POSITIVE_INFINITY
            1.0 -> 0.0
            else -> kotlin.math.ln(0.5) / kotlin.math.ln(1.0 - currentWeight)
        }
    }

    private fun Double.pow(exponent: Int): Double = Math.pow(this, exponent.toDouble())
}
