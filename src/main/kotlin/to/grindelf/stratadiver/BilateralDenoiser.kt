package to.grindelf.stratadiver

import kotlin.math.exp
import kotlin.math.pow

/** CPU reference for the small edge-aware filter used by denoise.frag. */
object BilateralDenoiser {
    const val RADIUS = 1
    private const val NORMAL_POWER = 16.0
    private const val DEPTH_SIGMA = 0.25
    private const val ALBEDO_SIGMA = 0.08

    data class Pixel(val r: Double, val g: Double, val b: Double,
                     val nx: Double, val ny: Double, val nz: Double,
                     val depth: Double, val ar: Double, val ag: Double, val ab: Double)

    fun weight(centre: Pixel, neighbour: Pixel, dx: Int, dy: Int): Double {
        if (centre.depth == 0.0 || neighbour.depth == 0.0) {
            if (centre.depth != neighbour.depth) return 0.0
        }
        val spatial = exp(-0.5 * (dx * dx + dy * dy))
        val normal = (centre.nx * neighbour.nx + centre.ny * neighbour.ny + centre.nz * neighbour.nz)
            .coerceAtLeast(0.0).pow(NORMAL_POWER)
        val depth = if (centre.depth > 0.0 && neighbour.depth > 0.0)
            exp(-0.5 * ((neighbour.depth - centre.depth) / DEPTH_SIGMA).pow(2.0)) else 1.0
        val dr = neighbour.ar - centre.ar
        val dg = neighbour.ag - centre.ag
        val db = neighbour.ab - centre.ab
        val albedo = exp(-0.5 * (dr * dr + dg * dg + db * db) / (ALBEDO_SIGMA * ALBEDO_SIGMA))
        return spatial * normal * depth * albedo
    }

    /** Clamped-edge 3x3 filter; guide and radiance arrays must describe the same image. */
    fun filter(image: List<Pixel>, width: Int, height: Int): List<Pixel> {
        require(width > 0 && height > 0 && image.size == width * height)
        return List(image.size) { index ->
            val x = index % width
            val y = index / width
            val centre = image[index]
            var r = centre.r; var g = centre.g; var b = centre.b
            var total = 1.0
            for (dy in -RADIUS..RADIUS) for (dx in -RADIUS..RADIUS) {
                if (dx == 0 && dy == 0) continue
                val other = image[y.coerceAdd(dy, height) * width + x.coerceAdd(dx, width)]
                val w = weight(centre, other, dx, dy)
                r += w * other.r; g += w * other.g; b += w * other.b
                total += w
            }
            centre.copy(r = r / total, g = g / total, b = b / total)
        }
    }

    private fun Int.coerceAdd(delta: Int, limit: Int) = (this + delta).coerceIn(0, limit - 1)
}
