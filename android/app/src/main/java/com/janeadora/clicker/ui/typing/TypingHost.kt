package com.janeadora.clicker.ui.typing

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.RemoteUiState
import com.janeadora.clicker.ui.remote.debugLog

// Only the flags and the request count are saved; never any text.
private val TypeFocusSaver = Saver<TypeFocus, List<Int>>(
    save = { listOf(if (it.active) 1 else 0, it.shows, if (it.dismissed) 1 else 0, if (it.justSubmitted) 1 else 0) },
    restore = { TypeFocus(it[0] == 1, it[1], it[2] == 1, it[3] == 1) },
)

/**
 * Live typing over the remote. Shows [content] (the remote screen) with
 * the type bar over its bottom edge. [content] gets the bar's height above
 * the keyboard while it is open (null when closed) and a function that
 * opens it. Each change in the bar goes to [onSetText] as the field's whole
 * text, never the same text twice in a row and nothing while the TV is not
 * connected; Search goes to [onSubmitText]. The bar also opens by itself
 * each time the TV asks for its keyboard, unless it was closed without a
 * search since (see [TypeFocus]).
 */
@Composable
fun TypingHost(
    state: RemoteUiState,
    onSetText: (String) -> Unit,
    onSubmitText: () -> Unit,
    content: @Composable (typingBar: Dp?, openTyping: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val connected = state.link == Link.Connected
    val draft = rememberTypeDraft()
    var open by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable(stateSaver = TypeFocusSaver) { mutableStateOf(TypeFocus()) }
    var barHeight by remember { mutableStateOf(0.dp) }
    LaunchedEffect(state.textFieldActive, state.textFieldShows) {
        val now = state.textFieldActive
        if (focus.offers(now, state.textFieldShows)) {
            open = true
            focus = focus.opened()
        }
        focus = focus.seen(now, state.textFieldShows)
    }
    Box(Modifier.fillMaxSize()) {
        content(if (open) barHeight else null) {
            open = true
            focus = focus.opened()
        }
        Box(Modifier.align(Alignment.BottomCenter)) {
            TypeBar(
                visible = open,
                onText = {
                    onSetText(it)
                    // The length only: the text may be a password.
                    debugLog(context, "text sent, length ${it.length}")
                },
                onSubmit = {
                    if (connected) {
                        onSubmitText()
                        focus = focus.submitted()
                        debugLog(context, "text submitted")
                    }
                },
                onClose = {
                    open = false
                    focus = focus.closed()
                },
                modifier = Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                connected = connected,
                draft = draft,
            )
        }
    }
}

// One draft per activity, kept across a rotation.
@Composable
private fun rememberTypeDraft(): TypeDraft {
    val owner = LocalActivity.current as ViewModelStoreOwner
    return remember(owner) { ViewModelProvider(owner)[TypeDraft::class.java] }
}
