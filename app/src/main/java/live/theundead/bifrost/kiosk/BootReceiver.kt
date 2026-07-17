package live.theundead.bifrost.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the kiosk up on boot **and after an OTA update**. As the Home launcher
 * the OS would eventually start us anyway, but launching explicitly (and
 * kicking the voice service) makes start-up deterministic — the wall panel
 * should be live the moment the tablet powers on.
 *
 * [Intent.ACTION_MY_PACKAGE_REPLACED] closes the update gap: replacing the APK
 * kills the old process and Android relaunches nothing on its own, leaving the
 * tablet on a dead screen until someone walks over, wakes it, and presses Home.
 * The broadcast is delivered to the NEW version right after install, so the
 * updated kiosk brings itself back — screen included ([DisplayPower.wake] arms
 * the turn-screen-on path, the only reliable screen-on on Android 15).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action ?: return) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                bringUpServices(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Post-OTA: relaunch with the wake path — the display is often
                // off (updates land on idle kiosks), and a plain start would
                // leave the new version invisible behind a dark screen.
                DisplayPower.wake(context)
                bringUpServices(context)
            }
        }
    }

    /** The hub link comes up with the device — before anyone touches it — so
     * remote sleep/wake works from the first minute after a power cut/update. */
    private fun bringUpServices(context: Context) {
        LinkService.start(context)
        if (Prefs(context).voiceEnabled) {
            live.theundead.bifrost.kiosk.voice.VoiceService.start(context)
        }
    }
}
