package com.janeadora.clicker.ui.remote

import com.janeadora.clicker.core.Link
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTextTest {
    @Test fun connected_shows_the_tv_name() = assertEquals("Living Room", statusText(Link.Connected, "Living Room"))
    @Test fun connecting() = assertEquals("Connecting…", statusText(Link.Connecting, "Living Room"))
    @Test fun unreachable() = assertEquals("TV not reachable", statusText(Link.Unreachable, "Living Room"))
    @Test fun no_wifi() = assertEquals("Wi-Fi is off", statusText(Link.NoWifi, "Living Room"))
    @Test fun pairing() = assertEquals("Pairing…", statusText(Link.Pairing, "Living Room"))
    @Test fun idle_with_no_devices() = assertEquals("Add a TV", statusText(Link.Idle, ""))
    @Test fun idle_with_a_device() = assertEquals("Not connected", statusText(Link.Idle, "Living Room"))
}
