package live.theundead.bifrost.kiosk

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for Bifrost's device-enrollment seam.
 *
 * Redeems a single-use, short-lived pairing token (scanned from the dashboard's
 * "Pair a device" QR) for a long-lived `bfr_` Bearer key:
 *
 *   POST {baseUrl}/api/enrollment/redeem  { token, device_name }
 *     201 → { key, prefix, name }   (the key, shown once)
 *     401 → invalid / expired / already-used token
 *
 * Mirrors [live.theundead.bifrost.kiosk.voice.BifrostVoiceClient]'s plain
 * HttpURLConnection + org.json style — no extra HTTP dependency.
 */
class EnrollmentClient(private val baseUrl: String) {

    sealed class Result {
        /** 201 — pairing succeeded; [key] is the minted `bfr_` Bearer key. */
        data class Success(val key: String, val prefix: String, val name: String) : Result()

        /** 401 — token was invalid, expired, or already used. */
        object Invalid : Result()

        /** Any other HTTP status or transport failure. [message] is user-facing. */
        data class Error(val message: String) : Result()
    }

    /** Blocking call — invoke off the main thread. */
    fun redeem(token: String, deviceName: String): Result {
        val url = URL(baseUrl.trimEnd('/') + "/api/enrollment/redeem")
        val body = JSONObject().apply {
            put("token", token)
            put("device_name", deviceName)
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
            }
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            when {
                code == 201 || code in 200..299 -> {
                    val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val json = JSONObject(resp)
                    val key = json.optString("key", "")
                    if (key.isBlank()) {
                        Result.Error("Pairing response had no key")
                    } else {
                        Result.Success(
                            key = key,
                            prefix = json.optString("prefix", ""),
                            name = json.optString("name", deviceName),
                        )
                    }
                }
                code == 401 -> Result.Invalid
                else -> {
                    val resp = conn.errorStream?.bufferedReader()
                        ?.use(BufferedReader::readText).orEmpty()
                    Log.w(TAG, "enrollment HTTP $code: $resp")
                    Result.Error("Pairing failed (HTTP $code)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "enrollment redeem failed", e)
            Result.Error("Network error reaching ${baseUrl}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "EnrollmentClient"
    }
}
