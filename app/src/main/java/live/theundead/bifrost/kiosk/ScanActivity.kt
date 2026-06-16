package live.theundead.bifrost.kiosk

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.BeepManager
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import live.theundead.bifrost.kiosk.databinding.ActivityScanBinding

/**
 * Pairing-QR scanner with real feedback — replaces ZXing's bare default capture
 * screen, which scanned silently (no read confirmation) and struggled to focus
 * on a QR shown on a monitor.
 *
 * The [DecoratedBarcodeView] is created **in code** and added behind the overlay
 * (index 0): inflating it from XML crashed on this build (its constructor
 * re-inflates an internal layout, which tripped the view-binding inflater).
 *
 * Improvements: continuous autofocus (the key to reading a QR on a glossy
 * screen), continuous decode, a beep + vibrate + green "Got it ✓" banner on
 * read, and a torch toggle for glare. Returns the raw QR text via [EXTRA_RESULT].
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var barcode: DecoratedBarcodeView
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

        // Build the scanner in code and slot it behind the banner/buttons.
        barcode = DecoratedBarcodeView(this).apply {
            setStatusText("") // we drive our own banner instead
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            barcodeView.cameraSettings.isAutoFocusEnabled = true
            barcodeView.cameraSettings.isContinuousFocusEnabled = true
        }
        binding.scanRoot.addView(
            barcode,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        barcode.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text
                if (captured || text.isNullOrBlank()) return
                captured = true
                // Never let the success chime/haptic crash the scan (e.g. a denied
                // VIBRATE permission on some device) — feedback is best-effort.
                runCatching { beep.playBeepSoundAndVibrate() }
                binding.scanBanner.setBackgroundColor(GREEN)
                binding.scanBanner.text = getString(R.string.scan_captured)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
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
        if (torchOn) barcode.setTorchOn() else barcode.setTorchOff()
        binding.torchButton.setText(if (torchOn) R.string.scan_torch_off else R.string.scan_torch_on)
    }

    override fun onResume() {
        super.onResume()
        barcode.resume()
    }

    override fun onPause() {
        super.onPause()
        barcode.pause()
    }

    companion object {
        const val EXTRA_RESULT = "scan_result"
        private val GREEN = Color.parseColor("#CC1F7A3D")
    }
}
