package com.janeadora.clicker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.clicker_ffi.RemoteEvent

class ReduceTest {
    @Test fun connecting_then_connected() {
        var s = reduce(RemoteUiState(), RemoteEvent.Connecting("tv"))
        assertEquals(Link.Connecting, s.link)
        assertEquals("tv", s.activeId)
        s = reduce(s, RemoteEvent.Connected("tv", "Living Room"))
        assertEquals(Link.Connected, s.link)
        assertEquals("Living Room", s.tvName)
    }

    @Test fun disconnected_with_reason_is_unreachable() =
        assertEquals(
            Link.Unreachable,
            reduce(RemoteUiState(link = Link.Connecting), RemoteEvent.Disconnected("TV not reachable")).link,
        )

    @Test fun disconnected_without_reason_is_idle() =
        assertEquals(Link.Idle, reduce(RemoteUiState(link = Link.Connected), RemoteEvent.Disconnected(null)).link)

    @Test fun pairing_failed_keeps_pairing_and_sets_error() {
        val s = reduce(reduce(RemoteUiState(), RemoteEvent.PairingRequired), RemoteEvent.PairingFailed("bad pin", true))
        assertEquals(Link.Pairing, s.link)
        assertEquals("bad pin", s.pairingError)
    }

    @Test fun connecting_clears_pairing_error_and_different_tv() {
        val before = RemoteUiState(link = Link.Pairing, pairingError = "bad pin", differentTvHost = "192.0.2.20")
        val s = reduce(before, RemoteEvent.Connecting("tv"))
        assertNull(s.pairingError)
        assertNull(s.differentTvHost)
    }

    @Test fun pairing_required_clears_old_error() {
        val s = reduce(RemoteUiState(pairingError = "bad pin"), RemoteEvent.PairingRequired)
        assertEquals(Link.Pairing, s.link)
        assertNull(s.pairingError)
    }

    @Test fun startup_error_survives_every_event() {
        val events = listOf(
            RemoteEvent.Connecting("tv"),
            RemoteEvent.Connected("tv", "Den"),
            RemoteEvent.Disconnected("TV not reachable"),
            RemoteEvent.Disconnected(null),
            RemoteEvent.PairingRequired,
            RemoteEvent.PairingFailed("bad pin", true),
            RemoteEvent.PairingOk,
            RemoteEvent.Volume(1u, 10u, false),
            RemoteEvent.TextFieldActive(true),
            RemoteEvent.DevicesChanged,
            RemoteEvent.DifferentTv("192.0.2.10"),
        )
        val start = RemoteUiState(startupError = "disk full")
        for (e in events) assertEquals("after $e", "disk full", reduce(start, e).startupError)
        assertEquals("disk full", events.fold(start, ::reduce).startupError)
    }

    @Test fun pairing_ok_clears_error() {
        val s = reduce(RemoteUiState(link = Link.Pairing, pairingError = "bad pin"), RemoteEvent.PairingOk)
        assertNull(s.pairingError)
    }

    @Test fun disconnect_clears_text_field_and_keeps_volume() {
        val before = RemoteUiState(
            link = Link.Connected,
            textFieldActive = true,
            volume = Volume(level = 12, max = 100, muted = false),
        )
        val s = reduce(before, RemoteEvent.Disconnected(null))
        assertFalse(s.textFieldActive)
        assertEquals(Volume(12, 100, false), s.volume)
    }

    @Test fun volume_event_converts_unsigned_values() {
        val s = reduce(RemoteUiState(), RemoteEvent.Volume(level = 200u, max = 255u, muted = true))
        assertEquals(Volume(200, 255, true), s.volume)
    }

    @Test fun text_field_event_sets_flag() {
        assertEquals(true, reduce(RemoteUiState(), RemoteEvent.TextFieldActive(true)).textFieldActive)
    }

    @Test fun different_tv_sets_host() =
        assertEquals("192.0.2.10", reduce(RemoteUiState(), RemoteEvent.DifferentTv("192.0.2.10")).differentTvHost)

    @Test fun devices_changed_leaves_state_to_the_repository() {
        val before = RemoteUiState(link = Link.Connected, tvName = "Den")
        assertEquals(before, reduce(before, RemoteEvent.DevicesChanged))
    }

    @Test fun different_tv_leaves_idle_not_connecting() {
        // The core says Disconnected first, but the question alone must also end "Connecting…".
        val connecting = RemoteUiState(link = Link.Connecting, activeId = "tv")
        val asked = reduce(connecting, RemoteEvent.DifferentTv("192.0.2.10"))
        assertEquals(Link.Idle, asked.link)
        assertEquals("192.0.2.10", asked.differentTvHost)
        val inOrder = listOf(RemoteEvent.Disconnected(null), RemoteEvent.DifferentTv("192.0.2.10")).fold(connecting, ::reduce)
        assertEquals(Link.Idle, inOrder.link)
        assertTrue(wantsConnect(inOrder.link))
    }

    @Test fun a_refused_code_is_a_wrong_code() {
        val s = reduce(RemoteUiState(link = Link.Pairing), RemoteEvent.PairingFailed("PIN checksum mismatch", wrongCode = true))
        assertEquals(Link.Pairing, s.link)
        assertTrue(s.pairingWrongCode)
    }

    @Test fun a_tv_that_went_away_is_not_a_wrong_code() {
        val wrong = reduce(RemoteUiState(link = Link.Pairing), RemoteEvent.PairingFailed("bad pin", wrongCode = true))
        val gone = reduce(wrong, RemoteEvent.PairingFailed("pairing connect failed: TV not reachable", wrongCode = false))
        assertEquals(Link.Pairing, gone.link)
        assertEquals("pairing connect failed: TV not reachable", gone.pairingError)
        assertFalse(gone.pairingWrongCode)
    }

    @Test fun every_keyboard_request_is_counted() {
        var s = RemoteUiState()
        s = reduce(s, RemoteEvent.TextFieldActive(true))
        s = reduce(s, RemoteEvent.TextFieldActive(true))
        assertEquals(2, s.textFieldShows)
        s = reduce(s, RemoteEvent.TextFieldActive(false))
        assertEquals(2, s.textFieldShows)
        assertFalse(s.textFieldActive)
    }
}
