package live.theundead.bifrost.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Device-owner admin. Set with:
 *   adb shell dpm set-device-owner live.theundead.bifrost.kiosk/.AdminReceiver
 * (the device must have no accounts). Being device owner is what unlocks
 * [android.app.admin.DevicePolicyManager.setLockTaskPackages] /
 * [android.app.admin.DevicePolicyManager.setStatusBarDisabled] so the kiosk
 * can hard-pin itself — see [LockTask].
 */
class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) = ComponentName(context, AdminReceiver::class.java)
    }
}
