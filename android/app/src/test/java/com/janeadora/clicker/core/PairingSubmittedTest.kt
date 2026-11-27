package com.janeadora.clicker.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.clicker_ffi.RemoteEvent

/** The "code sent, waiting for the TV" flag, which lives in state so a rotation cannot send twice. */
class PairingSubmittedTest {
    private val sent = RemoteUiState(link = Link.Pairing, pairingSubmitted = true)

    @Test fun pairing_failed_clears_it() = assertFalse(reduce(sent, RemoteEvent.PairingFailed("bad pin", true)).pairingSubmitted)

    @Test fun pairing_ok_clears_it() = assertFalse(reduce(sent, RemoteEvent.PairingOk).pairingSubmitted)

    @Test fun pairing_required_clears_it() = assertFalse(reduce(sent, RemoteEvent.PairingRequired).pairingSubmitted)

    @Test fun leaving_pairing_clears_it() {
        assertFalse(reduce(sent, RemoteEvent.Disconnected(null)).pairingSubmitted)
        assertFalse(reduce(sent, RemoteEvent.Disconnected("TV not reachable")).pairingSubmitted)
        assertFalse(reduce(sent, RemoteEvent.Connecting("tv")).pairingSubmitted)
        assertFalse(reduce(sent, RemoteEvent.Connected("tv", "Den")).pairingSubmitted)
    }

    @Test fun unrelated_events_keep_it() {
        assertTrue(reduce(sent, RemoteEvent.Volume(1u, 100u, false)).pairingSubmitted)
        assertTrue(reduce(sent, RemoteEvent.DevicesChanged).pairingSubmitted)
    }
}
