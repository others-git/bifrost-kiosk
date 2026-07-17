package live.theundead.bifrost.kiosk

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Always-on sound-LEVEL monitor — the kiosk microphone as a room presence
 * sensor. **Privacy by construction:** each audio window is reduced to a single
 * RMS number and discarded; nothing is stored, and nothing but the level (dBFS)
 * and an elevated/quiet verdict ever leaves the device.
 *
 * Detection lives in [NoiseDetector] (pure, unit-tested): a slow EMA of quiet
 * windows tracks the room's ambient baseline (HVAC, fridge hum), "elevated"
 * fires when the level exceeds baseline + margin for two consecutive windows,
 * and clears only after a hangover of quiet — so the hub sees *edges*, not a
 * stream of readings. A periodic level report (~30s) keeps the Clients view's
 * telemetry live even when nothing changes; the hub's changed-only pipeline
 * gate keeps that quiet downstream.
 */
class NoiseMonitor(
    sensitivity: String?,
    /** Called on elevated/quiet edges AND periodic level reports (off main). */
    private val onReport: (elevated: Boolean, levelDb: Double) -> Unit,
) {
    private val detector = NoiseDetector(marginDb(sensitivity))
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "noise-monitor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    @SuppressLint("MissingPermission") // caller checks LockTask.hasMicPermission
    private fun run() {
        val min = AudioRecord.getMinBufferSize(RATE_HZ, CHANNEL, ENCODING)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE_HZ, CHANNEL, ENCODING,
                maxOf(min, WINDOW_SAMPLES * 4),
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed — noise monitor idle")
            rec?.release()
            return
        }
        rec.startRecording()
        Log.i(TAG, "listening (level-only, margin ${detector.marginDb} dB)")
        val buf = ShortArray(WINDOW_SAMPLES)
        var lastLevelReport = 0L
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    SystemClock.sleep(200)
                    continue
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0
                    sum += v * v
                }
                val db = 20 * log10(sqrt(sum / n).coerceAtLeast(1e-6))
                val now = SystemClock.elapsedRealtime()
                val edge = detector.onWindow(db, now)
                if (edge != null) {
                    lastLevelReport = now
                    onReport(edge, db)
                } else if (now - lastLevelReport >= LEVEL_REPORT_MS) {
                    lastLevelReport = now
                    onReport(detector.elevated, db)
                }
            }
        } catch (_: InterruptedException) {
            // stop() — fall through to release.
        } finally {
            runCatching {
                rec.stop()
                rec.release()
            }
        }
    }

    companion object {
        private const val TAG = "NoiseMonitor"
        private const val RATE_HZ = 8000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** ~250ms analysis window. */
        private const val WINDOW_SAMPLES = RATE_HZ / 4

        /** Cadence of no-edge level telemetry. */
        private const val LEVEL_REPORT_MS = 30_000L

        /** How far above the ambient baseline counts as "someone's here". */
        fun marginDb(sensitivity: String?): Double = when (sensitivity) {
            "low" -> 15.0
            "high" -> 6.0
            else -> 10.0
        }
    }
}

/**
 * The pure edge detector behind [NoiseMonitor] — separable so the two-window
 * confirmation, adaptive baseline, and hangover are unit-testable without an
 * [AudioRecord].
 *
 * - **Baseline** learns from quiet windows on a slow EMA, and from loud windows
 *   on a MUCH slower one (~20min time constant): a conversation can't quickly
 *   teach itself into the baseline, but a genuinely changed ambience (an AC
 *   spinning up, a too-low initial estimate) converges within the hour instead
 *   of reading as presence forever. Floored so a dead-silent room doesn't
 *   drive the threshold absurdly low.
 * - **Two consecutive** over-threshold windows confirm an elevated edge (a
 *   single click/pop isn't presence).
 * - **Hangover**: once elevated, it takes [HANGOVER_MS] of continuous quiet to
 *   emit the quiet edge — conversation pauses don't flap the sensor.
 */
class NoiseDetector(val marginDb: Double) {
    var baselineDb = -60.0
        private set
    var elevated = false
        private set
    private var overCount = 0
    private var lastLoudAt = 0L

    /** Feed one window's level; returns the edge it produced, if any. */
    fun onWindow(db: Double, nowMs: Long): Boolean? {
        if (db > baselineDb + marginDb) {
            lastLoudAt = nowMs
            overCount++
            // Creep toward sustained noise so a permanent ambience change stops
            // counting as presence eventually (see the class docs).
            baselineDb = baselineDb * (1 - LOUD_ALPHA) + db * LOUD_ALPHA
            if (!elevated && overCount >= 2) {
                elevated = true
                return true
            }
            return null
        }
        overCount = 0
        baselineDb = (baselineDb * (1 - QUIET_ALPHA) + db * QUIET_ALPHA)
            .coerceAtLeast(BASELINE_FLOOR_DB)
        if (elevated && nowMs - lastLoudAt >= HANGOVER_MS) {
            elevated = false
            return false
        }
        return null
    }

    companion object {
        /** Quiet-window EMA weight — ≈1min time constant at 250ms windows. */
        private const val QUIET_ALPHA = 0.004

        /** Loud-window EMA weight — ≈20min time constant at 250ms windows:
         * slow enough that a conversation barely moves the baseline, fast
         * enough that a new constant ambience converges within the hour. */
        private const val LOUD_ALPHA = 0.0002

        /** A silent room can't drag the threshold below (floor + margin). */
        private const val BASELINE_FLOOR_DB = -75.0

        /** Continuous quiet required to clear the elevated state. */
        const val HANGOVER_MS = 20_000L
    }
}
