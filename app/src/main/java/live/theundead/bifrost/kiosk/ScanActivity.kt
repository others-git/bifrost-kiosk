package live.theundead.bifrost.kiosk

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.BeepManager
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import live.theundead.bifrost.kiosk.databinding.ActivityScanBinding

/**
 * Pairing-QR scanner with real feedback — replaces ZXing's bare default capture
 * screen, which scanned silently (no read confirmation) and struggled to focus
 * on a QR shown on a monitor.
 *
 * Improvements that matter here:
 *  - **Continuous autofocus** — the key to reading a QR on a glossy screen; the
 *    default single-shot autofocus often can't lock on.
 *  - **Continuous decode** — keeps trying every frame instead of one attempt.
 *  - **Unmissable confirmation** — a beep, a vibrate, and a green "Got it ✓"
 *    banner the instant a code decodes.
 *  - **Torch toggle** — fights glare/reflections off the screen.
 *
 * Returns the raw QR text via [EXTRA_RESULT]; [SettingsActivity] redeems it.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var beep: BeepManager
    private var torchOn = false
    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        beep = BeepManager(this).apply {
            isBeepEnabled = true
            isVibrateEnabled = true
        }

        binding.barcode.barcodeView.apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            cameraSettings.isAutoFocusEnabled = true
            cameraSettings.isContinuousFocusEnabled = true
        }
        binding.barcode.setStatusText("") // we drive our own banner instead

        binding.barcode.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text
                if (captured || text.isNullOrBlank()) return
                captured = true
                beep.playBeepSoundAndVibrate()
                binding.scanBanner.setBackgroundColor(GREEN)
                binding.scanBanner.text = getString(R.string.scan_captured)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
                // Brief beat so the green flash registers, then close.
                binding.scanBanner.postDelayed({ finish() }, 350)
            }
        })

        binding.torchButton.setOnClickListener { toggleTorch() }
        binding.cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        if (torchOn) binding.barcode.setTorchOn() else binding.barcode.setTorchOff()
        binding.torchButton.setText(if (torchOn) R.string.scan_torch_off else R.string.scan_torch_on)
    }

    override fun onResume() {
        super.onResume()
        binding.barcode.resume()
    }

    override fun onPause() {
        super.onPause()
        binding.barcode.pause()
    }

    companion object {
        const val EXTRA_RESULT = "scan_result"
        private val GREEN = Color.parseColor("#CC1F7A3D")
    }
}
