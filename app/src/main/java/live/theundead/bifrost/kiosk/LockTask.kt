package live.theundead.bifrost.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Thin wrapper over the device-owner policy surface used by the kiosk.
 *
 * Every call degrades gracefully when the app is **not** device owner (e.g. a
 * fresh sideload before `dpm set-device-owner`): the kiosk still renders, it
 * just can't hard-pin. That keeps the APK installable and debuggable without a
 * provisioned device.
 */
object LockTask {
    private const val TAG = "LockTask"

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /** Allow-list ourselves + (idempotently) become the persistent preferred Home. */
    fun configurePolicies(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "not device owner; kiosk runs in soft mode")
            return
        }
        val admin = AdminReceiver.component(context)
        runCatching { dpm.setLockTaskPackages(admin, arrayOf(context.packageName)) }
            .onFailure { Log.e(TAG, "setLockTaskPackages failed", it) }

        // Survive reboots without the launcher chooser popping up.
        runCatching {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            }
            val activity = android.content.ComponentName(context, MainActivity::class.java)
            dpm.addPersistentPreferredActivity(admin, filter, activity)
        }.onFailure { Log.e(TAG, "addPersistentPreferredActivity failed", it) }

        // Device-owner can self-grant the mic so the satellite never prompts.
        grantPermissions(context)
    }

    /**
     * (Re-)apply the device-owner self-grants for the mic (and camera, used by QR
     * pairing). Exposed so the maintenance screen can fix a tablet whose grant
     * never ran — e.g. `dpm set-device-owner` happened *after* the app first
     * launched, so [configurePolicies]' grant was a no-op at the time. Returns
     * whether the mic ended up granted.
     */
    fun grantPermissions(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && dpm.isDeviceOwnerApp(context.packageName)) {
            val admin = AdminReceiver.component(context)
            for (perm in listOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.CAMERA,
            )) {
                runCatching {
                    dpm.setPermissionGrantState(
                        admin, context.packageName, perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                }.onFailure { Log.e(TAG, "grant $perm failed", it) }
            }
        }
        return hasMicPermission(context)
    }

    /** Enter lock-task + hide the status bar. No-op (logged) when not device owner. */
    fun start(activity: Activity) {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(activity.packageName)) {
            runCatching { dpm.setStatusBarDisabled(AdminReceiver.component(activity), true) }
            if (!isLockTaskActive(activity)) {
                runCatching { activity.startLockTask() }
                    .onFailure { Log.e(TAG, "startLockTask failed", it) }
            }
        } else {
            // Without device-owner, screen-pinning still needs a manual confirm,
            // so we don't force it; the immersive WebView is the soft kiosk.
            Log.w(TAG, "soft kiosk (no lock-task): not device owner")
        }
    }

    /** Drop lock-task for maintenance (called behind the PIN gate). */
    fun stop(activity: Activity) {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(activity.packageName)) {
            runCatching { dpm.setStatusBarDisabled(AdminReceiver.component(activity), false) }
        }
        if (isLockTaskActive(activity)) {
            runCatching { activity.stopLockTask() }
        }
    }

    private fun isLockTaskActive(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }
    }

    fun hasMicPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Set whether the screen stays on while charging — the device-owner global
     * setting `STAY_ON_WHILE_PLUGGED_IN`. `on` → all charge types (the screen
     * never sleeps while plugged); `off` → 0, so it sleeps on the normal timeout
     * even when plugged (lets a low tablet drop the screen and charge faster, and
     * deep-doze once unplugged). The kiosk toggles this by battery level. No-op
     * when not device owner. Returns whether it was applied.
     */
    fun setStayOnWhilePlugged(context: Context, on: Boolean): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        // Bitmask of charge types that keep the screen on: AC|USB|WIRELESS = 7.
        val value = if (on) {
            (android.os.BatteryManager.BATTERY_PLUGGED_AC or
                android.os.BatteryManager.BATTERY_PLUGGED_USB or
                android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
        } else {
            "0"
        }
        return runCatching {
            dpm.setGlobalSetting(
                AdminReceiver.component(context),
                android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                value,
            )
            true
        }.onFailure { Log.e(TAG, "setStayOnWhilePlugged failed", it) }.getOrDefault(false)
    }
}
