package live.theundead.bifrost.kiosk

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Shared PIN prompt guarding every escape from the kiosk. */
object PinGate {
    fun prompt(context: Context, prefs: Prefs, onSuccess: () -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = context.getString(R.string.enter_pin)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.enter_pin)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (input.text.toString() == prefs.exitPin) {
                    onSuccess()
                } else {
                    Toast.makeText(context, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
