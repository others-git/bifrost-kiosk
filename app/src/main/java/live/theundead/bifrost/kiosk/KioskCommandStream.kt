package live.theundead.bifrost.kiosk

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Live server→kiosk command channel: a long-lived SSE connection to
 * `GET /api/kiosks/stream` (Bifrost M29). Controller commands (sleep / wake /
 * lock) arrive **instantly** here instead of waiting for the next check-in poll.
 *
 * The check-in heartbeat ([KioskCheckin]) stays as a slow liveness ping and the
 * offline fallback (a command issued while disconnected is queued server-side
 * and delivered on the next check-in) — so [MainActivity] de-dups a command that
 * the stream already handled moments before.
 *
 * Reconnects with capped backoff (the stream 404s until the kiosk has checked in
 * once, so early retries are expected). No external deps — a plain streaming
 * HTTP read, same spirit as the hub's own SSE consumer.
 */
class KioskCommandStream(
    private val serverBase: String,
    private val apiKey: String,
    private val onCommand: (String) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running || serverBase.isBlank() || apiKey.isBlank()) return
        running = true
        worker = thread(name = "kiosk-sse", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        var backoffMs = 2_000L
        while (running) {
            try {
                readStream()
                backoffMs = 2_000L // clean close (server restart) — reconnect promptly
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "stream error: ${e.message}")
            }
            if (!running) break
            try {
                Thread.sleep(backoffMs)
            } catch (e: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private fun readStream() {
        val url = URL(serverBase.trimEnd('/') + "/api/kiosks/stream")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 0 // long-lived; the server keep-alives every 15s
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "stream HTTP $code (will retry)")
                return
            }
            conn.inputStream.bufferedReader().use { reader ->
                var event = "message"
                while (running) {
                    val line = reader.readLine() ?: break // server closed
                    when {
                        line.startsWith(":") -> {} // comment / keep-alive ping
                        line.startsWith("event:") -> event = line.substringAfter("event:").trim()
                        line.startsWith("data:") -> {
                            val data = line.substringAfter("data:").trim()
                            if (event == "command" && data.isNotEmpty()) onCommand(data)
                        }
                        line.isEmpty() -> event = "message" // end of one event
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "KioskCommandStream"
    }
}
