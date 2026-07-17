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
    /** Result of a check-in: a queued command, the hub-assigned room, and the
     * microphone-presence config (the app starts/stops [NoiseMonitor] from it). */
    data class Result(
        val command: String?,
        val room: String?,
        val micPresence: Boolean,
        val micSensitivity: String?,
    )

    /** Battery / power telemetry sent with the heartbeat. Any field may be null
     * (a desktop "kiosk" has no battery; some props aren't always available). */
    data class Battery(
        val level: Int?,
        val charging: Boolean?,
        val voltageMv: Int?,
        val currentUa: Int?,
        val tempDeciC: Int?,
        val source: String?,
    )

    /** Heartbeat; returns the queued command + assigned room, or null on failure. */
    fun checkin(appVersion: String, screenOn: Boolean, battery: Battery? = null): Result? {
        if (serverBase.isBlank() || apiKey.isBlank()) return null
        val url = URL(serverBase.trimEnd('/') + "/api/kiosks/checkin")
        val body = JSONObject().apply {
            put("app_version", appVersion)
            put("screen_on", screenOn)
            battery?.let { b ->
                b.level?.let { put("battery_level", it) }
                b.charging?.let { put("battery_charging", it) }
                b.voltageMv?.let { put("battery_voltage_mv", it) }
                b.currentUa?.let { put("battery_current_ua", it) }
                b.tempDeciC?.let { put("battery_temp_dc", it) }
                b.source?.let { put("power_source", it) }
            }
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
                micPresence = json.optBoolean("mic_presence", false),
                micSensitivity = json.optString("mic_sensitivity", "").ifBlank { null },
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkin failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Report a sound-level edge (or periodic level) to the hub's noise endpoint
     * (`POST /api/kiosks/self/noise`, `bfr_key` cookie auth — the same identity
     * cookie the kiosk WebView carries). Fire-and-forget; blocking. */
    fun reportNoise(elevated: Boolean, levelDb: Double): Boolean {
        if (serverBase.isBlank() || apiKey.isBlank()) return false
        val url = URL(serverBase.trimEnd('/') + "/api/kiosks/self/noise")
        val body = JSONObject().apply {
            put("elevated", elevated)
            put("level", levelDb)
        }.toString()
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Cookie", "bfr_key=$apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode in 200..299
            if (!ok) Log.w(TAG, "noise report HTTP ${conn.responseCode}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "noise report failed", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "KioskCheckin"
    }
}
