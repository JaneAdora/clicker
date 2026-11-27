package com.janeadora.clicker.ui.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostInputTest {
    @Test fun ipv4_ok() = assertEquals("192.0.2.10", normalizeHost(" 192.0.2.10 "))

    @Test fun octet_over_255_rejected() = assertNull(normalizeHost("192.0.2.300"))

    @Test fun hostname_ok() = assertEquals("tv.local", normalizeHost("tv.local"))

    @Test fun url_rejected() = assertNull(normalizeHost("http://192.0.2.10"))

    @Test fun empty_rejected() = assertNull(normalizeHost("   "))

    @Test fun port_rejected() = assertNull(normalizeHost("192.0.2.10:6466"))

    @Test fun ipv6_literals_rejected() {
        assertNull(normalizeHost("2001:db8::10"))
        assertNull(normalizeHost("[2001:db8::10]"))
        assertNull(normalizeHost("fe80::1%wlan0"))
    }

    @Test fun three_octets_rejected() = assertNull(normalizeHost("192.0.2"))

    @Test fun leading_zero_octet_rejected() = assertNull(normalizeHost("192.0.02.10"))

    @Test fun inner_space_rejected() = assertNull(normalizeHost("192.0. 2.10"))

    @Test fun empty_label_rejected() {
        assertNull(normalizeHost("tv..local"))
        assertNull(normalizeHost(".tv"))
    }

    @Test fun hyphen_at_label_edge_rejected() = assertNull(normalizeHost("-tv.local"))

    @Test fun too_long_rejected() = assertNull(normalizeHost("a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63)))

    @Test fun trailing_dot_kept_off() = assertEquals("tv.local", normalizeHost("tv.local."))
}
