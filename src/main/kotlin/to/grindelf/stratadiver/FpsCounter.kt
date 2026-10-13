package to.grindelf.stratadiver

/**
 * The frame-rate readout, top-left.
 *
 * Drawn as seven-segment digits assembled from solid quads, so it needs no font and no
 * Tile in the atlas — the whole HUD grows by zero bytes of art per digit.
 */
class FpsCounter(private val refreshMs: Long = 250L) {

    private val frameTimes = FloatArray(30)
    private var frameTimeIndex = 0
    private var frameTimeCount = 0
    private var lastFrameNanos = 0L
    private var lastUpdateNanos = 0L
    private var displayedFps = 0f

    /**
     * Samples the frame time on every call but only returns a new number every
     * [refreshMs] milliseconds.
     *
     * The average is genuinely measured every frame; it is only the *display* that is
     * throttled, because a number changing sixty times a second is not a readout.
     */
    fun sample(): Float {
        val now = System.nanoTime()
        if (lastFrameNanos != 0L) {
            frameTimes[frameTimeIndex] = (now - lastFrameNanos) / 1_000_000_000f
            frameTimeIndex = (frameTimeIndex + 1) % frameTimes.size
            if (frameTimeCount < frameTimes.size) frameTimeCount++
        }
        lastFrameNanos = now

        if (now - lastUpdateNanos >= refreshMs * 1_000_000L) {
            if (frameTimeCount > 0) {
                var sum = 0f
                for (i in 0 until frameTimeCount) sum += frameTimes[i]
                val average = sum / frameTimeCount
                displayedFps = if (average > 0f) 1f / average else 0f
            }
            lastUpdateNanos = now
        }
        return displayedFps
    }

    fun render(ui: UiPass) {
        val fps = sample()
        if (fps <= 0f) return

        val text = fps.toInt().toString()
        val digits = if (text.length >= 2) text else "0$text"

        ui.begin()
        var x = 10f
        for (ch in digits) {
            digit(ui, x, 10f, 22f, 32f, ch - '0')
            x += 28f
        }
        var labelX = x + 6f
        for (ch in "FPS") {
            letter(ui, labelX, 30f, 22f, 12f, ch)
            labelX += 28f
        }
        ui.end()
    }

    //     aaa
    //    f   b
    //    f   b
    //     ggg
    //    e   c
    //    e   c
    //     ddd
    //
    // Written as segment names, not bitmasks. Hand-written masks were wrong for nine of
    // the ten digits — which is exactly the class of bug that naming makes obvious and
    // hex does not.
    private val SEGMENT_DIGITS = arrayOf(
        "abcdef",  // 0
        "bc",      // 1
        "abdeg",   // 2
        "abcdg",   // 3
        "bcfg",    // 4
        "acdfg",   // 5
        "acdefg",  // 6
        "abc",     // 7
        "abcdefg", // 8
        "abcdfg"   // 9
    )

    private fun segmentMask(digit: Int): Int {
        var mask = 0
        for (segment in SEGMENT_DIGITS[digit]) {
            // Bit 6 = segment a, down to bit 0 = segment g.
            mask = mask or (1 shl (6 - "abcdefg".indexOf(segment)))
        }
        return mask
    }

    private fun digit(ui: UiPass, x: Float, y: Float, w: Float, h: Float, value: Int) {
        if (value !in 0..9) return
        val lit = segmentMask(value)
        val t = (h * 0.14f).coerceAtLeast(2f) // segment thickness
        val half = (h - t) / 2f

        // [x, y, w, h] per segment a..g
        val bars = arrayOf(
            floatArrayOf(x + t, y, w - 2 * t, t),                 // a top
            floatArrayOf(x + w - t, y + t, t, half - t),          // b upper right
            floatArrayOf(x + w - t, y + half + t, t, half - t),   // c lower right
            floatArrayOf(x + t, y + h - t, w - 2 * t, t),         // d bottom
            floatArrayOf(x, y + half + t, t, half - t),           // e lower left
            floatArrayOf(x, y + t, t, half - t),                  // f upper left
            floatArrayOf(x + t, y + h / 2f - t / 2f, w - 2 * t, t) // g middle
        )
        for (i in bars.indices) {
            if ((lit shr (6 - i)) and 1 == 0) continue
            val bar = bars[i]
            ui.line(bar[0], bar[1], bar[2], bar[3], 0.85f)
        }
    }

    /** Minimal letterforms for the FPS label, built from the same bars. */
    private fun letter(ui: UiPass, x: Float, y: Float, w: Float, h: Float, ch: Char) {
        val t = (h * 0.22f).coerceAtLeast(1.5f)
        fun bar(bx: Float, by: Float, bw: Float, bh: Float) =
            ui.line(x + bx, y + by, bw, bh, 0.7f)

        when (ch) {
            'F' -> {
                bar(0f, 0f, t, h); bar(0f, 0f, w, t); bar(0f, h / 2f, w - t, t)
            }
            'P' -> {
                bar(0f, 0f, t, h); bar(0f, 0f, w - t, t); bar(w - t, 0f, t, h / 2f)
                bar(0f, h / 2f - t / 2f, w - t, t)
            }
            'S' -> {
                bar(t, 0f, w - 2 * t, t); bar(0f, t, t, h / 2f - t)
                bar(t, h / 2f - t / 2f, w - 2 * t, t)
                bar(w - t, h / 2f, t, h / 2f - t); bar(t, h - t, w - 2 * t, t)
            }
        }
    }
}
