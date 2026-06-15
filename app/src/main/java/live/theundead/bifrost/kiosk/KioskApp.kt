package live.theundead.bifrost.kiosk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    VOICE_CHANNEL_ID,
                    "Voice satellite",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Always-on listening for the wake word." },
            )
        }
    }

    companion object {
        const val VOICE_CHANNEL_ID = "voice_satellite"
    }
}
