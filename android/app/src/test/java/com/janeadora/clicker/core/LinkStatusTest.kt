package com.janeadora.clicker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.clicker_ffi.RemoteEvent

class LinkStatusTest {
    @Test fun no_wifi_overrides_unreachable() = assertEquals(Link.NoWifi, linkFor(false, Link.Unreachable))

    @Test fun no_wifi_overrides_connecting() = assertEquals(Link.NoWifi, linkFor(false, Link.Connecting))

    @Test fun no_wifi_overrides_idle() = assertEquals(Link.NoWifi, linkFor(false, Link.Idle))

    @Test fun wifi_up_keeps_base() = assertEquals(Link.Connected, linkFor(true, Link.Connected))

    @Test fun wifi_up_never_says_no_wifi() = assertEquals(Link.Unreachable, linkFor(true, Link.Unreachable))

    @Test fun connect_last_skips_a_link_already_up_or_on_the_way() {
        assertFalse(wantsConnect(Link.Connected))
        assertFalse(wantsConnect(Link.Connecting))
        assertFalse(wantsConnect(Link.Pairing))
        assertTrue(wantsConnect(Link.Idle))
        assertTrue(wantsConnect(Link.Unreachable))
        assertTrue(wantsConnect(Link.NoWifi))
    }

    // The core retries an unreachable TV every 2 seconds and reports each
    // failure. The state must come out equal, so the StateFlow does not
    // re-emit and the screen does not flicker or repeat feedback.
    @Test fun retry_failures_leave_the_state_unchanged() {
        val first = reduce(RemoteUiState(), RemoteEvent.Disconnected("TV not reachable"))
        val again = reduce(first, RemoteEvent.Disconnected("TV not reachable"))
        assertEquals(first, again)
        assertEquals(Link.Unreachable, again.link)
    }
}
