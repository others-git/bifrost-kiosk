package live.theundead.bifrost.kiosk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update from the Bifrost hub (the `update` controller command).
 *
 * Kiosks are offline, LAN-only — they never reach the internet. So the hub caches
 * the latest signed APK and serves it over the LAN; here we pull the manifest,
 * compare its `versionCode` to ours, download + SHA-256-verify the APK, and — as
 * **device owner** — install it **silently** via [PackageInstaller] (no prompt;
 * the app is killed and relaunched on the new version).
 *
 * Blocking — call [update] off the main thread. Auth is the same `bfr_` Bearer key
 * the heartbeat/voice use.
 */
class KioskUpdater(
    private val context: Context,
    private val serverBase: String,
    private val apiKey: String,
) {
    private data class Manifest(val versionCode: Int, val versionName: String, val sha256: String)

    /** Run the whole check → download → install. Returns a short status for logs. */
    fun update(): String {
        if (serverBase.isBlank() || apiKey.isBlank()) return "not configured"
        val manifest = fetchManifest() ?: return "no update cached on hub"
        val installed = BuildConfig.VERSION_CODE
        if (manifest.versionCode <= installed) {
            return "already current (installed=$installed, offered=${manifest.versionCode})"
        }
        val apk = File(context.cacheDir, "bifrost-update.apk")
        val sha = downloadApk(apk) ?: return "download failed"
        if (!sha.equals(manifest.sha256, ignoreCase = true)) {
            apk.delete()
            return "sha256 mismatch (got $sha, want ${manifest.sha256}) — aborted"
        }
        return install(apk, manifest.versionCode)
    }

    private fun fetchManifest(): Manifest? {
        val conn = open("/api/kiosks/update/manifest") ?: return null
        return try {
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val j = JSONObject(body)
                    Manifest(
                        versionCode = j.getInt("version_code"),
                        versionName = j.optString("version_name"),
                        sha256 = j.optString("sha256"),
                    )
                }
                HttpURLConnection.HTTP_NO_CONTENT -> null
                else -> {
                    Log.w(TAG, "manifest HTTP $code")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "manifest fetch failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Stream the APK to [dest], returning its hex SHA-256 (or null on failure). */
    private fun downloadApk(dest: File): String? {
        val conn = open("/api/kiosks/update/apk") ?: return null
        return try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "apk HTTP ${conn.responseCode}")
                return null
            }
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "apk download failed", e)
            dest.delete()
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Silent device-owner install of [apk] over our own package. */
    private fun install(apk: File, versionCode: Int): String {
        return try {
            val installer = context.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("bifrost-update", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // The result lands in InstallResultReceiver; on success the process
                // is replaced, so we mostly observe this for failures.
                val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "install committed for versionCode $versionCode")
            "installing $versionCode"
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            "install failed: ${e.message}"
        }
    }

    private fun open(path: String): HttpURLConnection? = try {
        (URL(serverBase.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
    } catch (e: Exception) {
        Log.e(TAG, "open $path failed", e)
        null
    }

    companion object {
        private const val TAG = "KioskUpdater"
        const val ACTION_INSTALL_RESULT = "live.theundead.bifrost.kiosk.INSTALL_RESULT"
    }
}
