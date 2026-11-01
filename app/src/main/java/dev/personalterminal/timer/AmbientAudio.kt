package dev.personalterminal.timer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Procedural focus ambience: rain, brown noise and mechanical-keyboard clicks, all synthesised
 * on the fly — the app ships no audio assets. The pomodoro drives the lifecycle (focus/stopwatch
 * plays, breaks and stop silence it); the chips on the timer screen and in settings switch the
 * pattern live through [setPattern] / [setVolume].
 */
object AmbientAudio {
    const val OFF = "off"
    const val RAIN = "rain"
    const val BROWN = "brown"
    const val KEYS = "keys"
    val PATTERNS = listOf(OFF, RAIN, BROWN, KEYS)

    private const val SAMPLE_RATE = 16_000

    @Volatile private var pattern = OFF
    @Volatile private var volume = 60
    /** True while a focus/stopwatch phase wants sound — patterns applied even if prefs arrive late. */
    @Volatile private var want = false
    @Volatile private var playing = false
    @Volatile private var paused = false

    private var track: AudioTrack? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rng = java.util.Random()

    fun setPattern(p: String) {
        val next = if (p in PATTERNS) p else OFF
        if (next == pattern) return
        pattern = next
        if (!want) return
        stopTrack()
        paused = false
        if (next == OFF) {
            playing = false
        } else {
            playing = true
            startTrack()
        }
    }

    fun setVolume(v: Int) {
        volume = v.coerceIn(0, 100)
        runCatching { track?.setVolume(volume / 100f) }
    }

    /** A focus (or stopwatch) phase wants sound — honour the current pattern. */
    fun focusOn() {
        want = true
        if (pattern == OFF || playing) {
            if (playing && paused) { paused = false; runCatching { track?.play() } }
            return
        }
        paused = false
        playing = true
        startTrack()
    }

    /** Break, stop, idle — release everything. */
    fun focusOff() {
        want = false
        playing = false
        paused = false
        stopTrack()
    }

    fun pause() { if (playing && !paused) { paused = true; runCatching { track?.pause() } } }
    fun resume() {
        if (!want || pattern == OFF) return
        if (playing && paused) { paused = false; runCatching { track?.play() } }
        else if (!playing) { playing = true; paused = false; startTrack() }
    }

    private fun startTrack() {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes((minBuf * 3).coerceAtLeast(16_384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        t.setVolume(volume / 100f)
        track = t
        val mode = pattern
        t.play()
        job = scope.launch {
            val gen: Gen? = when (mode) {
                RAIN -> RainGen()
                BROWN -> BrownGen()
                KEYS -> KeysGen()
                else -> null
            }
            if (gen == null) { runCatching { t.release() }; return@launch }
            val buf = ShortArray(1024)
            while (isActive && playing && pattern == mode) {
                if (paused) { delay(150); continue }
                gen.fill(buf)
                if (t.write(buf, 0, buf.size) <= 0) break
            }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    private fun stopTrack() {
        job?.cancel()
        job = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    // ------------------------------------------------------------------ generators

    /** One PCM16 mono generator at [SAMPLE_RATE]. */
    private interface Gen { fun fill(out: ShortArray) }

    /** Deep low-frequency rumble: integrated, leaked and low-passed white noise. */
    private class BrownGen : Gen {
        private var y = 0.0
        private var lp = 0.0
        private val r = java.util.Random()
        override fun fill(out: ShortArray) {
            for (i in out.indices) {
                val w = r.nextDouble() * 2 - 1
                y = (y + 0.02 * w) * 0.998
                if (y > 1.0) y = 1.0
                if (y < -1.0) y = -1.0
                lp += 0.6 * (y - lp)
                out[i] = (lp * 20000).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }

    /** Soft hiss body plus sparse decaying droplet pings. */
    private class RainGen : Gen {
        private var lp = 0.0
        private var env = 0.0
        private var phase = 0.0
        private var freq = 2400.0
        private val r = java.util.Random()
        override fun fill(out: ShortArray) {
            for (i in out.indices) {
                val w = r.nextDouble() * 2 - 1
                lp += 0.22 * (w - lp)
                if (env <= 0.001 && r.nextDouble() < 0.0035) {
                    env = 1.0
                    freq = 1400 + r.nextDouble() * 2600
                    phase = 0.0
                }
                var s = lp * 0.45
                if (env > 0.001) {
                    phase += 2 * PI * freq / SAMPLE_RATE
                    s += sin(phase) * env * 0.22
                    env *= 0.996
                }
                out[i] = (s * 20000).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }

    /** Mechanical-keyboard texture: short bright clicks, sometimes doubled, with typing pauses. */
    private class KeysGen : Gen {
        private var env = 0.0
        private var phase = 0.0
        private var freq = 900.0
        private var noise = 0.0
        private var nextIn = 0
        private val r = java.util.Random()
        override fun fill(out: ShortArray) {
            for (i in out.indices) {
                if (env <= 0.002) {
                    nextIn--
                    if (nextIn <= 0 && r.nextDouble() < 0.0006) {
                        env = 1.0
                        freq = 700 + r.nextDouble() * 900
                        phase = 0.0
                        nextIn = if (r.nextDouble() < 0.25) 55 else (250 + r.nextInt(900))
                    }
                }
                var s = 0.0
                if (env > 0.002) {
                    phase += 2 * PI * freq / SAMPLE_RATE
                    noise = noise * 0.6 + (r.nextDouble() * 2 - 1) * 0.4
                    s = (sin(phase) * 0.5 + noise) * env * 0.6
                    env *= 0.9955
                }
                out[i] = (s * 22000).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }
}
