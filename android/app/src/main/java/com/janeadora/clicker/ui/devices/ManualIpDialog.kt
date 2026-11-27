package com.janeadora.clicker.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.janeadora.clicker.ui.theme.ClickerType

const val HOST_INVALID = "That is not an IP address. Try one like 192.168.1.20."

/**
 * Add a TV by its address. [onAdd] gets the address once [normalizeHost]
 * accepts it; anything else keeps the dialog open with a message.
 */
@Composable
fun ManualIpDialog(onAdd: (String) -> Unit, onCancel: () -> Unit, initial: String = "") {
    var text by rememberSaveable { mutableStateOf(initial) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val add = {
        val host = normalizeHost(text)
        if (host == null) error = HOST_INVALID else onAdd(host)
    }
    ClickerDialog(
        title = "Enter IP address",
        onDismiss = onCancel,
        buttons = {
            DialogButton("Cancel", onCancel)
            DialogButton("Add", add, filled = true)
        },
    ) {
        DialogText("Find it on the TV in its network settings.")
        DialogTextField(
            initial = text,
            onChange = { text = it; error = null },
            description = "TV IP address",
            errorText = error,
            // Uri allows digits, dots and the letters of a name like tv.local.
            keyboardType = KeyboardType.Uri,
            onDone = add,
            style = ClickerType.status.copy(fontSize = 18.sp, lineHeight = 26.sp),
            placeholder = "192.168.1.20",
        )
        FieldMessage(error)
    }
}
