package com.janeadora.clicker.core

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun ipv4_is_preferred() = assertEquals("192.0.2.10", pickHost(listOf(ip("2001:db8::10"), ip("192.0.2.10"))))

    @Test fun link_local_ipv6_is_skipped() = assertNull(pickHost(listOf(ip("fe80::1"))))

    @Test fun routable_ipv6_is_the_fallback() = assertEquals("2001:db8:0:0:0:0:0:10", pickHost(listOf(ip("fe80::1"), ip("2001:db8::10"))))

    @Test fun no_address_gives_null() = assertNull(pickHost(emptyList()))
}
