package com.janeadora.clicker.ui.remote

import android.view.KeyEvent
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.janeadora.clicker.core.DpadStyle
import com.janeadora.clicker.core.Settings
import com.janeadora.clicker.core.ThemeMode
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.DarkColors
import com.janeadora.clicker.ui.theme.LocalClickerColors
import uniffi.clicker_ffi.Key

// Panels have 22dp corners; a sheet rounds only the corners away from the screen edge.
internal val SHEET_SHAPE = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

// A key in the sheet: what it shows, the key it sends, and what TalkBack says.
private class MoreKey(val label: String, val key: Key, val spoken: String = label)

private val MORE_KEYS = listOf(
    MoreKey("TV settings", Key.SETTINGS),
    MoreKey("Input", Key.INPUT),
    MoreKey("Assistant", Key.ASSIST),
    MoreKey("Channel up", Key.CHANNEL_UP),
    MoreKey("Channel down", Key.CHANNEL_DOWN),
    // Not on the main screen: OK in the middle of the D-pad does the job there.
    MoreKey("Play/pause", Key.PLAY_PAUSE, "Play or pause"),
    MoreKey("Stop", Key.STOP),
    MoreKey("Previous", Key.PREV),
    MoreKey("Next", Key.NEXT),
)

/**
 * The More sheet: the less used TV keys, then the comfort settings. Keys dim
 * when no TV is connected, like the main buttons. Each
 * setting change goes to [onSettingsChange] as an edit of the stored settings,
 * so two quick changes never undo each other. The sheet is its own window,
 * so the phone's volume keys pressed while it is open go to [onVolumeKey]
 * first; it returns true when it took the key.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MoreSheet(
    settings: Settings,
    connected: Boolean,
    onPress: (Key) -> Boolean,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
    onVolumeKey: (KeyEvent) -> Boolean = { false },
) {
    val c = LocalClickerColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SHEET_SHAPE,
        containerColor = c.surfaceContainer,
        contentColor = c.onSurface,
        tonalElevation = 0.dp,
        scrimColor = c.surface.copy(alpha = 0.7f),
        // No handle: the sheet drags by its body and closes from the scrim or Back.
        dragHandle = null,
        // The sheet is its own window; keep its bar icons in the app's mode,
        // which can differ from the phone's.
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = c != DarkColors,
            isAppearanceLightNavigationBars = c != DarkColors,
        ),
    ) {
        SheetBarsFollowTheme(light = c != DarkColors)
        ForwardVolumeKeys(onVolumeKey)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("More", style = ClickerType.title, color = c.heading)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (k in MORE_KEYS) {
                    RemoteButton(k.spoken, { onPress(k.key) }, Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp), connected) {
                        KeyLabel(k.label, Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("App settings", style = ClickerType.title, color = c.heading)
            Choice(
                "Theme",
                listOf("System" to ThemeMode.System, "Light" to ThemeMode.Light, "Dark" to ThemeMode.Dark),
                settings.theme,
            ) { v -> onSettingsChange { it.copy(theme = v) } }
            Choice(
                "D-pad",
                listOf("Buttons" to DpadStyle.Buttons, "Swipe pad" to DpadStyle.Swipe),
                settings.dpad,
            ) { v -> onSettingsChange { it.copy(dpad = v) } }
            Toggle("Haptics", settings.haptics) { v -> onSettingsChange { it.copy(haptics = v) } }
            Toggle("Phone volume keys control the TV", settings.volumeKeys) { v ->
                onSettingsChange { it.copy(volumeKeys = v) }
            }
            Toggle("Keep screen on", settings.keepScreenOn) { v -> onSettingsChange { it.copy(keepScreenOn = v) } }
        }
    }
}

// The properties above set the bar icons when the sheet opens; this keeps
// them right when the theme is changed while the sheet is open.
@Composable
internal fun SheetBarsFollowTheme(light: Boolean) {
    val view = LocalView.current
    LaunchedEffect(light) {
        val window = generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

// Hands the volume keys to [onKey] before the sheet's window, which would
// otherwise change the phone's volume. Other keys stay with the sheet.
@Composable
private fun ForwardVolumeKeys(onKey: (KeyEvent) -> Boolean) {
    val view = LocalView.current
    val handler by rememberUpdatedState(onKey)
    DisposableEffect(view) {
        val window = generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        val original = window?.callback
        if (window != null && original != null) {
            window.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val volume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    return (volume && handler(event)) || original.dispatchKeyEvent(event)
                }
            }
        }
        onDispose { if (window != null && original != null) window.callback = original }
    }
}

/** A labelled single choice shown as a row of pills; the chosen one is accent-filled. */
@Composable
private fun <T> Choice(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val c = LocalClickerColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = ClickerType.body, color = c.onSurface)
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, value) in options) {
                val on = value == selected
                Box(
                    Modifier.heightIn(min = 48.dp).widthIn(min = 72.dp)
                        .clip(ClickerShapes.control)
                        .background(if (on) c.accent else c.surfaceContainer)
                        .border(ClickerShapes.border, if (on) c.accent else c.outlineStrong, ClickerShapes.control)
                        .selectable(selected = on, role = Role.RadioButton) { onSelect(value) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ButtonLabel(name, style = ClickerType.label, color = if (on) c.onAccent else c.heading)
                }
            }
        }
    }
}

/** A labelled on/off setting; the whole row is the tap target. */
@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalClickerColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clip(ClickerShapes.field)
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f).padding(end = 12.dp, top = capLift(ClickerType.body)),
            style = ClickerType.body,
            color = c.onSurface,
        )
        Switch(
            checked = value,
            onCheckedChange = null,
            // Brand colors, with the same outline on and off. The switch has no elevation.
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                checkedBorderColor = c.outlineStrong,
                uncheckedThumbColor = c.onSurface,
                uncheckedTrackColor = c.surfaceRaised,
                uncheckedBorderColor = c.outlineStrong,
            ),
        )
    }
}
