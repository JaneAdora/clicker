package com.janeadora.clicker.ui.typing

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.janeadora.clicker.ui.remote.Glyph
import com.janeadora.clicker.ui.remote.NavGlyph
import com.janeadora.clicker.ui.remote.capLift
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.LocalClickerColors
import com.janeadora.clicker.ui.theme.reduceMotion
import kotlinx.coroutines.delay

/** Shown in the bar while no TV is connected. */
const val TYPE_NOT_CONNECTED = "Not connected. Text will not reach the TV."

/**
 * What the bar holds: the field as shown, and the text last sent to the TV.
 * Kept in a ViewModel so a rotation keeps both, and never saved, so the
 * text (which may be a password) does not outlive the process.
 */
class TypeDraft : ViewModel() {
    var value by mutableStateOf(TextFieldValue())
        private set

    /** The text last sent (see [TypeMemory]). */
    var lastSent: String = ""
        private set

    /**
     * Applies a change the user made to the field (see [TypeMemory.edit]):
     * refused while not [connected]. Returns the text to send, or null.
     */
    fun edit(new: TextFieldValue, connected: Boolean): String? {
        val (after, send) = TypeMemory(value.text, lastSent).edit(new.text, connected)
        if (after.text == new.text) value = new
        lastSent = after.lastSent
        return send
    }

    /** Empties the field and forgets what was sent. The TV's field is left as it is. */
    fun reset() {
        val empty = TypeMemory(value.text, lastSent).linkLost()
        value = TextFieldValue(empty.text)
        lastSent = empty.lastSent
    }
}

/**
 * The type bar: a solid strip above the keyboard with one field, a clear
 * button while there is text, and a close button. While [visible] it takes
 * focus and opens the keyboard. A change to the field sends the field's
 * whole text as shown (composing text included) to [onText], never the
 * same text twice in a row. With [connected] false the field takes no
 * typing, says the text will not reach the TV, and is emptied, so nothing
 * typed before or during the outage goes out later. The
 * keyboard's Search key, or Enter on a physical keyboard, calls [onSubmit]
 * and then [onClose]. [onClose] also runs for the close button, for Back
 * and when the keyboard is put away. Once hidden the field is emptied;
 * nothing is sent for that. With reduce motion on it appears without a
 * slide. The keyboard is asked not to learn what is typed, since it may be
 * a password.
 */
@Composable
fun TypeBar(
    visible: Boolean,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    connected: Boolean = true,
    draft: TypeDraft = remember { TypeDraft() },
) {
    LaunchedEffect(visible) { if (!visible) draft.reset() }
    LaunchedEffect(connected) { if (!connected) draft.reset() }
    BackHandler(enabled = visible, onBack = onClose)
    val context = LocalContext.current
    val enter = if (reduceMotion(context)) EnterTransition.None else slideInVertically(tween(160)) { it }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .then(modifier),
        enter = enter,
        exit = ExitTransition.None,
    ) {
        Bar(draft, connected, onText, onSubmit, onClose)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bar(
    draft: TypeDraft,
    connected: Boolean,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalClickerColors.current
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // The keyboard put away (Back, or the keyboard's own hide key) closes
    // the bar, once the keyboard has stayed up for a moment: after a
    // rotation it shows, hides and shows again as it rebuilds itself. Only
    // while the app is in front, as leaving the app hides it too.
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val imeUp = WindowInsets.isImeVisible
    var imeSettled by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) {
        if (imeUp) {
            delay(IME_SETTLE_MS)
            imeSettled = true
        } else if (imeSettled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            close()
        }
    }

    val text = draft.value.text
    Column(
        Modifier.fillMaxWidth()
            .background(c.surfaceContainer)
            .drawBehind {
                // Centred half a stroke down, so the whole hairline shows.
                val y = ClickerShapes.border.toPx() / 2
                drawLine(c.outline, Offset(0f, y), Offset(size.width, y), ClickerShapes.border.toPx())
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!connected) {
            Text(TYPE_NOT_CONNECTED, Modifier.padding(horizontal = 4.dp), style = ClickerType.status, color = c.muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NoLearning { BasicTextField(
                value = draft.value,
                onValueChange = { draft.edit(it, connected)?.let(onText) },
                modifier = Modifier.weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = ClickerType.body.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    // Search terms and passwords come out mangled by autocorrect.
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = {
                    onSubmit()
                    onClose()
                }),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clip(ClickerShapes.field)
                            .background(c.surface)
                            .border(ClickerShapes.border, if (focused) c.accent else c.outlineStrong, ClickerShapes.field)
                            // Anek sits high in its line; the cap lift centres it optically.
                            .padding(start = 14.dp, end = 14.dp, top = capLift(ClickerType.body)),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) Text(TYPE_PLACEHOLDER, style = ClickerType.body, color = c.muted)
                        inner()
                    }
                },
            ) }
            if (text.isNotEmpty()) {
                BarButton("Clear text", NavGlyph.Clear) { draft.edit(TextFieldValue(), connected)?.let(onText) }
            }
            BarButton("Close typing", NavGlyph.Close, onClose)
        }
    }
}

private const val TYPE_PLACEHOLDER = "Type to the TV"

/**
 * Asks the keyboard not to learn what is typed in [content]
 * (IME_FLAG_NO_PERSONALIZED_LEARNING), while keeping a normal text
 * keyboard with suggestions.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            val noLearning = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                    request.createInputConnection(outAttributes).also {
                        outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    }
            }
            next.startInputMethod(noLearning)
        },
        content = content,
    )
}

// How long the keyboard must stay up before its going away closes the bar.
private const val IME_SETTLE_MS = 500L

/** A 48dp round icon button on the bar, outlined like the remote's buttons. */
@Composable
private fun BarButton(description: String, glyph: NavGlyph, onClick: () -> Unit) {
    val c = LocalClickerColors.current
    Box(
        Modifier.size(48.dp)
            .clip(ClickerShapes.control)
            .background(c.surfaceContainer)
            .border(ClickerShapes.border, c.outlineStrong, ClickerShapes.control)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides c.heading) { Glyph(glyph) }
    }
}
