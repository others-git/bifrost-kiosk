package live.theundead.bifrost.kiosk

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts the kiosk heartbeat to `POST /api/kiosks/checkin` (Bifrost M29). This is
 * what makes a paired tablet *appear* in the hub's Clients view and stay marked
 * online; it also picks up any queued controller command (sleep / wake / lock),
 * which the hub clears on delivery.
 *
 * Auth is the same `bfr_` Bearer key the voice seam uses ([BifrostVoiceClient]).
 * Blocking — call off the main thread.
 */
class KioskCheckin(
    private val serverBase: String,
    private val apiKey: String,
) {
    /** Result of a check-in: a queued command and/or the hub-assigned room. */
    data class Result(val command: String?, val room: String?)

    /** Heartbeat; returns the queued command + assigned room, or null on failure. */
    fun checkin(appVersion: String, screenOn: Boolean): Result? {
        if (serverBase.isBlank() || apiKey.isBlank()) return null
        val url = URL(serverBase.trimEnd('/') + "/api/kiosks/checkin")
        val body = JSONObject().apply {
            put("app_version", appVersion)
            put("screen_on", screenOn)
        }.toString()

        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "checkin HTTP $code")
                return null
            }
            val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(resp)
            Result(
                command = json.optString("command", "").ifBlank { null },
                room = json.optString("room", "").ifBlank { null },
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkin failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "KioskCheckin"
    }
}
