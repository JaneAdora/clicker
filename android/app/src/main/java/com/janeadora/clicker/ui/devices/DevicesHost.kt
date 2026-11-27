package com.janeadora.clicker.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.janeadora.clicker.core.Discovery
import com.janeadora.clicker.core.FoundTv
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.RemoteRepository
import com.janeadora.clicker.core.RemoteUiState
import com.janeadora.clicker.ui.remote.Haptic
import com.janeadora.clicker.ui.remote.pairingAccepted
import com.janeadora.clicker.ui.remote.playHaptic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import uniffi.clicker_ffi.RemoteException

/** How long the sheet says "Looking for TVs…" before it says none were found. */
private const val LOOK_MS = 8_000L

/**
 * The TV switcher and its dialogs: the sheet, "Enter IP address", rename,
 * remove, the pairing code and the different-TV question. [state] is the
 * screen's state (link already adjusted for Wi-Fi). [onConnect] connects
 * to a saved TV by id, asking for local network access first if needed.
 * With no saved TV the sheet opens by itself once per launch, after
 * [beforeOpen] returns (it waits out the launch entrance, when one is
 * playing). When the TV accepts a pairing code there is one quiet haptic,
 * if [haptics] is on; connecting, reconnecting and switching TVs are silent.
 */
@Composable
fun DevicesHost(
    state: RemoteUiState,
    repository: RemoteRepository,
    discovery: Discovery,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    canDiscover: Boolean,
    notice: String,
    allowLabel: String,
    onAllowLocalNetwork: () -> Unit,
    onConnect: (String) -> Unit,
    haptics: Boolean = false,
    beforeOpen: suspend () -> Unit = {},
) {
    var decided by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (openOnStart(decided, state.devices.isNotEmpty(), state.startupError != null)) {
            beforeOpen()
            onOpenChange(true)
        }
        decided = true
    }
    val view = LocalView.current
    // The count of accepted codes as last seen, starting from now: a screen
    // rebuilt after a pairing does not confirm it again.
    var seen by remember { mutableIntStateOf(state.pairingsAccepted) }
    LaunchedEffect(state.pairingsAccepted) {
        if (pairingAccepted(seen, state.pairingsAccepted)) playHaptic(view, Haptic.Paired, haptics)
        seen = state.pairingsAccepted
    }
    var enterIp by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }

    if (open) {
        SheetWithDiscovery(state, repository, discovery, canDiscover, notice, allowLabel, onAllowLocalNetwork,
            onPick = { row ->
                onOpenChange(false)
                pick(row, repository, onConnect)
            },
            onRename = { renaming = it.id },
            onRemove = { removeError = null; removing = it.id },
            onEnterIp = { onOpenChange(false); enterIp = true },
            onDismiss = { onOpenChange(false) },
        )
    }
    if (enterIp) {
        ManualIpDialog(
            onAdd = { host ->
                enterIp = false
                repository.addDevice(name = host, host = host, serviceName = null)?.let { onConnect(it.id) }
            },
            onCancel = { enterIp = false },
        )
    }
    state.devices.firstOrNull { it.id == renaming }?.let { tv ->
        RenameDialog(tv.name, onSave = { name ->
            try {
                repository.renameDevice(tv.id, name)
                renaming = null
                null
            } catch (e: RemoteException) {
                "Could not save the name. Try again."
            }
        }, onCancel = { renaming = null })
    }
    state.devices.firstOrNull { it.id == removing }?.let { tv ->
        RemoveDialog(tv.name, removeError, onRemove = {
            try {
                repository.removeDevice(tv.id)
                removing = null
            } catch (e: RemoteException) {
                removeError = "Could not remove it. Try again."
            }
        }, onCancel = { removing = null })
    }
    if (state.link == Link.Pairing) {
        PairDialog(
            state.pairingError,
            state.pairingSubmitted,
            onSubmit = repository::submitPin,
            onCancel = repository::disconnect,
            wrongCode = state.pairingWrongCode,
        )
    }
    state.differentTvHost?.let { host -> DifferentTv(host, repository, onConnect) }
}

private fun pick(row: DeviceRow, repository: RemoteRepository, onConnect: (String) -> Unit) {
    val id = row.id
    if (id != null) {
        // Always connect, even to the current TV: a tap is the way out of
        // a link that looks on its way but is not.
        onConnect(id)
        return
    }
    // Only rows the core did not match get here, so this saves a new TV and
    // never touches a saved one.
    repository.addDevice(name = row.name, host = row.host, serviceName = row.serviceName)?.let { onConnect(it.id) }
}

/**
 * The TV at a saved address is a different one. "Add it as a new TV"
 * always saves a new entry (named by its address until renamed) and
 * connects to it; the saved TV is never changed. Either way the question
 * is cleared.
 */
@Composable
private fun DifferentTv(host: String, repository: RemoteRepository, onConnect: (String) -> Unit) {
    DifferentTvDialog(host, onAdd = {
        val device = repository.addNewDevice(name = host, host = host, serviceName = null)
        repository.clearDifferentTv()
        device?.let { onConnect(it.id) }
    }, onCancel = repository::clearDifferentTv)
}

@Composable
private fun SheetWithDiscovery(
    state: RemoteUiState,
    repository: RemoteRepository,
    discovery: Discovery,
    canDiscover: Boolean,
    notice: String,
    allowLabel: String,
    onAllow: () -> Unit,
    onPick: (DeviceRow) -> Unit,
    onRename: (DeviceRow) -> Unit,
    onRemove: (DeviceRow) -> Unit,
    onEnterIp: () -> Unit,
    onDismiss: () -> Unit,
) {
    var found by remember { mutableStateOf(emptyList<FoundTv>()) }
    var healed by remember { mutableStateOf(emptyMap<String, String>()) }
    var waited by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The core says which found TVs are saved ones. It also treats a changed
    // address as a candidate to verify; Kotlin never writes it anywhere.
    fun match(tvs: List<FoundTv>) =
        tvs.mapNotNull { tv -> repository.noteDiscovered(tv.serviceName, tv.host)?.let { tv.serviceName to it } }.toMap()
    if (canDiscover) {
        // Runs while the sheet is open and the app is on screen: leaving
        // composition, or the app going to the background, stops discovery.
        // Matched before the list shows, so a saved TV never flashes up as new.
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Each return to the app is a fresh search, with its own wait.
                failed = false
                waited = false
                launch { delay(LOOK_MS); waited = true }
                discovery.browse()
                    .catch { failed = true }
                    .collect { healed = match(it); found = it }
            }
        }
    }
    // A TV just saved from the list is matched again, by its service name.
    LaunchedEffect(state.devices) { healed = match(found) }
    val search = when {
        !canDiscover -> Search.NeedsPermission
        found.isNotEmpty() -> Search.NothingNew
        failed -> Search.Failed
        waited -> Search.NothingFound
        else -> Search.Looking
    }
    DeviceSheet(
        rows = deviceRows(state.devices, state.activeId, found, healed),
        link = state.link,
        search = search,
        notice = notice,
        allowLabel = allowLabel,
        onPick = onPick,
        onRename = onRename,
        onRemove = onRemove,
        onEnterIp = onEnterIp,
        onAllow = onAllow,
        onDismiss = onDismiss,
    )
}
