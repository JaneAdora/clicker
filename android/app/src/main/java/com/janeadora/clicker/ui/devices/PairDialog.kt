package com.janeadora.clicker.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

const val PIN_MISMATCH = "That code did not match. The TV is showing a new one."
const val PAIR_NOT_ANSWERING = "The TV is not answering. Check that it is on."

/** What the dialog says under the code for [error], by whether it was a [wrongCode]. */
fun pairMessage(error: String?, wrongCode: Boolean): String? = when {
    error == null -> null
    wrongCode -> PIN_MISMATCH
    else -> PAIR_NOT_ANSWERING
}

/** The code field's memory: what is typed, and the code last sent to the TV. */
data class PinEntry(val pin: String = "", val lastSent: String? = null) {
    /**
     * After a failure. A wrong code clears the field for the TV's new one.
     * A TV that went away keeps what is typed; whatever was sent did not
     * reach it, so the same code may go again.
     */
    fun afterFailure(wrongCode: Boolean): PinEntry = if (wrongCode) PinEntry() else copy(lastSent = null)
}

/**
 * Shown while the TV is showing a pairing code. A full code is sent once
 * (see [shouldSubmit]); while [submitting] (the code is with the TV) the
 * field takes no input and the dialog says "Checking…". [error] is set when
 * the code failed: with [wrongCode] the TV refused it (or did not answer)
 * and the field clears for the new one; without, the TV stopped answering
 * and the code typed so far stays (see [PinEntry.afterFailure]).
 * Cancel (or Back) stops pairing. The code lives only in this dialog's
 * memory, never in saved state or logs, and is gone when the dialog closes.
 */
@Composable
fun PairDialog(error: String?, submitting: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit, wrongCode: Boolean = true) {
    var entry by remember { mutableStateOf(PinEntry()) }
    LaunchedEffect(error, wrongCode) {
        if (error != null) entry = entry.afterFailure(wrongCode)
    }
    val message = pairMessage(error, wrongCode)
    ClickerDialog(
        title = "Enter the code on your TV",
        onDismiss = onCancel,
        // A stray tap must not end pairing; Back and Cancel do.
        dismissOutside = false,
        buttons = { DialogButton("Cancel", onCancel) },
    ) {
        DialogText("The TV is showing six letters and numbers.")
        PinField(entry.pin, errorText = if (!submitting) message else null) { typed ->
            if (submitting) return@PinField
            val previous = entry.pin
            entry = entry.copy(pin = typed)
            if (shouldSubmit(previous, typed, entry.lastSent, inFlight = submitting)) {
                entry = entry.copy(lastSent = typed)
                onSubmit(typed)
            }
        }
        if (submitting) FieldMessage("Checking…", muted = true) else FieldMessage(message)
    }
}
