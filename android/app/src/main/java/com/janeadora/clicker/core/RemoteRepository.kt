package com.janeadora.clicker.core

import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.clicker_ffi.Device
import uniffi.clicker_ffi.Key
import uniffi.clicker_ffi.Remote
import uniffi.clicker_ffi.RemoteEvent
import uniffi.clicker_ffi.RemoteException
import uniffi.clicker_ffi.RemoteListener

/** Logcat tag for the app. Lines carry link states only, never addresses or names. */
const val LOG_TAG = "Clicker"

/**
 * Owns the Rust `Remote` and exposes its state as a StateFlow.
 *
 * `scope` must run on the main dispatcher. Events arrive on a Rust runtime
 * thread; the listener only posts them onto `scope` and returns, so it never
 * blocks the Rust side. Every state change then happens on the main thread.
 *
 * If the Remote cannot be created (storage error, or the native library fails
 * to load), the repository keeps running without it: `state.startupError`
 * holds the reason and every method is a safe no-op.
 */
class RemoteRepository(
    dataDir: File,
    private val scope: CoroutineScope,
    remoteFactory: (String, RemoteListener) -> Remote = { dir, listener -> Remote(dir, listener) },
) : AutoCloseable {
    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    private val listener = object : RemoteListener {
        override fun onEvent(event: RemoteEvent) {
            scope.launch { apply(event) }
        }
    }

    private val remote: Remote? = try {
        remoteFactory(dataDir.absolutePath, listener)
    } catch (e: RemoteException) {
        fail(e)
    } catch (e: LinkageError) {
        // UnsatisfiedLinkError and friends when libclicker_ffi.so cannot load.
        fail(e)
    }

    private fun fail(e: Throwable): Remote? {
        _state.update { it.copy(startupError = e.message ?: e.javaClass.simpleName) }
        return null
    }

    init {
        remote?.let { r ->
            _state.update {
                it.copy(devices = r.devices(), shortcuts = r.shortcuts(), activeId = r.activeDeviceId())
            }
        }
    }

    private fun apply(event: RemoteEvent) {
        _state.update { reduce(it, event) }
        if (event is RemoteEvent.DevicesChanged) refreshLists()
    }

    // The active TV is re-read too: removing it leaves none, and the screen
    // must not keep its name or connect to some other TV.
    private fun refreshLists() {
        val r = remote ?: return
        _state.update { it.copy(devices = r.devices(), shortcuts = r.shortcuts(), activeId = r.activeDeviceId()) }
    }

    fun connect(id: String) {
        val r = remote ?: return
        Log.i(LOG_TAG, "connect requested")
        // Shown at once, so a second connect (a start and a Wi-Fi change at
        // the same moment) sees the first and is skipped. The core's own
        // Connecting event follows and sets the same state.
        _state.update { it.copy(link = Link.Connecting, pairingSubmitted = false) }
        try {
            r.connect(id)
        } catch (e: RemoteException) {
            // Only an unknown id lands here; show it as a failed connect.
            apply(RemoteEvent.Disconnected(e.message ?: "unknown device"))
        }
    }

    /**
     * Connects to the last used TV, if there is one. Does nothing with no
     * saved TV, or while the link is already up, on its way or pairing.
     */
    fun connectLast() {
        if (!wantsConnect(_state.value.link)) return
        remote?.activeDeviceId()?.let { connect(it) }
    }

    fun disconnect() {
        val r = remote ?: return
        Log.i(LOG_TAG, "disconnect")
        r.disconnect()
    }

    /** False when not connected; the key is dropped. */
    fun key(k: Key): Boolean = remote?.sendKey(k) ?: false

    fun launch(slot: String): Boolean = remote?.launch(slot) ?: false

    /**
     * Sends the code the TV shows. Never logged. Marks the code as sent
     * until the TV answers, and clears the last error, so a second wrong
     * code shows its error again.
     */
    fun submitPin(pin: String) {
        val r = remote ?: return
        _state.update { it.copy(pairingError = null, pairingSubmitted = true) }
        r.submitPin(pin)
    }

    /** Clears the "different TV at this address" question once it is answered. */
    fun clearDifferentTv() {
        _state.update { it.copy(differentTvHost = null) }
    }

    fun setText(t: String) {
        remote?.setText(t)
    }

    fun submitText() {
        remote?.submitText()
    }

    /** Null only when the Remote failed to start. */
    fun addDevice(name: String, host: String, serviceName: String?): Device? =
        remote?.addDevice(name, host, serviceName)?.also { refreshLists() }

    /**
     * Always saves a new entry, even at an address a saved TV already has.
     * Leaves every saved TV as it is and does not connect. Null only when
     * the Remote failed to start.
     */
    fun addNewDevice(name: String, host: String, serviceName: String?): Device? =
        remote?.addNewDevice(name, host, serviceName)?.also { refreshLists() }

    /** Throws RemoteException on an unknown id, an empty name or a failed save. */
    fun renameDevice(id: String, name: String) {
        val r = remote ?: return
        r.renameDevice(id, name)
        refreshLists()
    }

    /** Throws RemoteException on an unknown id or a failed save. */
    fun removeDevice(id: String) {
        val r = remote ?: return
        r.removeDevice(id)
        refreshLists()
    }

    /**
     * A TV seen by discovery. Returns the saved device id when the service
     * name matches. A changed host is only tried, and saved once the TV proves
     * its identity; the Rust side decides that.
     */
    fun noteDiscovered(serviceName: String, host: String): String? = remote?.noteDiscovered(serviceName, host)

    /** Throws RemoteException when the slot or target is invalid or the save fails. */
    fun setShortcut(slot: String, label: String, target: String) {
        val r = remote ?: return
        r.setShortcut(slot, label, target)
        refreshLists()
    }

    /** Throws RemoteException when the slot is invalid or the save fails. */
    fun clearShortcut(slot: String) {
        val r = remote ?: return
        r.clearShortcut(slot)
        refreshLists()
    }

    override fun close() {
        remote?.close()
    }
}
