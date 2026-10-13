package to.grindelf.stratadiver

/**
 * The sounds the game can play.
 *
 * Every Sound is defined by *what caused it* and *what it is made of*, which is why the
 * set grows by Material rather than by Block Type. There is no sound for "grass", only
 * for "earth, stepped on".
 */
enum class Occasion { FOOTSTEP, BREAK, PLACE, PICKUP, LANDING }

/**
 * Turns a Material and an Occasion into a waveform.
 *
 * Everything here is arithmetic over noise, and that is a deliberate choice rather than a
 * stand-in for real work: the sounds are placeholders for recordings that do not exist.
 * See `docs/adr/0002-sounds-synthesized-at-runtime.md`.
 *
 * Two properties are non-negotiable and are what `tools/Check.kt` asserts:
 *
 * - **Deterministic.** Seeded from the Material and Occasion, so the same launch produces
 *   byte-identical buffers. Otherwise the headless checks would be asserting against a
 *   moving target, and a buffer that happens to work today could be silent tomorrow.
 * - **Audible.** A filter that cancels to zero, or an envelope that never opens, produces
 *   a buffer that is perfectly valid and makes no sound at all. That is the specific
 *   failure this shape of code invites, so it is checked for.
 */
object Synth {

    /** 48kHz: the sample rate of the machine's default output, and cheap to resample. */
    const val SAMPLE_RATE = 48_000

    /** How long any one sound lasts. Short enough to feel like an event, not a loop. */
    private const val MAX_SECONDS = 0.45f

    /**
     * The sound for [occasion] on [material], as mono samples in -1..1.
     *
     * Built from filtered noise under an envelope. Noise rather than a tone because every
     * one of these sounds is the sound of something being struck or moved through, and
     * those are broadband; the filter is what gives each Material its character.
     */
    fun render(material: Material, occasion: Occasion): FloatArray {
        val voice = Voice.of(material, occasion)
        // Multiply before rounding, not after: (0.45f).toInt() is 0, which makes the clamp
        // below an empty range and throws rather than producing a sound.
        val ceiling = (MAX_SECONDS * SAMPLE_RATE).toInt()
        val length = (voice.seconds * SAMPLE_RATE).toInt().coerceIn(64, ceiling)

        // Seeded from what the sound *is*, not from the clock. Two launches produce
        // byte-identical audio, which is what lets the headless checks assert against it.
        val random = WorldGen.Random(
            WorldGen.seedFor(material.ordinal, occasion.ordinal, 0x50F0)
        )

        // Two-stage filter: a resonant band that gives a Material its pitch character,
        // then a high- or low-pass that gives it its edge.
        val band = ResonantFilter(SAMPLE_RATE, voice.bandHz, voice.resonance)
        val edge = ResonantFilter(SAMPLE_RATE, voice.edgeHz, voice.edgeResonance)

        val out = FloatArray(length)
        for (i in 0 until length) {
            val t = i.toFloat() / SAMPLE_RATE

            // White noise, band-passed to the Material's resonant frequency.
            val noise = random.nextFloat() * 2f - 1f
            var sample = band.process(noise)

            // Second stage. Dry bypassed so a filter at an extreme cutoff contributes
            // colour rather than silence — the failure mode worth designing against.
            val filtered = edge.process(sample)
            sample = if (voice.mixEdge > 0f) {
                sample * (1f - voice.mixEdge) + filtered * voice.mixEdge
            } else {
                filtered
            }

            // A touch of a low sine gives stone and wood a pitched core; without it they
            // are indistinguishable from each other at small volumes.
            val tone = kotlin.math.sin(2.0 * Math.PI * voice.toneHz * t).toFloat()
            sample = sample * (1f - voice.toneMix) + tone * voice.toneMix * voice.decay(t)

            // Envelope. Exponential decay from a short attack — the shape of being hit,
            // as opposed to a linear fade, which sounds like a synthesizer.
            sample *= voice.envelope(t)

            out[i] = sample.coerceIn(-1f, 1f)
        }

        // Fade the last few samples to exact zero. A buffer that ends mid-waveform pops,
        // and a pop at the end of every single footstep is the kind of thing that makes
        // a soundscape feel broken.
        val fade = minOf(64, length / 8)
        for (i in 0 until fade) {
            out[length - 1 - i] *= i.toFloat() / fade
        }

        return out
    }

