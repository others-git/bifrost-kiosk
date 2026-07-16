package live.theundead.bifrost.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the kiosk up on boot. As the Home launcher the OS would eventually
 * start us anyway, but launching explicitly (and kicking the voice service)
 * makes start-up deterministic — the wall panel should be live the moment the
 * tablet powers on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            // The hub link comes up with the device — before anyone touches it —
            // so remote sleep/wake works from the first minute after a power cut.
            LinkService.start(context)
            val prefs = Prefs(context)
            if (prefs.voiceEnabled) {
                live.theundead.bifrost.kiosk.voice.VoiceService.start(context)
            }
        }
    }
}
