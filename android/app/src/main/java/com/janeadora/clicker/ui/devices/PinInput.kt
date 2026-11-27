package com.janeadora.clicker.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.LocalClickerColors

/** Length of the pairing code the TV shows. */
const val PIN_LENGTH = 6

private fun Char.isHex() = this in '0'..'9' || this in 'A'..'F'

/** The code as typed, trimmed and uppercased: six hex characters, or null. */
fun normalizePin(raw: String): String? =
    raw.trim().uppercase().takeIf { it.length == PIN_LENGTH && it.all(Char::isHex) }

/** What the code field keeps of [raw]: hex characters only, uppercased, at most six. */
internal fun pinFieldText(raw: String): String = raw.uppercase().filter(Char::isHex).take(PIN_LENGTH)

/** What TalkBack calls the code field. */
const val PIN_FIELD_DESCRIPTION = "Pairing code, 6 characters"

/**
 * The pairing code field: six Fira Code cells with 1dp outlines, the next
 * one to fill in accent. Takes focus and opens the keyboard when shown. The
 * keyboard is a password keyboard, so it offers no suggestions and does
 * not learn the code; anything that is not hex is dropped as it is typed
 * and letters are uppercased.
 */
@Composable
internal fun PinField(pin: String, errorText: String?, onChange: (String) -> Unit) {
    val c = LocalClickerColors.current
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = TextFieldValue(pin, TextRange(pin.length)),
        onValueChange = { onChange(pinFieldText(it.text)) },
        modifier = Modifier.fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = PIN_FIELD_DESCRIPTION
                if (errorText != null) error(errorText)
            },
        singleLine = true,
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = ClickerType.pin.copy(color = Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            // Password: no suggestion strip, and the keyboard does not learn the code.
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        decorationBox = { inner ->
            Box {
                // The real field stays in place for the keyboard and TalkBack, drawn invisible.
                Box(Modifier.matchParentSize().alpha(0f)) { inner() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until PIN_LENGTH) {
                        val next = focused && i == pin.length
                        Box(
                            Modifier.weight(1f).height(56.dp)
                                .clip(ClickerShapes.field)
                                .background(c.surface)
                                .border(ClickerShapes.border, if (next) c.accent else c.outlineStrong, ClickerShapes.field),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(pin.getOrNull(i)?.toString().orEmpty(), style = ClickerType.pin, color = c.heading)
                        }
                    }
                }
            }
        },
    )
}

/**
 * Whether a change in the code field sends the code: only when the text
 * itself changed (not just the cursor), it is a full code, it is not the
 * code already sent, and no code is waiting for the TV's answer.
 */
fun shouldSubmit(previous: String, current: String, lastSent: String?, inFlight: Boolean): Boolean =
    !inFlight && current != previous && current != lastSent && normalizePin(current) != null
