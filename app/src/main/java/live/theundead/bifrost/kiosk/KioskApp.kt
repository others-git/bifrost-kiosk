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
            nm.createNotificationChannel(
                NotificationChannel(
                    LINK_CHANNEL_ID,
                    "Hub link",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "Keeps the connection to the Bifrost hub alive " +
                        "so remote sleep/wake works while the screen is off."
                },
            )
        }
    }

    companion object {
        const val VOICE_CHANNEL_ID = "voice_satellite"
        const val LINK_CHANNEL_ID = "hub_link"
    }
}
