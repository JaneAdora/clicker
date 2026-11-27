package com.janeadora.clicker.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.RemoteUiState
import com.janeadora.clicker.core.Settings
import com.janeadora.clicker.core.ThemeMode
import com.janeadora.clicker.core.Volume
import com.janeadora.clicker.ui.remote.ProvideKeyFeedback
import com.janeadora.clicker.ui.remote.RemoteScreen
import com.janeadora.clicker.ui.theme.ClickerTheme
import uniffi.clicker_ffi.Device
import uniffi.clicker_ffi.Key
import uniffi.clicker_ffi.ShortcutSlot

/** Sample remote states for previews and the debug preview activity. */
object RemoteSamples {
    private val livingRoom = Device(id = "tv-1", name = "Living Room", host = "192.0.2.40", paired = true, lastVolume = 12u)

    // The core's default shortcuts: slots 1 to 7 filled, 8, 9 and 0 open.
    private val shortcuts = listOf(
        "1" to "Netflix", "2" to "YouTube", "3" to "Disney+", "4" to "Max",
        "5" to "Amazon", "6" to "Hulu", "7" to "Spotify", "8" to "", "9" to "", "0" to "",
    ).map { (slot, label) -> ShortcutSlot(slot, label, if (label.isEmpty()) "" else "https://example.com/$slot") }

    val connected = RemoteUiState(
        link = Link.Connected,
        devices = listOf(livingRoom),
        activeId = livingRoom.id,
        tvName = livingRoom.name,
        volume = Volume(level = 12, max = 100, muted = false),
        shortcuts = shortcuts,
    )

    /** [state] after the sample TV has taken [key]: the volume keys step its volume and Mute flips it. */
    fun afterKey(state: RemoteUiState, key: Key): RemoteUiState {
        val v = state.volume ?: return state
        return when (key) {
            Key.VOL_UP -> state.copy(volume = v.copy(level = (v.level + 1).coerceAtMost(v.max), muted = false))
            Key.VOL_DOWN -> state.copy(volume = v.copy(level = (v.level - 1).coerceAtLeast(0), muted = false))
            Key.MUTE -> state.copy(volume = v.copy(muted = !v.muted))
            else -> state
        }
    }

    /** By name, for the debug activity's `state` extra. */
    val byName: Map<String, RemoteUiState> = mapOf(
        "connected" to connected,
        "muted" to connected.copy(volume = Volume(level = 12, max = 100, muted = true)),
        "connecting" to connected.copy(link = Link.Connecting, volume = null),
        "unreachable" to connected.copy(link = Link.Unreachable, volume = null),
        "pairing" to connected.copy(link = Link.Pairing, volume = null),
        "nowifi" to connected.copy(link = Link.NoWifi, volume = null),
        "idle" to connected.copy(link = Link.Idle, volume = null),
        "nodevices" to RemoteUiState(shortcuts = shortcuts),
        "error" to RemoteUiState(startupError = "storage error: read-only file system"),
    )
}

@Composable
private fun Sample(state: RemoteUiState, theme: ThemeMode) {
    ClickerTheme(theme) { ProvideKeyFeedback(haptics = false, onNotConnected = {}) { RemoteScreen(state, Settings(theme = theme), onAction = { true }) } }
}

@Preview(name = "Connected, dark", widthDp = 411, heightDp = 914)
@Composable
private fun ConnectedDark() = Sample(RemoteSamples.connected, ThemeMode.Dark)

@Preview(name = "Connected, light", widthDp = 411, heightDp = 914)
@Composable
private fun ConnectedLight() = Sample(RemoteSamples.connected, ThemeMode.Light)

@Preview(name = "Unreachable", widthDp = 411, heightDp = 914)
@Composable
private fun Unreachable() = Sample(RemoteSamples.byName.getValue("unreachable"), ThemeMode.Dark)

@Preview(name = "No devices", widthDp = 411, heightDp = 914)
@Composable
private fun NoDevices() = Sample(RemoteSamples.byName.getValue("nodevices"), ThemeMode.Dark)

@Preview(name = "Landscape", widthDp = 914, heightDp = 411)
@Composable
private fun Landscape() = Sample(RemoteSamples.connected, ThemeMode.Dark)

@Preview(name = "Font scale 2.0", widthDp = 411, heightDp = 914, fontScale = 2f)
@Composable
private fun FontScale2() = Sample(RemoteSamples.connected, ThemeMode.Dark)
