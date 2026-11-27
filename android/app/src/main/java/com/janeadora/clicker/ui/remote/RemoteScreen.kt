package com.janeadora.clicker.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.core.DpadStyle
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.RemoteUiState
import com.janeadora.clicker.core.Settings
import com.janeadora.clicker.ui.shortcuts.TilePicture
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.DotGround
import com.janeadora.clicker.ui.theme.LocalClickerColors
import uniffi.clicker_ffi.Key

/**
 * Everything the remote screen can ask for. The handler returns whether a
 * [Press] or [Launch] went out to the TV, which decides the haptic; the
 * other actions return true.
 */
sealed interface RemoteAction {
    data class Press(val key: Key) : RemoteAction
    data class Launch(val slot: String) : RemoteAction
    data object OpenDevices : RemoteAction
    data object OpenTyping : RemoteAction
    data object OpenMore : RemoteAction
    /** [tile] is the tile size on screen, for the editor's preview. */
    data class EditShortcut(val slot: String, val tile: Dp = 72.dp) : RemoteAction
    data object Retry : RemoteAction
}

internal val SIDE = SHELL_SIDE.dp

/**
 * The remote: one body (a shell with compartments) on the dot
 * ground. Portrait stacks the rows of [BodyRow]: the TV name with Power,
 * the night display, Back, Home and Menu, the D-pad with Rewind and Fast
 * forward in its compartment's bottom corners, volume, the shortcut rack
 * and the bottom plate with Type and More. The body sits on the bottom bar
 * for one-handed reach. Landscape puts the D-pad's compartment left and
 * the rest in a column right.
 * [notice] is set while local network access is missing: the display then
 * says so, TalkBack reads [notice] for it, and the key beside it asks for
 * access, or opens Settings when [noticeOpensSettings].
 * [settings] picks the D-pad or the swipe pad and can keep the screen on. The caller wraps the screen
 * (and the More sheet) in [ProvideKeyFeedback], whose not-connected callback
 * bumps [pulse]; the status words give one short pulse per change.
 * [typingBar] is set while the type bar is open, to the height it takes
 * above the keyboard; the body then holds only the status row, Back, Home
 * and Menu and the D-pad in the space left (see [TypingRemote]).
 * [pictures] gives each shortcut tile's picture by slot.
 */
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    settings: Settings,
    notice: String? = null,
    noticeOpensSettings: Boolean = false,
    pulse: Int = 0,
    typingBar: Dp? = null,
    pictures: (String) -> TilePicture = { TilePicture.None },
    onAction: (RemoteAction) -> Boolean,
) {
    val c = LocalClickerColors.current
    state.startupError?.let { error ->
        Box(Modifier.fillMaxSize().background(c.surface)) {
            Text(
                "Clicker could not start: $error",
                Modifier.safeDrawingPadding().padding(SIDE),
                style = ClickerType.body,
                color = c.onSurface,
            )
        }
        return
    }
    KeepScreenOn(settings.keepScreenOn)
    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface)) {
        val landscape = maxWidth > maxHeight
        val screenHeight = maxHeight
        val calm = remember(landscape, typingBar != null) { CalmZones() }
        val density = LocalDensity.current
        // Bars and cutout only: a keyboard opening must not flip the layout to compact.
        val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val topInset = with(density) { bars.getTop(this).toDp() }
        val bottomInset = with(density) { bars.getBottom(this).toDp() }
        // Sizes depend on the screen alone, never on the link, so nothing moves when the TV drops or comes back.
        val metrics = remoteMetrics(screenHeight.value, density.fontScale, bottomInset.value, topInset.value, maxWidth.value)
        val parts = RemoteParts(state, onAction, notice, noticeOpensSettings, metrics, settings.dpad, pulse, pictures)
        // The body keeps clear of the bars and stands SIDE in from the screen's edges.
        val body = Modifier.safeDrawingPadding().padding(start = SIDE, end = SIDE, top = SHELL_TOP.dp)
        if (typingBar != null) {
            // No scrolling: the keyboard and the bar take the bottom, the rest fits above them.
            Box(Modifier.fillMaxSize().then(calm.container())) {
                DotGround(calm::ready, Modifier.matchParentSize())
                CompositionLocalProvider(LocalCalmZones provides calm) {
                    TypingRemote(parts, Modifier.fillMaxSize().then(body).padding(bottom = typingBar + BOTTOM_PAD.dp))
                }
            }
            return@BoxWithConstraints
        }
        // Scrolls only when very large text no longer fits the screen.
        Box(
            Modifier.fillMaxWidth().heightIn(min = screenHeight)
                .verticalScroll(rememberScrollState())
                .then(calm.container()),
        ) {
            DotGround(calm::ready, Modifier.matchParentSize())
            val content = Modifier.fillMaxWidth().heightIn(min = screenHeight).then(body).padding(bottom = BOTTOM_PAD.dp)
            CompositionLocalProvider(LocalCalmZones provides calm) {
                if (landscape) Landscape(parts, content) else Portrait(parts, content)
            }
        }
    }
}