    /**
     * The parameters of one sound: how long it lasts, what it is filtered to, and how it
     * decays.
     *
     * The characters follow from the substances. Soft things thud, hard things click, wood
     * rings, leaves rustle.
     */
    private data class Voice(
        val seconds: Float,
        val bandHz: Float,
        val resonance: Float,
        val edgeHz: Float,
        val edgeResonance: Float,
        val mixEdge: Float,
        val toneHz: Float,
        val toneMix: Float,
        val attack: Float,
        val decayRate: Float
    ) {
        /** Exponential decay, in the same units as the Voice's times. */
        fun decay(t: Float) = kotlin.math.exp((-decayRate * t).toDouble()).toFloat()

        fun envelope(t: Float): Float =
            if (t < attack) t / attack else decay(t)

        companion object {
            fun of(material: Material, occasion: Occasion): Voice {
                val base = when (material) {
                    // Soft, muffled, low. Dirt and grass barely ring at all.
                    Material.EARTH -> Voice(
                        seconds = 0.14f, bandHz = 320f, resonance = 0.9f,
                        edgeHz = 900f, edgeResonance = 1.4f, mixEdge = 0.35f,
                        toneHz = 90f, toneMix = 0.25f, attack = 0.004f, decayRate = 26f
                    )

                    // Sharp, bright, fast. A click with a faint ring under it.
                    Material.STONE -> Voice(
                        seconds = 0.11f, bandHz = 2600f, resonance = 1.6f,
                        edgeHz = 5000f, edgeResonance = 1.9f, mixEdge = 0.6f,
                        toneHz = 640f, toneMix = 0.3f, attack = 0.001f, decayRate = 40f
                    )

                    // Hollow and mid. The band is the point — it is what "knock" is.
                    Material.WOOD -> Voice(
                        seconds = 0.18f, bandHz = 620f, resonance = 0.55f,
                        edgeHz = 1800f, edgeResonance = 1.1f, mixEdge = 0.45f,
                        toneHz = 210f, toneMix = 0.45f, attack = 0.002f, decayRate = 17f
                    )

                    // Airy, high, soft, and the longest tail of the four. No hard transient:
                    // brushing through leaves does not click.
                    Material.FOLIAGE -> Voice(
                        seconds = 0.30f, bandHz = 3400f, resonance = 0.7f,
                        edgeHz = 6500f, edgeResonance = 1.2f, mixEdge = 0.75f,
                        toneHz = 0f, toneMix = 0f, attack = 0.02f, decayRate = 11f
                    )
                }

                // The Occasion bends the Material's character rather than replacing it: a
                // footstep is the same substance played softly, a break is the same played
                // hard. Without this, each Material would need four separate voices.
                return when (occasion) {
                    Occasion.FOOTSTEP -> base.copy(
                        seconds = base.seconds * 0.75f,
                        mixEdge = base.mixEdge * 0.5f,
                        toneMix = base.toneMix * 0.6f,
                        attack = base.attack * 2f
                    )
                    Occasion.LANDING -> base.copy(
                        seconds = base.seconds * 1.6f,
                        decayRate = base.decayRate * 0.55f,
                        toneMix = base.toneMix * 1.3f,
                        // A landing is felt, not just heard: no bright edge on the impact.
                        mixEdge = base.mixEdge * 0.4f
                    )
                    Occasion.BREAK -> base.copy(
                        seconds = base.seconds * 1.3f,
                        mixEdge = (base.mixEdge + 0.25f).coerceAtMost(1f),
                        attack = 0.001f
                    )
                    Occasion.PLACE -> base.copy(
                        seconds = base.seconds * 0.9f,
                        mixEdge = base.mixEdge * 0.7f,
                        toneMix = base.toneMix * 0.9f
                    )
                    Occasion.PICKUP -> base.copy(
                        seconds = base.seconds * 0.85f,
                        mixEdge = (base.mixEdge + 0.2f).coerceAtMost(1f),
                        toneMix = base.toneMix * 1.2f
                    )
                }
            }
        }
    }

    /**
     * A Chamberlin state-variable filter: one structure, three outputs.
     *
     * Chosen because it gives low-pass, band-pass and high-pass from the same two lines of
     * arithmetic, and because it is stable at audio rates for the moderate resonance values
     * these sounds use — a biquad would be more precise and is not needed to make earth
     * sound different from stone.
     */
    private class ResonantFilter(sampleRate: Int, frequencyHz: Float, resonance: Float) {
        private var low = 0f
        private var band = 0f

        // Clamped below 1 because the top of this structure goes unstable past it, and a
        // filter that produces NaN produces silence everywhere downstream.
        private val f = (2.0 * Math.sin(Math.PI * frequencyHz.coerceIn(20f, sampleRate / 4f) / sampleRate))
            .toFloat()
        private val q = 1f / resonance.coerceIn(1.0001f, 12f)

        /** The band-passed output: the resonant centre of the sound. */
        fun process(input: Float): Float {
            low += f * band
            val high = input - low - q * band
            band += f * high
            return band
        }
    }

    /** Converts mono float samples to 16-bit PCM, the format OpenAL buffers take. */
    fun toPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return out
    }
}
