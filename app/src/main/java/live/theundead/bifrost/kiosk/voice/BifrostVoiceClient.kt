package live.theundead.bifrost.kiosk.voice

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for Bifrost's voice seams.
 *
 * Two paths, both authenticated with the same `bfr_` Bearer key:
 *  - [command]: `POST /api/voice/command { text, context? }` (M23 P1) — the
 *    text→action seam, fed the **on-device Vosk** transcript. The reliable
 *    fallback that works with no server STT.
 *  - [listen]: `POST /api/voice/listen` (M23 P2) — multipart audio upload that
 *    runs server-side STT (Speaches/whisper) then the *same* command seam. More
 *    accurate than the on-device wake model, so it's preferred when available;
 *    it degrades to [command] when the server has no transcription model (503)
 *    or is unreachable. Both return `{ ok, said, clauses[] }`; `said` is read
 *    back via TTS.
 *
 * Auth: a `bfr_` Bearer key, matching `/api/v1` + `/mcp`. The voice seam now
 * accepts **either** a browser session **or** a `bfr_` Bearer key server-side
 * (`voice_authed` in `../bifrost` `src/api/voice.rs`), so the headless satellite
 * authenticates with its minted key like any public-API client.
 */
class BifrostVoiceClient(
    private val serverBase: String,
    private val endpointPath: String,
    private val apiKey: String,
) {
    data class Reply(val ok: Boolean, val said: String, val authError: Boolean = false)

    /** Blocking call — invoke off the main thread. Returns null on transport failure. */
    fun command(text: String, room: String?): Reply? {
        val url = URL(serverBase.trimEnd('/') + "/" + endpointPath.trimStart('/'))
        val body = JSONObject().apply {
            put("text", text)
            if (!room.isNullOrBlank()) {
                put("context", JSONObject().put("room", room))
            }
        }.toString()

        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "voice command HTTP $code: $resp")
                // 401/403 mean the device's API key is missing/invalid — surface a
                // specific "needs (re)pairing" message rather than a generic mishear.
                return Reply(false, "", authError = code == 401 || code == 403)
            }
            val json = JSONObject(resp)
            Reply(
                ok = json.optBoolean("ok", false),
                said = json.optString("said", ""),
            )
        } catch (e: Exception) {
            Log.e(TAG, "voice command failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Synthesize spoken audio for [text] via `POST /api/voice/speak` and return
     * the raw audio bytes (whatever container the hub's TTS produced — typically
     * wav). Returns **null** on any failure — no TTS model configured (503),
     * upstream error, or transport failure — so the caller can fall back to
     * on-device TTS and never go silent. Blocking — invoke off the main thread.
     */
    fun speak(text: String): ByteArray? {
        if (text.isBlank()) return null
        val url = URL(serverBase.trimEnd('/') + SPEAK_PATH)
        val body = JSONObject().put("text", text).toString()

        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 30_000 // synthesis can take a beat
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "audio/*")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "voice speak HTTP $code — falling back to on-device TTS")
                return null
            }
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "voice speak failed — falling back to on-device TTS", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Server-side STT: POST the captured command [wav] (16 kHz mono WAV) to
     * `/api/voice/listen` as multipart, with optional [room] context. Returns
     * **null to signal "fall back to [command]"** — that covers a 503 (no
     * transcription model configured server-side), a 5xx (STT upstream failed),
     * and any transport failure, so the caller can retry with the Vosk
     * transcript. A 401/403 surfaces as an auth error (needs re-pairing) without
     * falling back, since the text path would fail the same way.
     *
     * Blocking — invoke off the main thread.
     */
    fun listen(wav: ByteArray, room: String?): Reply? {
        val url = URL(serverBase.trimEnd('/') + LISTEN_PATH)
        val boundary = "----bifrost${System.nanoTime()}"

        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 20_000 // server STT can take a beat (CPU whisper)
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { writeMultipart(it, boundary, wav, room) }

            val code = conn.responseCode
            when {
                code in 200..299 -> {
                    val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val json = JSONObject(resp)
                    Reply(ok = json.optBoolean("ok", false), said = json.optString("said", ""))
                }
                code == 401 || code == 403 -> Reply(false, "", authError = true)
                else -> {
                    // 503 = no transcription model; 502 = STT upstream failed; etc.
                    Log.w(TAG, "voice listen HTTP $code — falling back to text command")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice listen failed — falling back to text command", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Hand-rolled multipart body: the `file` audio part + an optional `room` field. */
    private fun writeMultipart(out: OutputStream, boundary: String, wav: ByteArray, room: String?) {
        val header = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"command.wav\"\r\n")
            append("Content-Type: audio/wav\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.write(wav)
        val tail = buildString {
            append("\r\n")
            if (!room.isNullOrBlank()) {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"room\"\r\n\r\n")
                append(room).append("\r\n")
            }
            append("--").append(boundary).append("--\r\n")
        }
        out.write(tail.toByteArray())
        out.flush()
    }

    companion object {
        private const val TAG = "BifrostVoiceClient"
        private const val LISTEN_PATH = "/api/voice/listen"
        private const val SPEAK_PATH = "/api/voice/speak"
    }
}
