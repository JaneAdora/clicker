package com.janeadora.clicker.core

import uniffi.clicker_ffi.Device
import uniffi.clicker_ffi.RemoteEvent
import uniffi.clicker_ffi.ShortcutSlot

enum class Link { NoWifi, Idle, Connecting, Connected, Unreachable, Pairing }

data class Volume(val level: Int, val max: Int, val muted: Boolean)

data class RemoteUiState(
    val link: Link = Link.Idle,
    val devices: List<Device> = emptyList(),
    val activeId: String? = null,
    val tvName: String = "",
    val volume: Volume? = null,
    val textFieldActive: Boolean = false,
    /**
     * How many times the TV has asked for its keyboard this run. The TV
     * only ever says a field became active, so a new request shows as a
     * rise in this count (see TypeFocus).
     */
    val textFieldShows: Int = 0,
    val pairingError: String? = null,
    /**
     * With [pairingError]: true when the TV refused the code or did not
     * answer it (it shows a fresh one), false when the TV stopped answering
     * pairing connections at all.
     */
    val pairingWrongCode: Boolean = false,
    /** A code was sent and the TV has not answered yet. */
    val pairingSubmitted: Boolean = false,
    val differentTvHost: String? = null,
    val shortcuts: List<ShortcutSlot> = emptyList(),
    /** Set when the Rust Remote could not be created; the app cannot work. */
    val startupError: String? = null,
)

/**
 * Folds one event from the Rust core into the UI state. Pure, so it is unit
 * tested on the host. Events can arrive slightly out of order around a
 * reconnect, so each one sets state outright rather than assuming a sequence.
 * DevicesChanged is handled by RemoteRepository, which re-reads the lists.
 */
fun reduce(state: RemoteUiState, event: RemoteEvent): RemoteUiState = when (event) {
    is RemoteEvent.Connecting -> state.copy(
        link = Link.Connecting,
        activeId = event.deviceId,
        pairingError = null,
        pairingSubmitted = false,
        differentTvHost = null,
    )
    is RemoteEvent.Connected -> state.copy(
        link = Link.Connected,
        activeId = event.deviceId,
        tvName = event.name,
        pairingError = null,
        pairingSubmitted = false,
    )
    is RemoteEvent.Disconnected -> state.copy(
        link = if (event.reason != null) Link.Unreachable else Link.Idle,
        textFieldActive = false,
        pairingSubmitted = false,
    )
    RemoteEvent.PairingRequired -> state.copy(link = Link.Pairing, pairingError = null, pairingSubmitted = false)
    is RemoteEvent.PairingFailed -> state.copy(
        link = Link.Pairing,
        pairingError = event.message,
        pairingWrongCode = event.wrongCode,
        pairingSubmitted = false,
    )
    RemoteEvent.PairingOk -> state.copy(link = Link.Connecting, pairingError = null, pairingSubmitted = false)
    is RemoteEvent.Volume -> state.copy(
        volume = Volume(event.level.toInt(), event.max.toInt(), event.muted),
    )
    is RemoteEvent.TextFieldActive -> state.copy(
        textFieldActive = event.active,
        textFieldShows = if (event.active) state.textFieldShows + 1 else state.textFieldShows,
    )
    RemoteEvent.DevicesChanged -> state
    // The core tore the link down before asking; never leave it on its way.
    is RemoteEvent.DifferentTv -> state.copy(link = Link.Idle, differentTvHost = event.host)
}
