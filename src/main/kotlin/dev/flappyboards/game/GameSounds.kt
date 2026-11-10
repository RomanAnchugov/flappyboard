package dev.flappyboards.game

import com.intellij.openapi.diagnostic.Logger
import dev.flappyboards.settings.FlappyBoardsSettings
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineUnavailableException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Tiny synthesized sound effects: no audio assets, everything is generated as
 * 16-bit mono PCM at 44.1 kHz and played through short-lived Clips on a
 * daemon thread (never blocks the EDT). Soft sine-based tones with
 * exponential decay, kept quiet on purpose — this runs inside an IDE.
 *
 * If no audio device is available the whole object silently disables itself.
 */
object GameSounds {

    private val LOG = Logger.getInstance(GameSounds::class.java)
    private const val SR = 44100

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FlappyBoards-Sound").apply { isDaemon = true }
    }

    private val cache = HashMap<String, ByteArray>()
    private val lastPlays = HashMap<String, Long>()
    private var disabled = false

    fun flap() = play("flap", 40) { sweep(70, 500f, 900f, 0.22f, decayK = 26f) }
    fun ding() = play("ding", 60) { chime() }
    fun buzz() = play("buzz", 90) { buzz() }
    fun death() = play("death", 120) { fall() }
    fun best() = play("best", 300) { arpeggio() }

    private fun play(name: String, minIntervalMs: Long, synth: () -> ByteArray) {
        if (disabled || !FlappyBoardsSettings.instance.state.soundsEnabled) return

        val now = System.nanoTime()
        val last = lastPlays[name] ?: 0L
        if (now - last < minIntervalMs * 1_000_000) return
        lastPlays[name] = now

        val data = try {
            cache.getOrPut(name) { synth() }
        } catch (e: Exception) {
            LOG.warn("FlappyBoards: failed to synthesize sound '$name', disabling audio", e)
            disabled = true
            return
        }

        pool.execute {
            try {
                val clip = AudioSystem.getClip()
                clip.open(AudioFormat(SR.toFloat(), 16, 1, true, false), data, 0, data.size)
                clip.addLineListener { e ->
                    if (e.type == LineEvent.Type.STOP) clip.close()
                }
                clip.start()
            } catch (e: LineUnavailableException) {
                LOG.info("FlappyBoards: no audio line available, disabling sounds")
                disabled = true
            } catch (e: Exception) {
                LOG.warn("FlappyBoards: failed to play sound '$name', disabling audio", e)
                disabled = true
            }
        }
    }

    // ----------------------------------------------------------- synthesis

    private fun bytes(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Soft rising "whoop" — flap. */
    private fun sweep(ms: Int, f0: Float, f1: Float, vol: Float, decayK: Float): ByteArray {
        val n = SR * ms / 1000
        val s = FloatArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / n
            val f = f0 + (f1 - f0) * t
            phase += (2f * PI.toFloat() * f / SR)
            val env = exp(-decayK * (i.toFloat() / SR))
            s[i] = sin(phase) * env * vol
        }
        return bytes(s)
    }

    /** Bell-like two-partial chime — pipe passed. */
    private fun chime(): ByteArray {
        val ms = 320
        val n = SR * ms / 1000
        val s = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val env = exp(-9.0 * t).toFloat()
            val v = sin(2 * PI * 880 * t) +
                0.45 * sin(2 * PI * 1760 * t) +
                0.15 * sin(2 * PI * 2640 * t)
            s[i] = v.toFloat() * env * 0.2f
        }
        return bytes(s)
    }

    /** Short low muffled buzz — wrong key. */
    private fun buzz(): ByteArray {
        val ms = 110
        val n = SR * ms / 1000
        val s = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val env = min(1f, t * 90f) * exp(-14.0 * t).toFloat()
            val v = sin(2 * PI * 170 * t) * 0.7 +
                0.2 * sin(2 * PI * 340 * t) +
                0.1 * sin(2 * PI * 510 * t)
            s[i] = v.toFloat() * env * 0.16f
        }
        return bytes(s)
    }

    /** Descending slide — death. */
    private fun fall(): ByteArray {
        val ms = 380
        val n = SR * ms / 1000
        val s = FloatArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / n
            val vibrato = 1f + 0.02f * sin(2f * PI.toFloat() * 9f * (i.toFloat() / SR))
            val f = (420f + (90f - 420f) * t) * vibrato
            phase += (2f * PI.toFloat() * f / SR)
            val env = exp(-3.0 * (i.toFloat() / SR)).toFloat()
            s[i] = sin(phase) * env * 0.28f
        }
        return bytes(s)
    }

    /** Small major arpeggio — new best score. */
    private fun arpeggio(): ByteArray {
        val notes = floatArrayOf(880f, 1108.73f, 1318.51f) // A5, C#6, E6
        val noteMs = 120
        val n = SR * noteMs / 1000
        val s = FloatArray(n * notes.size)
        for ((k, f) in notes.withIndex()) {
            for (i in 0 until n) {
                val t = i.toFloat() / SR
                val env = exp(-8.0 * t).toFloat()
                val v = sin(2 * PI * f * t) + 0.3 * sin(2 * PI * f * 2 * t)
                val idx = k * n + i
                s[idx] = (s[idx] + v.toFloat() * env * 0.2f)
            }
        }
        return bytes(s)
    }
}
