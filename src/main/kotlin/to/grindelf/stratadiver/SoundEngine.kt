package to.grindelf.stratadiver

import org.joml.Vector3f
import org.lwjgl.openal.AL10.AL_BUFFER
import org.lwjgl.openal.AL10.AL_FORMAT_MONO16
import org.lwjgl.openal.AL10.AL_GAIN
import org.lwjgl.openal.AL10.AL_MAX_DISTANCE
import org.lwjgl.openal.AL10.AL_MIN_GAIN
import org.lwjgl.openal.AL10.AL_ORIENTATION
import org.lwjgl.openal.AL10.AL_PITCH
import org.lwjgl.openal.AL10.AL_POSITION
import org.lwjgl.openal.AL10.AL_REFERENCE_DISTANCE
import org.lwjgl.openal.AL10.AL_ROLLOFF_FACTOR
import org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE
import org.lwjgl.openal.AL10.AL_SOURCE_STATE
import org.lwjgl.openal.AL10.AL_PLAYING
import org.lwjgl.openal.AL10.alBufferData
import org.lwjgl.openal.AL10.alDeleteBuffers
import org.lwjgl.openal.AL10.alDeleteSources
import org.lwjgl.openal.AL10.alGenBuffers
import org.lwjgl.openal.AL10.alGenSources
import org.lwjgl.openal.AL10.alGetSourcei
import org.lwjgl.openal.AL10.alListener3f
import org.lwjgl.openal.AL10.alListenerf
import org.lwjgl.openal.AL10.alListenerfv
import org.lwjgl.openal.AL10.alSource3f
import org.lwjgl.openal.AL10.alSourcef
import org.lwjgl.openal.AL10.alSourcei
import org.lwjgl.openal.AL10.alSourcePlay
import org.lwjgl.openal.AL10.alSourceStop
import org.lwjgl.system.MemoryUtil.NULL

/**
 * Plays Sounds, and owns the audio device.
 *
 * **Audio is optional.** If the device will not open — no hardware, another app holding
 * it, a headless machine — this reports itself disabled and every call becomes a no-op.
 * The game runs silently rather than refusing to start. A game that will not launch
 * because a sound card is unhappy is strictly worse than a quiet game.
 *
 * Every Sound is rendered once at startup into an OpenAL buffer and then reused, so
 * playing one is a handful of uniform writes. Nothing here knows how a buffer was produced
 * — that is what lets real recordings replace the synthesised placeholders without
 * touching this file. See `docs/adr/0002-sounds-synthesized-at-runtime.md`.
 */
class SoundEngine {

    /** Whether audio is actually available. Everything else is safe to call regardless. */
    var isAvailable: Boolean = false
        private set

    private val sources = IntArray(VOICE_POOL)
    private val buffers = HashMap<Pair<Occasion, Material>, Int>()

    /** When each source last started, so the oldest can be identified when all are busy. */
    private val startedAt = LongArray(VOICE_POOL)
    private var playCounter = 0L

    private var playSeed = 1

    /** How loud everything is, before the GUI adjustment. 0..1. */
    private var volume = 0.7f

    /** Set while the player has a GUI layer open, which eases the mix down. */
    var isGuiOpen: Boolean = false
        set(value) {
            field = value
            applyGain()
        }

