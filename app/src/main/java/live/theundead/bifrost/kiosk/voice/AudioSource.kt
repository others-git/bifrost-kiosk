package live.theundead.bifrost.kiosk.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * A source of 16 kHz mono 16-bit PCM for the speech engine. Abstracting it lets
 * us feed the **exact same** [HybridSpeechEngine] pipeline from the live mic in
 * production, or from a WAV file when debugging (see the replay harness) — so the
 * voice path can be exercised reproducibly without anyone talking to the tablet.
 */
interface AudioSource {
    val label: String

    /** Acquire the source. Returns false if unavailable (e.g. mic init failed). */
    fun open(): Boolean

    /** Fill [buf]; return bytes read, 0 if none right now, or -1 at end-of-stream. */
    fun read(buf: ByteArray): Int

    fun close()
}

const val SAMPLE_RATE_HZ = 16000

/** Live microphone via [AudioRecord] — the production source. */
class MicAudioSource : AudioSource {
    override val label = "mic"
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO auto-granted to the device-owner kiosk
    override fun open(): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, ENCODING)
        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ, CHANNEL, ENCODING,
                maxOf(min, FRAME_BYTES * 4),
            )
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            r?.release()
            return false
        }
        r.startRecording()
        record = r
        return true
    }

    override fun read(buf: ByteArray): Int = record?.read(buf, 0, buf.size) ?: -1

    override fun close() {
        runCatching { record?.stop(); record?.release() }
        record = null
    }

    companion object {
        private const val TAG = "MicAudioSource"
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}

/**
 * Replays a 16 kHz mono 16-bit PCM WAV as if it were the mic. When
 * [realTimePaced] it sleeps to match wall-clock duration so the pipeline's
 * silence/window timing behaves exactly as live; otherwise it feeds as fast as
 * possible (quicker iteration for pure recognition checks). Returns -1 once the
 * file is exhausted so the engine can finalize the utterance.
 */
class FileAudioSource(
    private val wav: File,
    private val realTimePaced: Boolean = true,
) : AudioSource {
    override val label = "file:${wav.name}"
    private var raf: RandomAccessFile? = null
    private var remaining = 0L

    override fun open(): Boolean {
        return runCatching {
            val f = RandomAccessFile(wav, "r")
            val dataOffset = findDataChunk(f)
            f.seek(dataOffset)
            remaining = f.length() - dataOffset
            raf = f
            Log.i(TAG, "replaying $wav (${remaining} PCM bytes, paced=$realTimePaced)")
            true
        }.getOrElse { Log.e(TAG, "open failed", it); false }
    }

    override fun read(buf: ByteArray): Int {
        val f = raf ?: return -1
        if (remaining <= 0) return -1
        val want = minOf(buf.size.toLong(), remaining).toInt()
        val n = f.read(buf, 0, want)
        if (n <= 0) return -1
        remaining -= n
        if (realTimePaced) {
            // n bytes = n/2 samples; pace to their real duration.
            val ms = (n / 2) * 1000L / SAMPLE_RATE_HZ
            if (ms > 0) runCatching { Thread.sleep(ms) }
        }
        return n
    }

    override fun close() {
        runCatching { raf?.close() }
        raf = null
    }

    /** Locate the start of the WAV `data` chunk (handles non-canonical headers). */
    private fun findDataChunk(f: RandomAccessFile): Long {
        val header = ByteArray(12)
        f.readFully(header) // "RIFF"<size>"WAVE"
        var pos = 12L
        val sz = ByteArray(8)
        while (pos + 8 <= f.length()) {
            f.seek(pos)
            f.readFully(sz)
            val id = String(sz, 0, 4, Charsets.US_ASCII)
            val chunkLen = (sz[4].toInt() and 0xFF) or ((sz[5].toInt() and 0xFF) shl 8) or
                ((sz[6].toInt() and 0xFF) shl 16) or ((sz[7].toInt() and 0xFF) shl 24)
            if (id == "data") return pos + 8
            pos += 8 + chunkLen + (chunkLen and 1) // chunks are word-aligned
        }
        return 44L // fall back to the canonical header size
    }

    companion object {
        private const val TAG = "FileAudioSource"
    }
}

/** ~100 ms of 16 kHz mono 16-bit audio per engine read. */
const val FRAME_BYTES = 3200
