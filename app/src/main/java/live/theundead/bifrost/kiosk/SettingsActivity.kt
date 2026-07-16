package live.theundead.bifrost.kiosk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import live.theundead.bifrost.kiosk.databinding.ActivitySettingsBinding
import live.theundead.bifrost.kiosk.voice.VoiceService
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * PIN-gated maintenance screen (reached by long-pressing the kiosk's top-right
 * corner). Lets an operator re-point the dashboard/server, provision the API
 * key, tune the wake word, and temporarily drop lock-task for servicing.
 *
 * Provisioning the hardest field (the 68-char `bfr_` key) is done hands-free by
 * **scanning the dashboard's pairing QR** — that single scan configures the
 * server, the dashboard URL, and the API key in one shot.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Launches our feedback-rich [ScanActivity] and redeems the QR it returns. */
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult // cancelled
            val contents = result.data?.getStringExtra(ScanActivity.EXTRA_RESULT)
            if (!contents.isNullOrBlank()) handleScannedPayload(contents)
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner()
            else toast("Camera permission is required to scan the pairing QR")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dashboardUrl.setText(prefs.dashboardUrl)
        binding.serverBase.setText(prefs.serverBase)
        binding.apiKey.setText(prefs.apiKey)
        binding.voiceEndpoint.setText(prefs.voiceEndpoint)
        binding.wakeWord.setText(prefs.wakeWord)
        binding.roomContext.setText(prefs.roomContext)
        binding.exitPin.setText(prefs.exitPin)
        binding.voiceEnabled.isChecked = prefs.voiceEnabled

        renderStatus()

        binding.saveButton.setOnClickListener { save() }
        binding.scanQrButton.setOnClickListener { startScanFlow() }
        binding.grantAccessButton.setOnClickListener {
            val ok = LockTask.grantPermissions(this)
            val msg = when {
                ok -> "Mic + camera access granted"
                !LockTask.isDeviceOwner(this) -> "Not device owner — can't self-grant; set device owner first"
                else -> "Couldn't grant access"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        binding.exitLockButton.setOnClickListener {
            LockTask.stop(this)
            Toast.makeText(this, "Lock-task released for maintenance", Toast.LENGTH_LONG).show()
        }
        binding.releaseOwnerButton.setOnClickListener { confirmReleaseOwnership() }
    }

    /** Re-provisioning escape hatch: relinquish device ownership so the app can
     * be uninstalled (Android refuses to uninstall a device owner) — the only
     * way to swap to a differently-signed build. Confirmed, because the tablet
     * loses hard-pinning until `dpm set-device-owner` runs again. */
    private fun confirmReleaseOwnership() {
        if (!LockTask.isDeviceOwner(this)) {
            toast("Not device owner — nothing to release")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Release device ownership?")
            .setMessage(
                "The kiosk loses hard-pinning and self-granting until ownership is " +
                    "set again over adb (dpm set-device-owner). Only do this to " +
                    "re-provision — e.g. replacing the app with a differently-signed build.",
            )
            .setPositiveButton("Release") { _, _ ->
                val ok = LockTask.releaseDeviceOwnership(this)
                toast(if (ok) "Ownership released — the app can now be uninstalled" else "Couldn't release ownership")
                if (ok) renderStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The maintenance status readout — one renderer so it can't drift between
     * screen-open and post-release-ownership refreshes. */
    private fun renderStatus() {
        binding.statusText.text = buildString {
            append("device owner: ").append(LockTask.isDeviceOwner(this@SettingsActivity))
            append("\nmic granted: ").append(LockTask.hasMicPermission(this@SettingsActivity))
            append("\npkg: ").append(packageName)
        }
    }

    /** Relaunch the kiosk with CLEAR_TASK so MainActivity is *recreated* — its
     * onCreate seeds the fresh `bfr_key` cookie and reloads against the current
     * URL. Both the Save and QR-pairing paths must use this (a plain launch
     * intent just foregrounds the stale instance, which keeps showing the
     * password prompt). */
    private fun relaunchKiosk() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
        finish()
    }

    private fun save() {
        prefs.dashboardUrl = binding.dashboardUrl.text.toString()
        prefs.serverBase = binding.serverBase.text.toString()
        prefs.apiKey = binding.apiKey.text.toString()
        prefs.voiceEndpoint = binding.voiceEndpoint.text.toString()
        prefs.wakeWord = binding.wakeWord.text.toString()
        prefs.roomContext = binding.roomContext.text.toString()
        prefs.exitPin = binding.exitPin.text.toString().ifBlank { Prefs.DEFAULT_PIN }
        prefs.voiceEnabled = binding.voiceEnabled.isChecked

        // Apply the voice toggle immediately.
        if (prefs.voiceEnabled) VoiceService.start(this) else VoiceService.stop(this)

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        relaunchKiosk()
    }

    // --- Pairing-QR scan → redeem → save → reload --------------------------

    /** Ensure camera permission, then launch the scanner. */
    private fun startScanFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchScanner()
        } else {
            // Device-owner deployments may have auto-granted this already; if not,
            // ask at runtime (the device owner can also pre-grant silently).
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        scanLauncher.launch(Intent(this, ScanActivity::class.java))
    }

    /**
     * Parse `{ "v":1, "base_url":..., "token":... }` and redeem it. Lenient about
     * unknown `v`; only `base_url` + `token` are required.
     */
    private fun handleScannedPayload(payload: String) {
        val baseUrl: String
        val token: String
        try {
            val json = JSONObject(payload)
            baseUrl = json.optString("base_url").trim()
            token = json.optString("token").trim()
        } catch (e: Exception) {
            Log.w(TAG, "QR payload was not pairing JSON", e)
            toast("That QR isn't a Bifrost pairing code")
            return
        }
        if (baseUrl.isBlank() || token.isBlank()) {
            toast("That QR isn't a Bifrost pairing code")
            return
        }

        toast("Pairing…")
        val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Bifrost Kiosk"
        thread {
            val result = EnrollmentClient(baseUrl).redeem(token, deviceName)
            mainHandler.post { onRedeemResult(baseUrl, result) }
        }
    }

    private fun onRedeemResult(baseUrl: String, result: EnrollmentClient.Result) {
        when (result) {
            is EnrollmentClient.Result.Success -> {
                // One scan configures everything: the QR's base_url is the Bifrost
                // origin for both the dashboard and the voice/API server.
                prefs.apiKey = result.key
                prefs.serverBase = baseUrl
                prefs.dashboardUrl = baseUrl

                binding.apiKey.setText(prefs.apiKey)
                binding.serverBase.setText(prefs.serverBase)
                binding.dashboardUrl.setText(prefs.dashboardUrl)

                toast("Paired ✓")
                relaunchKiosk()
            }
            EnrollmentClient.Result.Invalid ->
                toast("Pairing code invalid or expired — show a fresh QR")
            is EnrollmentClient.Result.Error ->
                toast(result.message)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