    /** Set while the window is not focused, which silences the game entirely. */
    var isPaused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) stopAllSources()
            applyGain()
        }

    /**
     * Opens the device and renders every Sound.
     *
     * Returns false if audio is unavailable. The caller carries on regardless — nothing
     * downstream should ever need to know.
     */
    fun start(): Boolean {
        // Everything here is inside a catch because "audio is optional" has to mean it.
        // A missing native library, a busy device, an OpenAL that refuses a context: all
        // of them are a quiet game, and none of them may stop the game starting. The
        // previous version crashed the game on launch the first time it met one.
        return try {
            openDevice()
        } catch (e: Throwable) {
            // UnsatisfiedLinkError, IllegalStateException, whatever the platform decides
            // to throw. Deliberately Throwable: an Error from a native loader is not an
            // Exception, and catching only Exception would let the crash through.
            println("[sound] unavailable (${e::class.simpleName}: ${e.message}); running silently")
            false
        }
    }

    private fun openDevice(): Boolean {
        // `ALC` creates itself in a static initialiser, unless the system property
        // org.lwjgl.system.Configuration.OPENAL_EXPLICIT_INIT is set. Calling create()
        // unconditionally therefore throws "ALC has already been created" — the class was
        // initialised by the reference to it on the line above.
        if (org.lwjgl.openal.ALC.getFunctionProvider() == null) {
            org.lwjgl.openal.ALC.create()
        }

        val device = org.lwjgl.openal.ALC10.alcOpenDevice(null as java.nio.ByteBuffer?)
        if (device == NULL) {
            println("[sound] no audio device available; running silently")
            return false
        }

        // Attributes are (name, value) pairs, terminated by a zero name. The frequency is
        // pinned to the rate the sounds were synthesized at, so OpenAL is not asked to
        // resample every buffer on the way out.
        //
        // Built with *relative* puts before flip(). Writing with an absolute put and then
        // flipping inverts a buffer whose position never moved, and produces one with
        // negative remaining — which surfaces as "Number of remaining elements is 0" from
        // deep inside LWJGL and a context that is never created.
        val attributes = org.lwjgl.system.MemoryUtil.memAllocInt(3)
        attributes.put(org.lwjgl.openal.ALC10.ALC_FREQUENCY).put(Synth.SAMPLE_RATE)
        attributes.put(0)
        attributes.flip()

        val context = org.lwjgl.openal.ALC10.alcCreateContext(device, attributes)
        org.lwjgl.system.MemoryUtil.memFree(attributes)
        if (context == NULL) {
            println("[sound] no audio context available; running silently")
            org.lwjgl.openal.ALC10.alcCloseDevice(device)
            return false
        }
        org.lwjgl.openal.ALC10.alcMakeContextCurrent(context)

        org.lwjgl.openal.AL.createCapabilities(
            org.lwjgl.openal.ALC.createCapabilities(device)
        )

        alGenSources(sources)

        for (occasion in Occasion.entries) {
            for (material in Material.entries) {
                val pcm = Synth.toPcm16(Synth.render(material, occasion))
                val buffer = alGenBuffers()
                alBufferData(buffer, AL_FORMAT_MONO16, pcm, Synth.SAMPLE_RATE)
                buffers[occasion to material] = buffer
            }
        }

        applyGain()
        isAvailable = true
        return true
    }

    /**
     * Points the listener at the player's eyes, so positioned Sounds come from the world
     * rather than from inside the player's head.
     */
    fun updateListener(position: Vector3f, front: Vector3f) {
        if (!isAvailable) return
        alListener3f(AL_POSITION, position.x, position.y, position.z)
        // OpenAL wants forward then up, as six floats.
        alListenerfv(AL_ORIENTATION, floatArrayOf(front.x, front.y, front.z, 0f, 1f, 0f))
    }

    /**
     * A GUI layer eases the mix down but never to silence.
     *
     * Not to silence because a footstep that cuts out when you open the inventory reads as
     * a bug rather than as a menu. Down rather than full because the menu is what the
     * player is attending to.
     */
    fun applyGain() {
        if (!isAvailable) return
        val effective = when {
            isPaused -> 0f
            isGuiOpen -> volume * GUI_DUCK
            else -> volume
        }
        alListenerf(AL_GAIN, effective)
    }

    /**
     * Plays a Sound.
     *
     * [position] places it in the world; null plays it at the listener, which is what the
     * player's own footsteps and landings want — they *are* at the listener.
     *
     * Pitch and gain are jittered per play. Without that, every footstep is the identical
     * waveform on an identical beat and walking sounds like a machine gun.
     */
    fun play(
        occasion: Occasion,
        material: Material,
        position: Vector3f? = null,
        gain: Float = 1f,
        pitch: Float = 1f
    ) {
        if (!isAvailable) return
        // A device can also disappear mid-session — headphones unplugged, a device
        // claimed by another app. Playing must never be the thing that kills a frame.
        try {
            playInternal(occasion, material, position, gain, pitch)
        } catch (e: Throwable) {
            isAvailable = false
            println("[sound] playback failed (${e::class.simpleName}); disabling audio")
        }
    }

    private fun playInternal(
        occasion: Occasion,
        material: Material,
        position: Vector3f?,
        gain: Float,
        pitch: Float
    ) {
        val buffer = buffers[occasion to material] ?: return
        val index = freeSource() ?: return
        val source = sources[index]

        val random = WorldGen.Random(playSeed++)
        val randomPitch = 0.94f + 0.12f * random.nextFloat()
        val randomGain = 0.85f + 0.15f * random.nextFloat()

        alSourceStop(source)
        alSourcei(source, AL_BUFFER, buffer)
        alSourcei(source, AL_SOURCE_RELATIVE, 0)
        alSourcef(source, AL_MIN_GAIN, 0f)
        alSourcef(source, AL_GAIN, (gain * randomGain).coerceIn(0f, 1f))
        alSourcef(source, AL_PITCH, pitch * randomPitch)

        if (position != null) {
            alSource3f(source, AL_POSITION, position.x, position.y, position.z)
            // Near sounds at full volume, distant ones quieter but still present. The 3-cell
            // reference distance stops a block you just broke from being a whisper.
            alSourcef(source, AL_REFERENCE_DISTANCE, 3f)
            alSourcef(source, AL_MAX_DISTANCE, 48f)
            alSourcef(source, AL_ROLLOFF_FACTOR, 1.1f)
        } else {
            alSource3f(source, AL_POSITION, 0f, 0f, 0f)
            alSourcef(source, AL_REFERENCE_DISTANCE, 1f)
            alSourcef(source, AL_MAX_DISTANCE, 64f)
            alSourcef(source, AL_ROLLOFF_FACTOR, 0f)
        }

        startedAt[index] = ++playCounter
        alSourcePlay(source)
    }

    /**
     * The index of a source that is not playing, or the oldest one if they are all busy.
     *
     * Stealing the oldest rather than dropping the new Sound, and capping the pool rather
     * than giving every event a voice: clipping is painful in a way that a dropped sound
     * during a burst of block-breaking is not.
     */
    private fun freeSource(): Int? {
        var oldestIndex = 0
        var oldestStart = Long.MAX_VALUE
        for (i in sources.indices) {
            if (alGetSourcei(sources[i], AL_SOURCE_STATE) != AL_PLAYING) return i
            if (startedAt[i] < oldestStart) {
                oldestStart = startedAt[i]
                oldestIndex = i
            }
        }
        return oldestIndex
    }

    private fun stopAllSources() {
        for (source in sources) alSourceStop(source)
    }

    /** Frees every OpenAL object. */
    fun stop() {
        if (!isAvailable) return
        try {
            stopAllSources()
            alDeleteBuffers(buffers.values.toIntArray())
            alDeleteSources(sources)
        } catch (e: Throwable) {
            // Teardown must not throw either: the window is already going away, and a
            // failure to free an audio buffer is not worth a stack trace on the way out.
            println("[sound] teardown problem (${e::class.simpleName}); ignoring")
        }
        buffers.clear()
        isAvailable = false
    }

    companion object {
        /**
         * How many Sounds can play at once. Conservative on purpose: the ceiling only
         * becomes audible when a burst of events overlaps, and a dropped sound there is far
         * less noticeable than the alternative.
         */
        const val VOICE_POOL = 16

        /** What the mix drops to while a GUI layer is open. */
        private const val GUI_DUCK = 0.45f
    }
}
