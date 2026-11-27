package com.janeadora.clicker.core

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.janeadora.clicker.ui.remote.statusText
import com.janeadora.clicker.ui.remote.tvNameFor
import uniffi.clicker_ffi.Device
import uniffi.clicker_ffi.Key
import uniffi.clicker_ffi.NoHandle
import uniffi.clicker_ffi.Remote
import uniffi.clicker_ffi.RemoteEvent
import uniffi.clicker_ffi.RemoteException
import uniffi.clicker_ffi.RemoteListener
import uniffi.clicker_ffi.ShortcutSlot

/** The repository must survive a Remote that cannot be created. */
class RemoteRepositoryTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val dir = File("unused")

    @Test fun storage_failure_sets_startup_error_and_commands_are_no_ops() {
        val repo = RemoteRepository(dir, scope) { _, _ -> throw RemoteException.Storage("disk full") }
        val s = repo.state.value
        assertEquals("disk full", s.startupError)
        assertTrue(s.devices.isEmpty())
        assertTrue(s.shortcuts.isEmpty())
        assertFalse(repo.key(Key.UP))
        assertFalse(repo.launch("1"))
        assertNull(repo.addDevice("TV", "192.0.2.10", null))
        assertNull(repo.noteDiscovered("svc", "192.0.2.10"))
        assertNull(repo.addNewDevice("TV", "192.0.2.10", null))
        // None of these may throw without a Remote.
        repo.connect("tv")
        repo.connectLast()
        repo.disconnect()
        repo.submitPin("1234")
        assertFalse(repo.state.value.pairingSubmitted)
        repo.clearDifferentTv()
        repo.setText("hi")
        repo.submitText()
        repo.renameDevice("tv", "Den")
        repo.removeDevice("tv")
        repo.setShortcut("1", "YouTube", "com.google.android.youtube.tv")
        repo.clearShortcut("1")
        repo.close()
        assertEquals("disk full", repo.state.value.startupError)
    }

    @Test fun native_library_failure_sets_startup_error() {
        val repo = RemoteRepository(dir, scope) { _, _ -> throw UnsatisfiedLinkError("libclicker_ffi.so not found") }
        assertEquals("libclicker_ffi.so not found", repo.state.value.startupError)
        assertFalse(repo.key(Key.HOME))
    }

    /** A Remote with no Rust behind it; only the calls these tests make are answered. */
    private open class FakeRemote(var devices: List<Device> = emptyList()) : Remote(NoHandle) {
        val calls = mutableListOf<String>()
        var active: String? = devices.firstOrNull()?.id
        override fun devices() = devices
        override fun shortcuts() = emptyList<ShortcutSlot>()
        override fun activeDeviceId(): String? = active
        override fun connect(deviceId: String) { calls += "connect $deviceId" }
        override fun disconnect() { calls += "disconnect" }
        override fun addNewDevice(name: String, host: String, serviceName: String?): Device {
            calls += "addNewDevice $name $host $serviceName"
            val d = Device(id = "new-${devices.size}", name = name, host = host, paired = false, lastVolume = null)
            devices = devices + d
            return d
        }
        override fun addDevice(name: String, host: String, serviceName: String?): Device = error("addDevice must not be called")
        override fun renameDevice(id: String, name: String) = error("renameDevice must not be called")
        override fun removeDevice(id: String) {
            calls += "removeDevice $id"
            devices = devices.filter { it.id != id }
            if (active == id) active = null
        }
        override fun noteDiscovered(serviceName: String, host: String): String? = error("noteDiscovered must not be called")
        override fun submitPin(pin: String) { calls += "submitPin" }
        override fun close() = Unit
    }

    @Test fun add_new_device_adds_an_entry_and_leaves_the_saved_tv_alone() {
        val saved = Device(id = "living-room", name = "Living Room", host = "192.0.2.10", paired = true, lastVolume = 3u)
        val fake = FakeRemote(listOf(saved))
        val repo = RemoteRepository(dir, scope) { _, _ -> fake }
        val added = repo.addNewDevice("192.0.2.10", "192.0.2.10", null)
        assertEquals(listOf("addNewDevice 192.0.2.10 192.0.2.10 null"), fake.calls)
        assertEquals("new-1", added?.id)
        // The saved TV is unchanged and the list now has both.
        assertEquals(listOf(saved, added), repo.state.value.devices)
    }

    @Test fun submit_pin_marks_the_code_sent_and_clears_the_last_error() {
        val fake = FakeRemote()
        val repo = RemoteRepository(dir, scope) { _, _ -> fake }
        repo.submitPin("A1B2C3")
        assertTrue(repo.state.value.pairingSubmitted)
        assertNull(repo.state.value.pairingError)
        assertEquals(listOf("submitPin"), fake.calls)
    }

    private fun tv(id: String, name: String) = Device(id = id, name = name, host = "192.0.2.10", paired = true, lastVolume = null)

    /** A repository over [fake], and the listener the core would call. */
    private fun withListener(fake: FakeRemote): Pair<RemoteRepository, RemoteListener> {
        var listener: RemoteListener? = null
        val repo = RemoteRepository(dir, scope) { _, l -> listener = l; fake }
        return repo to listener!!
    }

    @Test fun different_tv_then_cancel_leaves_a_link_that_can_connect_again() {
        val fake = FakeRemote(listOf(tv("den", "Den")))
        val (repo, core) = withListener(fake)
        // (repo.connect logs, which a host test cannot; the core's event sets the same state.)
        core.onEvent(RemoteEvent.Connecting("den"))
        core.onEvent(RemoteEvent.Disconnected(null))
        core.onEvent(RemoteEvent.DifferentTv("192.0.2.10"))
        assertEquals("192.0.2.10", repo.state.value.differentTvHost)
        repo.clearDifferentTv() // Cancel
        val s = repo.state.value
        assertNull(s.differentTvHost)
        assertEquals(Link.Idle, s.link)
        // Retry and auto-connect are no longer blocked by a stale "Connecting…".
        assertTrue(wantsConnect(s.link))
        assertEquals("den", fake.activeDeviceId())
    }

    @Test fun a_code_with_no_answer_ends_with_the_dialog_usable_again() {
        val fake = FakeRemote(listOf(tv("den", "Den")))
        val (repo, core) = withListener(fake)
        core.onEvent(RemoteEvent.PairingRequired)
        repo.submitPin("A1B2C3")
        assertTrue(repo.state.value.pairingSubmitted)
        // The core's finish timeout.
        core.onEvent(RemoteEvent.PairingFailed("The TV did not answer. Try the code again.", wrongCode = true))
        val s = repo.state.value
        assertEquals(Link.Pairing, s.link)
        assertFalse(s.pairingSubmitted)
        assertEquals("The TV did not answer. Try the code again.", s.pairingError)
        assertTrue(s.pairingWrongCode)
    }

    @Test fun removing_the_active_tv_leaves_no_name_and_does_not_pick_another() {
        val fake = FakeRemote(listOf(tv("den", "Den"), tv("kitchen", "Kitchen")))
        val (repo, core) = withListener(fake)
        core.onEvent(RemoteEvent.Connecting("den"))
        core.onEvent(RemoteEvent.Connected("den", "Den"))
        assertEquals("Den", tvNameFor(repo.state.value))
        core.onEvent(RemoteEvent.Disconnected(null))
        repo.removeDevice("den")
        val s = repo.state.value
        assertNull(s.activeId)
        assertEquals(listOf("kitchen"), s.devices.map { it.id })
        assertEquals("", tvNameFor(s))
        assertEquals("Not connected", statusText(s.link, tvNameFor(s), anySaved = s.devices.isNotEmpty()))
        fake.calls.clear()
        repo.connectLast()
        assertEquals(emptyList<String>(), fake.calls)
        // With the last one gone it asks for a TV.
        repo.removeDevice("kitchen")
        val none = repo.state.value
        assertEquals("Add a TV", statusText(none.link, tvNameFor(none), anySaved = none.devices.isNotEmpty()))
    }
}
