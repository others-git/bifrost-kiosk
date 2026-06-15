package live.theundead.bifrost.kiosk.voice

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for Bifrost's text→action voice seam.
 *
 * Posts to the **shipped** `POST /api/voice/command { text, context? }`
 * (Bifrost M23 P1), which returns `{ ok, said, clauses[] }`. We send transcribed
 * text (STT happens on-device via Vosk). Bifrost M23 P2 also shipped a
 * server-side `POST /api/voice/listen` multipart-audio endpoint; we stay on
 * on-device STT for now (half-duplex, no upload), but that seam is the path to
 * server-side STT later. `said` is read back via TTS.
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

    companion object {
        private const val TAG = "BifrostVoiceClient"
    }
}
