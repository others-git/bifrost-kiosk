package live.theundead.bifrost.kiosk.voice

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the home's voice vocabulary from Bifrost's
 * `GET /api/voice/vocabulary` (shipped server-side). The response is
 * `{ "words": [...] }` — the command-grammar keywords PLUS every enabled
 * room/device/scene name, tokenized to lowercase words (the wake word
 * "bifrost" is included). This is the exact set the on-device recognizer should
 * be biased toward (see [VoskSpeechEngine.buildGrammar]).
 *
 * Auth is the same `bfr_` Bearer key the voice command uses ([serverBase] +
 * [apiKey], straight off Prefs). Returns null on any failure (offline,
 * unauthorized, malformed) so the engine can fall back to an open recognizer.
 */
class VocabularyClient(
    private val serverBase: String,
    private val apiKey: String,
) {
    /** Blocking call — invoke off the main/audio thread. Null on any failure. */
    fun fetch(): List<String>? {
        if (serverBase.isBlank()) return null
        val url = URL(serverBase.trimEnd('/') + PATH)

        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "vocabulary HTTP $code")
                return null
            }
            val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            parseWords(resp)
        } catch (e: Exception) {
            Log.w(TAG, "vocabulary fetch failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "VocabularyClient"
        private const val PATH = "/api/voice/vocabulary"

        /**
         * Pull `words` out of the response into a clean, lowercased, de-duped
         * list. Returns null if the payload has no usable words (so the caller
         * treats an empty/garbage response the same as a failed fetch and keeps
         * the open recognizer).
         */
        fun parseWords(body: String): List<String>? {
            val arr: JSONArray = runCatching { JSONObject(body).optJSONArray("words") }
                .getOrNull() ?: return null
            val words = buildList {
                for (i in 0 until arr.length()) {
                    val w = arr.optString(i, "").lowercase().trim()
                    if (w.isNotEmpty()) add(w)
                }
            }.distinct()
            return words.ifEmpty { null }
        }
    }
}
