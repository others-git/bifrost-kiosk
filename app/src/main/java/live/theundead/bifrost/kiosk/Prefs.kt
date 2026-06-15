package live.theundead.bifrost.kiosk

import android.content.Context
import androidx.core.content.edit

/**
 * All user-configurable kiosk state, backed by SharedPreferences.
 *
 * Defaults match the reference Bifrost deployment (see README). The whole point
 * of keeping these out of code is that the same APK drops onto any tablet and is
 * pointed at any hub from the maintenance screen.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("bifrost_kiosk", Context.MODE_PRIVATE)

    var dashboardUrl: String
        get() = sp.getString(KEY_DASHBOARD_URL, DEFAULT_DASHBOARD_URL)!!
        set(v) = sp.edit { putString(KEY_DASHBOARD_URL, v.trim()) }

    /** Base URL of the Bifrost server for the voice API (may differ from the dashboard host). */
    var serverBase: String
        get() = sp.getString(KEY_SERVER_BASE, DEFAULT_DASHBOARD_URL)!!
        set(v) = sp.edit { putString(KEY_SERVER_BASE, v.trim().trimEnd('/')) }

    /** Bearer key (Bifrost `bfr_…`). Empty until provisioned. */
    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "")!!
        set(v) = sp.edit { putString(KEY_API_KEY, v.trim()) }

    /** Path of the text→action voice seam on the server. */
    var voiceEndpoint: String
        get() = sp.getString(KEY_VOICE_ENDPOINT, DEFAULT_VOICE_ENDPOINT)!!
        set(v) = sp.edit { putString(KEY_VOICE_ENDPOINT, v.trim()) }

    var wakeWord: String
        get() = sp.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD)!!
        set(v) = sp.edit { putString(KEY_WAKE_WORD, v.trim().lowercase()) }

    /** Room this device sits in; sent as `context.room` so bare commands resolve. */
    var roomContext: String
        get() = sp.getString(KEY_ROOM, "")!!
        set(v) = sp.edit { putString(KEY_ROOM, v.trim()) }

    var exitPin: String
        get() = sp.getString(KEY_EXIT_PIN, DEFAULT_PIN)!!
        set(v) = sp.edit { putString(KEY_EXIT_PIN, v.trim()) }

    var voiceEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE_ENABLED, true)
        set(v) = sp.edit { putBoolean(KEY_VOICE_ENABLED, v) }

    companion object {
        const val DEFAULT_DASHBOARD_URL = "https://bifrost.theundead.live"
        const val DEFAULT_VOICE_ENDPOINT = "/api/voice/command"
        const val DEFAULT_WAKE_WORD = "bifrost"
        const val DEFAULT_PIN = "0000"

        private const val KEY_DASHBOARD_URL = "dashboard_url"
        private const val KEY_SERVER_BASE = "server_base"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_VOICE_ENDPOINT = "voice_endpoint"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_ROOM = "room_context"
        private const val KEY_EXIT_PIN = "exit_pin"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
    }
}