// Keeps the screen on while the remote is showing, when the setting asks.
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** The pieces every arrangement shares. */
internal class RemoteParts(
    val state: RemoteUiState,
    val onAction: (RemoteAction) -> Boolean,
    notice: String?,
    noticeOpensSettings: Boolean,
    val metrics: RemoteMetrics,
    val dpad: DpadStyle,
    val pulse: Int,
    val pictures: (String) -> TilePicture = { TilePicture.None },
) {
    val connected = state.link == Link.Connected
    val press: (Key) -> Boolean = { onAction(RemoteAction.Press(it)) }

    val tvName: String = tvNameFor(state)
    val plate = PlateState(state.link, tvName, state.volume, state.devices.isNotEmpty(), notice, noticeOpensSettings, onAction)
}

/**
 * The current TV's name, or empty when there is no current TV (none saved,
 * or the current one was removed). Never another saved TV's name: the
 * remote would not be talking to it.
 */
fun tvNameFor(state: RemoteUiState): String {
    val id = state.activeId ?: return ""
    if (state.devices.none { it.id == id }) return ""
    return state.devices.first { it.id == id }.name.ifEmpty { state.tvName }
}

// The dot ground only has to keep out from under the body.
@Composable
internal fun Modifier.calmShell(): Modifier = calm("shell", SHELL_RADIUS.dp)

@Composable
private fun Portrait(p: RemoteParts, modifier: Modifier) {
    val m = p.metrics
    val emphasis = rememberStatusPulse(p.pulse)
    // Sizes come from remoteMetrics, row by row. The body sits on the
    // bottom bar; any height it does not need stays above it.
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Shell(Modifier.widthIn(max = SHELL_MAX_WIDTH.dp).calmShell()) {
            for (row in BodyRow.entries) {
                Spacer(Modifier.height(m.gapBefore(row).dp))
                val size = Modifier.fillMaxWidth().height(m.height(row).dp)
                when (row) {
                    BodyRow.Name -> NameRow(p.plate, size, m.key.dp)
                    BodyRow.Display -> DisplayRow(p.plate, size, emphasis)
                    BodyRow.Nav -> NavRow(p, size)
                    BodyRow.Pad -> PadBay(p, size, m.dpad.dp, m.ok.dp, m.bayPad.dp)
                    BodyRow.Volume -> VolumeControl(p.state.volume, p.connected, p.press, size)
                    BodyRow.Rack -> Rack(p, m.tile.dp, size)
                    BodyRow.Plate -> BottomPlate(p, size)
                }
            }
        }
    }
}

@Composable
private fun Landscape(p: RemoteParts, modifier: Modifier) {
    val emphasis = rememberStatusPulse(p.pulse)
    val row = Modifier.fillMaxWidth().height(ROW_HEIGHT.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Shell(Modifier.widthIn(max = 780.dp).calmShell()) {
            // The bottom plate goes under the D-pad, which evens the two columns out.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(LANDSCAPE_GAP)) {
                Column(Modifier.width(LANDSCAPE_PAD + (2 * (ROW_HEIGHT + 2 * BAY_INSET)).dp), verticalArrangement = Arrangement.spacedBy(LANDSCAPE_GAP)) {
                    PadBay(p, Modifier.fillMaxWidth().weight(1f).heightIn(min = LANDSCAPE_PAD + (DPAD_LIP + 2 * BAY_INSET).dp), LANDSCAPE_PAD, 76.dp, padTop = null)
                    BottomPlate(p, row, cornerEnd = false)
                }
                Column(Modifier.weight(1f)) {
                    NameRow(p.plate, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(PLATE_GAP.dp))
                    DisplayRow(p.plate, row, emphasis)
                    Spacer(Modifier.height(LANDSCAPE_GAP))
                    NavRow(p, row)
                    Spacer(Modifier.height(LANDSCAPE_GAP))
                    VolumeControl(p.state.volume, p.connected, p.press, row)
                    Spacer(Modifier.height(LANDSCAPE_GAP))
                    Rack(p, 56.dp, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// Landscape is short: the compact D-pad and 8dp gaps.
private val LANDSCAPE_PAD = 208.dp
private val LANDSCAPE_GAP = 8.dp
