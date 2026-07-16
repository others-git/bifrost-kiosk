package live.theundead.bifrost.kiosk

import android.content.Context
import android.content.Intent

/**
 * The one shared "light the panel" primitive behind the remote `wake` command.
 *
 * Modern Android turns the screen on for exactly one app-reachable reason: an
 * activity with `setTurnScreenOn(true)` **becomes visible/resumed**. Setting
 * that flag on an already-stopped activity does nothing, and the legacy
 * `SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP` nudge is deprecated and
 * unreliable on recent releases (the reason "wake" silently no-oped on the
 * Android 15 wall tablet while "sleep" worked fine). So: relaunch the kiosk
 * activity (device owners are exempt from background-activity-launch
 * restrictions; `singleTask` just brings the live instance forward) with a
 * wake extra — [MainActivity] sees it in onCreate/onNewIntent, arms the
 * turn-screen-on window flags for this wake only, and the resume is what
 * lights the display. The wake-lock nudge stays as belt-and-braces for older
 * builds where it still works.
 */
object DisplayPower {
    /** Intent extra marking a launch as a remote wake — arms the turn-screen-on
     * flags for this landing only, so a stray relaunch never lights the panel. */
    const val EXTRA_WAKE = "live.theundead.bifrost.kiosk.WAKE"

    fun wake(context: Context) {
        LockTask.nudgeDisplayOn(context)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            it.putExtra(EXTRA_WAKE, true)
            context.startActivity(it)
        }
    }
}
