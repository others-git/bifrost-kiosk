package live.theundead.bifrost.kiosk

import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

/**
 * The hub link as a **foreground service**: the check-in heartbeat and the live
 * command stream, moved OUT of [MainActivity] so they survive the screen
 * turning off.
 *
 * Why: presence-aware display power means the hub routinely sleeps the screen
 * and later wakes it on motion. With the loops owned by the Activity, a
 * screen-off kiosk leaves the foreground and Android's cached-app freezer
 * (12+) freezes the whole process — the SSE stream and heartbeats stop, so the
 * hub's queued "wake" could never arrive: blanking worked, waking didn't. A
 * foreground service keeps the process runnable exactly like the voice
 * satellite already does when enabled; a self-renewing partial wake lock keeps
 * the CPU serviceable on battery.
 *
 * Commands dispatch to [MainActivity] via [CommandBus] (it owns the screen and
 * WebView actions); if the activity is somehow gone, sleep/wake degrade to the
 * service's own minimal handling so display power still works.
 */
class LinkService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var commandStream: KioskCommandStream? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The config the running stream was built with, to restart it on change. */
    private var streamConfig: Pair<String, String>? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            postCheckin()
            handler.postDelayed(this, CHECKIN_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bifrost:link")
            .apply { setReferenceCounted(false) }
        handler.post(heartbeatRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // (Re)build the stream when the hub config changed (or first start) —
        // MainActivity pokes us on every resume, so a settings edit takes effect
        // without a reboot.
        val prefs = Prefs(this)
        val config = prefs.serverBase to prefs.apiKey
        if (config != streamConfig) {
            streamConfig = config
            commandStream?.stop()
            commandStream = KioskCommandStream(config.first, config.second) { cmd ->
                handler.post { deliver(cmd) }
            }.also { it.start() }
        }
        if (intent?.action == ACTION_CHECKIN_NOW) postCheckin()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatRunnable)
        commandStream?.stop()
        executor.shutdownNow()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    /** Hand a command to the activity; degrade to minimal display-power handling
     * if it isn't listening (it always should be, under lock task). */
    private fun deliver(cmd: String) {
        if (CommandBus.dispatch(cmd)) return
        Log.w(TAG, "no command listener — handling '$cmd' minimally")
        when (cmd) {
            "sleep" -> runCatching {
                (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
            }
            "wake" -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "bifrost:wake",
                )
                runCatching { wl.acquire(3_000L) }
            }
        }
    }

    /** One heartbeat: renew the CPU lease, report screen + battery, apply the
     * charge-aware screen policy, adopt the hub-assigned room, and act on any
     * queued command. */
    private fun postCheckin() {
        // Self-renewing lease instead of an indefinite hold: if the service dies,
        // the lock lapses on its own.
        runCatching { wakeLock?.acquire(WAKELOCK_LEASE_MS) }

        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val battery = readBattery()
        applyDozePolicy(battery.level, battery.source != "none")
        val prefs = Prefs(this)
        val server = prefs.serverBase
        val key = prefs.apiKey
        if (server.isBlank() || key.isBlank()) return
        executor.execute {
            val res = KioskCheckin(server, key)
                .checkin(BuildConfig.VERSION_NAME, screenOn, battery) ?: return@execute
            handler.post {
                // Adopt the hub-assigned room as the voice context (location).
                res.room?.let { if (it != prefs.roomContext) prefs.roomContext = it }
                res.command?.let { deliver(it) }
            }
        }
    }

    /** Snapshot battery + power state from the sticky battery broadcast plus the
     * instantaneous current. Used for the hub's per-kiosk power telemetry. */
    private fun readBattery(): KioskCheckin.Battery {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && scale > 0) l * 100 / scale else null
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
        val tempDeciC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }
        // Instantaneous current (µA, signed; vendors differ on sign while charging).
        val currentUa = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Int.MIN_VALUE && it != 0 }
        return KioskCheckin.Battery(level, charging, voltageMv, currentUa, tempDeciC, source)
    }

    /** Hysteresis state for [applyDozePolicy]: keep the screen on while plugged.
     * Flips false below 30% and true above 70%, so it doesn't flap mid-band. */
    private var stayAwakeWhilePlugged = true

    /** Last value pushed to the OS, so we only write the global setting on change. */
    private var lastStayOnApplied: Boolean? = null

    /**
     * Battery-aware screen policy (no doze-exemption — we *want* a low tablet to
     * conserve). **While plugged in**: keep the screen on when the battery is
     * healthy (≥70%) and let it sleep — minimal draw, charges faster — when low
     * (<30%), with hysteresis between. **On battery**: never force stay-on, so the
     * system's normal screen timeout auto-dozes (and deep-dozes) as usual.
     */
    private fun applyDozePolicy(level: Int?, plugged: Boolean) {
        if (level != null) {
            if (level >= STAY_AWAKE_PCT) stayAwakeWhilePlugged = true
            else if (level < AUTO_DOZE_PCT) stayAwakeWhilePlugged = false
        }
        val target = plugged && stayAwakeWhilePlugged
        if (target != lastStayOnApplied) {
            lastStayOnApplied = target
            LockTask.setStayOnWhilePlugged(this, target)
        }
    }

    private fun startForegroundNotification() {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, KioskApp.LINK_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Linked to hub — remote sleep/wake active")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "LinkService"
        private const val NOTIFICATION_ID = 2

        /** Heartbeat cadence — a cheap local-LAN poll carrying screen + battery
         * state, and the offline fallback for queued commands. */
        private const val CHECKIN_MS = 10_000L

        /** CPU lease per heartbeat; comfortably outlives the cadence so the lock
         * is continuous while the service lives, and lapses if it dies. */
        private const val WAKELOCK_LEASE_MS = 60_000L

        /** See [applyDozePolicy]: below this a plugged tablet's screen may sleep
         * so it charges faster; at/above [STAY_AWAKE_PCT] keep it awake. */
        private const val AUTO_DOZE_PCT = 30
        private const val STAY_AWAKE_PCT = 70

        /** Start (or poke) the link. Idempotent — re-sends the current config so
         * a settings edit re-targets the stream without a reboot. */
        fun start(context: Context) {
            val intent = Intent(context, LinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Fire an off-cadence check-in (e.g. right after a sleep/wake so the hub
         * sees the new screen state immediately). */
        fun checkinNow(context: Context) {
            val intent = Intent(context, LinkService::class.java).setAction(ACTION_CHECKIN_NOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private const val ACTION_CHECKIN_NOW = "live.theundead.bifrost.kiosk.CHECKIN_NOW"
    }
}
