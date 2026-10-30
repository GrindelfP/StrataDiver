package to.grindelf.stratadiver

/**
 * Where the frame's milliseconds actually went.
 *
 * This exists because the estimates were wrong. The plan for the GPU port asserted that
 * tonemapping the picture was a significant part of the frame; measuring it showed it was
 * 40 ms a frame at the resolution the game was actually running, *outside* the window the
 * adaptive resolution was measuring as the trace cost. Nothing had ever seen it because
 * nothing had ever looked. A renderer can spend a second a frame being careful with rays
 * and still lose to its own film, and the only way to know which is happening is to write
 * the number down while it happens.
 *
 * So: measured every frame, averaged over the window, drawn. The stages are the ones the
 * frame is actually made of, in the order they happen, so the bar reads left to right as the
 * frame does.
 *
 * The GPU half of the frame is not in here. Everything below is CPU wall time, and the
 * difference between "trace" and "present" is where a GL driver's own work would hide.
 * ARB_timer_query is available on this machine and would close that gap, but a fence around
 * the swap is what this measures first, and a number that is honest about what it does not
 * include is worth more than one that guesses.
 */
class FrameTimes(private val stageNames: List<String>) {

    private val stageAverages = Array(stageNames.size) { RollingAverage(60) }
    private val frameAverage = RollingAverage(60)
    private val stageFrame = DoubleArray(stageNames.size)
    private var mark = System.nanoTime()

    private var frameTotal = 0.0

    /** The whole frame, in milliseconds, averaged. */
    var frameMillis = 0.0
        private set

    fun begin() {
        mark = System.nanoTime()
        frameTotal = 0.0
        stageFrame.fill(0.0)
    }

    /** Closes the stage before [index] and opens the next one. */
    fun stage(index: Int) {
        val now = System.nanoTime()
        val delta = now - mark
        // Milliseconds, in both places. One of these was accumulating nanoseconds while
        // the other accumulated milliseconds, which made the frame total about a million
        // times too large — and the adaptive resolution controller, which reads it to
        // decide how hard it can afford to work, sat pinned at minimum resolution forever
        // without ever printing anything wrong.
        val ms = delta / 1e6
        stageFrame[index] += ms
        frameTotal += ms
        mark = now
    }

    /**
     * Ends the frame and folds this frame's stages into the averages.
     *
     * Averaged rather than shown raw because a single frame is not a measurement of
     * anything: the one after a reset is cold and the one before it is warm, and a readout
     * that flickers between them cannot be read at all.
     */
    fun end() {
        val now = System.nanoTime()
        val last = now - mark
        val ms = last / 1e6
        stageFrame[stageFrame.lastIndex] += ms
        frameTotal += ms
        stageFrame.forEachIndexed { index, value -> stageAverages[index].add(value) }
        frameAverage.add(frameTotal)
        frameMillis = frameAverage.average()
    }

    fun average(index: Int): Double = stageAverages[index].average()

    /**
     * Draws the breakdown under the frame-rate readout.
     *
     * A stacked bar in one row, each stage its own shade and width proportional to the
     * milliseconds it spent, then the number. Width rather than a list of figures, because
     * the question this answers is "what is eating the frame", and that is a shape
     * question: one fat bar segment names its culprit faster than any table can.
     */
    fun render(ui: UiPass, x: Float, y: Float, width: Float, diagnostics: String) {
        ui.begin()

        val scale = 4f
        val rowHeight = 5f

        // One row of stages.
        var cursor = x
        for (i in stageNames.indices) {
            val ms = average(i).toFloat()
            val w = if (i == stageNames.size - 1) width - (cursor - x)
            else (ms * scale).coerceAtMost(width - (cursor - x))
            if (w > 0.4f) {
                ui.line(cursor, y, w, rowHeight, SHADES[i % SHADES.size])
                cursor += w + 1f
            }
        }

        // The name of each stage under its own colour, and the frame total beside them.
        var tx = x
        for (i in stageNames.indices) {
            val label = "${"%.1f".format(average(i))} ${stageNames[i]}"
            text(ui, tx, y + rowHeight + 2f, label, SHADES[i % SHADES.size])
            tx += 4f * label.length + 10f
            if (tx > x + width - 40f) {
                tx = x
            }
        }

        text(ui, x, y + 20f, diagnostics, 0.8f)

        ui.end()
    }

    /**
     * Text, from a 3x5 font.
     *
     * Its own tiny font rather than a new Tile in the Atlas: this is a readout of numbers,
     * it is redrawn four times a second, and a font in the Atlas would be 36 Tiles of
     * memory to draw a caption that only a developer is ever going to read.
     */
    private fun text(ui: UiPass, x: Float, y: Float, message: String, shade: Float) {
        var cursor = x
        for (ch in message.uppercase()) {
            // Anything not in the font — a space, a lowercase letter, a stray character from a stage
            // name — falls back to '?' rather than indexing off the end of the table. The
            // bounds test is on the *offset*, not on a getOrElse: a negative index is not
            // out of bounds in Kotlin's eyes, it is a different bug, and it is thrown by
            // the same missing check that would have let a renamed stage name crash the
            // game on its first frame.
            val offset = ch - 'A'
            // '?' - 'A' is negative — '?' sits below 'A' in ASCII — so the fallback is an
            // index of its own rather than arithmetic on a character.
            val glyph = if (offset in FONT.indices) FONT[offset] else FONT[QUESTION]
            for (row in 0 until 5) {
                for (col in 0 until 3) {
                    if (glyph[(4 - row) * 3 + col] == '1') {
                        ui.line(cursor + col * 1.6f, y + row * 1.6f, 1.5f, 1.5f, shade)
                    }
                }
            }
            cursor += 5.6f
        }
    }

    private companion object {
        /** One shade per stage, dark to bright, so the bar reads left to right. */
        val SHADES = floatArrayOf(0.30f, 0.45f, 0.60f, 0.75f, 0.90f, 1.0f)

        /** Where the question mark sits in [FONT]. See its use in [text]. */
        const val QUESTION = 35

        /**
         * A 3x5 font, five rows of three bits, most significant bit leftmost.
         *
         * Written as rows rather than as packed integers so the shapes are visible: a
         * font encoded as hex is unreadable in the source and unfixable when a letter comes
         * out wrong, which is the same lesson the seven-segment table taught.
         */
        val FONT = arrayOf(
            "111101101101111", // A
            "110101110101110", // B
            "111100100100111", // C
            "110101101101110", // D
            "111100111100111", // E
            "111100111100100", // F
            "111101101101111", // G
            "101101111101101", // H
            "111010010010111", // I
            "001001001001111", // J
            "101101110101101", // K
            "100100100100111", // L
            "101111111101101", // M
            "101111111111101", // N
            "111101101101111", // O
            "111101111100100", // P
            "111101101111101", // Q
            "110101110101101", // R
            "111010010010111", // S
            "111010010010010", // T
            "101101101101111", // U
            "101101101101010", // V
            "101101111111101", // W
            "101101010101101", // X
            "101101010010010", // Y
            "111001010100111", // Z
            "010111011011110", // 0
            "010110010010111", // 1
            "110001010100011", // 2
            "110001010001110", // 3
            "101101111001001", // 4
            "111100110001110", // 5
            "111100111101111", // 6
            "111001010010010", // 7
            "111101111101111", // 8
            "111101111001111", // 9
            "000010111011000"  // ?
        )
    }
}
