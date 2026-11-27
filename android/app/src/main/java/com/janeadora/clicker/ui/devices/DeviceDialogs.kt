package com.janeadora.clicker.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.janeadora.clicker.ui.remote.ButtonLabel
import com.janeadora.clicker.ui.remote.INACTIVE_ALPHA
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.FiraCode
import com.janeadora.clicker.ui.theme.LocalClickerColors

/**
 * The house dialog: a 22dp panel on the container tone with a 1dp hairline
 * and no shadow. [onDismiss] runs for Back and, when [dismissOutside] is
 * true, for a tap outside. Buttons go in [buttons], right-aligned.
 */
@Composable
internal fun ClickerDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissOutside: Boolean = true,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val c = LocalClickerColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = dismissOutside)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(ClickerShapes.panel)
                .background(c.surfaceContainer)
                .border(ClickerShapes.border, c.outline, ClickerShapes.panel)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = ClickerType.title, color = c.heading)
            content()
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) { buttons() }
        }
    }
}

/**
 * A dialog button: a 48dp pill, accent-filled when [filled], else outlined.
 * With [enabled] false it shows at 40% and takes no taps.
 */
@Composable
internal fun DialogButton(label: String, onClick: () -> Unit, filled: Boolean = false, enabled: Boolean = true) {
    val c = LocalClickerColors.current
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp)
            .alpha(if (enabled) 1f else INACTIVE_ALPHA)
            .clip(ClickerShapes.control)
            .background(if (filled) c.accent else c.surfaceContainer)
            .border(ClickerShapes.border, if (filled) c.accent else c.outlineStrong, ClickerShapes.control)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        ButtonLabel(label, style = ClickerType.label, color = if (filled) c.onAccent else c.heading)
    }
}

/** Body copy in a dialog. */
@Composable
internal fun DialogText(text: String) = DialogText(AnnotatedString(text))

/** Body copy in a dialog, with styled runs such as an address in Fira Code. */
@Composable
internal fun DialogText(text: AnnotatedString) {
    Text(text, style = ClickerType.body, color = LocalClickerColors.current.muted)
}

// Fira Code runs wider than Anek at the same size; a step down keeps an address in a sentence even.
private val ADDRESS_SIZE = 16.sp

/** A line under a field that says what went wrong and what to do; reserves its height when empty. */
@Composable
internal fun FieldMessage(text: String?, muted: Boolean = false) {
    val c = LocalClickerColors.current
    Text(
        text.orEmpty(),
        Modifier.fillMaxWidth().heightIn(min = 26.dp),
        style = ClickerType.body,
        color = if (muted) c.muted else c.accent,
    )
}

/**
 * A one-line text field with a 1dp outline that turns accent with focus.
 * It takes focus when it appears unless [autoFocus] is false, with the
 * text selected. [description] is what TalkBack calls it; [errorText] is
 * announced with it.
 */
@Composable
internal fun DialogTextField(
    initial: String,
    onChange: (String) -> Unit,
    description: String,
    errorText: String?,
    keyboardType: KeyboardType,
    onDone: () -> Unit,
    style: TextStyle = ClickerType.body,
    placeholder: String = "",
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    autoFocus: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
) {
    val c = LocalClickerColors.current
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, if (autoFocus) TextRange(0, initial.length) else TextRange(initial.length)))
    }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { value = it; onChange(it.text) },
        modifier = Modifier.fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = description
                if (errorText != null) error(errorText)
            },
        textStyle = style.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.accent),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onNext = { onDone() }),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clip(ClickerShapes.field)
                    .background(c.surface)
                    .border(ClickerShapes.border, if (focused) c.accent else c.outlineStrong, ClickerShapes.field)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) Text(placeholder, style = style, color = c.muted)
                inner()
            }
        },
    )
}

const val NAME_NEEDED = "Give the TV a name."

/**
 * Rename a saved TV. [onSave] gets the trimmed name and returns an error
 * to show, or null when it saved.
 */
@Composable
internal fun RenameDialog(name: String, onSave: (String) -> String?, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(name) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val save = {
        val trimmed = text.trim()
        error = if (trimmed.isEmpty()) NAME_NEEDED else onSave(trimmed)
    }
    ClickerDialog(
        title = "Rename TV",
        onDismiss = onCancel,
        buttons = {
            DialogButton("Cancel", onCancel)
            DialogButton("Save", save, filled = true)
        },
    ) {
        DialogTextField(
            initial = name,
            onChange = { text = it; error = null },
            description = "TV name",
            errorText = error,
            keyboardType = KeyboardType.Text,
            onDone = save,
            capitalization = KeyboardCapitalization.Words,
        )
        FieldMessage(error)
    }
}

/** Asks once before a saved TV is removed. */
@Composable
internal fun RemoveDialog(name: String, error: String?, onRemove: () -> Unit, onCancel: () -> Unit) {
    ClickerDialog(
        title = "Remove $name?",
        onDismiss = onCancel,
        buttons = {
            DialogButton("Cancel", onCancel)
            DialogButton("Remove", onRemove, filled = true)
        },
    ) {
        DialogText("You will need its PIN to add it again.")
        if (error != null) FieldMessage(error)
    }
}

/**
 * The TV at a saved address is not the saved TV. "Add it as a new TV"
 * saves a new entry; the saved TV is left as it is.
 */
@Composable
internal fun DifferentTvDialog(host: String, onAdd: () -> Unit, onCancel: () -> Unit) {
    ClickerDialog(
        title = "A different TV is at this address.",
        onDismiss = onCancel,
        buttons = {
            DialogButton("Cancel", onCancel)
            DialogButton("Add it as a new TV", onAdd, filled = true)
        },
    ) {
        DialogText(
            buildAnnotatedString {
                append("The TV at ")
                withStyle(SpanStyle(fontFamily = FiraCode, fontSize = ADDRESS_SIZE)) { append(host) }
                append(" is not the one you saved there. Your saved TV stays as it is.")
            },
        )
    }
}
