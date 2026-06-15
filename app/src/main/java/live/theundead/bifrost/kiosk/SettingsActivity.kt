package live.theundead.bifrost.kiosk

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import live.theundead.bifrost.kiosk.databinding.ActivitySettingsBinding
import live.theundead.bifrost.kiosk.voice.VoiceService

/**
 * PIN-gated maintenance screen (reached by long-pressing the kiosk's top-right
 * corner). Lets an operator re-point the dashboard/server, provision the API
 * key, tune the wake word, and temporarily drop lock-task for servicing.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

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

        binding.statusText.text = buildString {
            append("device owner: ").append(LockTask.isDeviceOwner(this@SettingsActivity))
            append("\nmic granted: ").append(LockTask.hasMicPermission(this@SettingsActivity))
            append("\npkg: ").append(packageName)
        }

        binding.saveButton.setOnClickListener { save() }
        binding.exitLockButton.setOnClickListener {
            LockTask.stop(this)
            Toast.makeText(this, "Lock-task released for maintenance", Toast.LENGTH_LONG).show()
        }
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
        // Relaunch the kiosk so the new URL/policies take hold.
        startActivity(packageManager.getLaunchIntentForPackage(packageName))
        finish()
    }
}
