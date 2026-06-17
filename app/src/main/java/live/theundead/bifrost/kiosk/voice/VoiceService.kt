package live.theundead.bifrost.kiosk.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import live.theundead.bifrost.kiosk.KioskApp
import live.theundead.bifrost.kiosk.MainActivity
import live.theundead.bifrost.kiosk.Prefs
import live.theundead.bifrost.kiosk.R

/**
 * Always-on voice satellite as a `microphone` foreground service, started on
 * boot and kept alongside the locked WebView. The kiosk Activity hosts the
 * display; this service owns the mic — co-resident in one device-owner app so
 * the satellite never fights the kiosk for foreground (see README rationale).
 */
class VoiceService : LifecycleService() {

    private var pipeline: VoicePipeline? = null

    override fun onCreate() {
        super.onCreate()
        // A microphone-type FGS requires RECORD_AUDIO to already be granted
        // (Android 14+), so decide the type *before* startForeground — calling it
        // with TYPE_MICROPHONE ungranted throws and crashes the whole app. As a
        // soft kiosk (not device owner) we can't self-grant, so degrade
        // gracefully: come up untyped and stop, leaving the kiosk untouched.
        val hasMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        startForegroundNotification(hasMic)
        if (!hasMic) {
            Log.w(TAG, "no RECORD_AUDIO permission — voice idle (kiosk unaffected)")
            stopSelf()
            return
        }
        pipeline = VoicePipeline(this, Prefs(this)).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PTT -> pipeline?.beginPushToTalk()
            ACTION_PTT_STOP -> pipeline?.endPushToTalk()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startForegroundNotification(micGranted: Boolean) {
        val tap = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val listeningFor = if (micGranted) "Listening for “${Prefs(this).wakeWord}”" else "Voice off"
        val notification = NotificationCompat.Builder(this, KioskApp.VOICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(listeningFor)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()

        // Only claim the microphone type when we actually hold RECORD_AUDIO;
        // otherwise come up as an untyped FGS (TYPE_NONE) — still satisfies the
        // startForegroundService() contract without tripping the type check.
        val type = if (micGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    override fun onDestroy() {
        pipeline?.shutdown()
        pipeline = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIF_ID = 42
        private const val ACTION_PTT = "live.theundead.bifrost.kiosk.PTT"
        private const val ACTION_PTT_STOP = "live.theundead.bifrost.kiosk.PTT_STOP"

        /** Push-to-talk **press**: start capturing now (skip the wake word) and hold
         * the window open until [pushToTalkStop]. No-op if voice is off / mic-less. */
        fun pushToTalk(context: Context) {
            context.startService(
                Intent(context, VoiceService::class.java).setAction(ACTION_PTT),
            )
        }

        /** Push-to-talk **release**: stop capturing and dispatch the held command. */
        fun pushToTalkStop(context: Context) {
            context.startService(
                Intent(context, VoiceService::class.java).setAction(ACTION_PTT_STOP),
            )
        }

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
