package com.janeadora.clicker.ui.devices

import com.janeadora.clicker.core.FoundTv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.clicker_ffi.Device

class DeviceRowsTest {
    private val a = Device(id = "a", name = "Living Room", host = "192.0.2.10", paired = true, lastVolume = null)
    private val b = Device(id = "b", name = "Bedroom", host = "192.0.2.11", paired = true, lastVolume = null)

    @Test fun active_saved_tv_is_first() {
        val rows = deviceRows(listOf(a, b), activeId = "b", found = emptyList(), healed = emptyMap())
        assertEquals("b", rows[0].id)
        assertTrue(rows[0].active)
        assertTrue(rows[0].saved)
        assertEquals(listOf("b", "a"), rows.map { it.id })
        assertFalse(rows[1].active)
    }

    @Test fun found_tv_matching_saved_is_not_duplicated() {
        // Seen at a new address: the core matched it to A by service name.
        val found = listOf(FoundTv("Living Room", "192.0.2.40"))
        val rows = deviceRows(listOf(a, b), activeId = "a", found = found, healed = mapOf("Living Room" to a.id))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.saved })
        // The saved row keeps its saved address; discovery never changes it here.
        assertEquals("192.0.2.10", rows.first { it.id == "a" }.host)
        assertEquals("Living Room", rows.first { it.id == "a" }.serviceName)
    }

    @Test fun unsaved_found_tvs_follow_saved() {
        val found = listOf(FoundTv("Den TV", "192.0.2.20"), FoundTv("Office", "192.0.2.21"))
        val rows = deviceRows(listOf(a, b), activeId = null, found = found, healed = emptyMap())
        assertEquals(4, rows.size)
        assertTrue(rows.take(2).all { it.saved })
        assertTrue(rows.dropWhile { it.saved }.all { !it.saved })
        val den = rows[2]
        assertNull(den.id)
        assertEquals("Den TV", den.name)
        assertEquals("192.0.2.20", den.host)
        assertEquals("Den TV", den.serviceName)
    }

    @Test fun found_tv_with_a_saved_name_but_no_match_is_listed_as_new() {
        // Same name as A, but the core did not vouch for it: a new TV, A untouched.
        val found = listOf(FoundTv("Living Room", "192.0.2.50"))
        val rows = deviceRows(listOf(a), activeId = "a", found = found, healed = emptyMap())
        assertEquals(2, rows.size)
        val saved = rows[0]
        assertEquals("192.0.2.10", saved.host)
        assertNull(saved.serviceName)
        val fresh = rows[1]
        assertFalse(fresh.saved)
        assertNull(fresh.id)
        assertEquals("192.0.2.50", fresh.host)
    }

    @Test fun found_tv_at_a_saved_address_is_not_listed_twice() {
        // Saved by IP, then seen by discovery at the same address.
        val found = listOf(FoundTv("Bravia", "192.0.2.11"))
        val rows = deviceRows(listOf(a, b), activeId = "a", found = found, healed = emptyMap())
        assertEquals(listOf("a", "b"), rows.map { it.id })
    }

    @Test fun the_same_found_tv_twice_is_one_row() {
        val found = listOf(FoundTv("Den TV", "192.0.2.20"), FoundTv("Den TV", "192.0.2.20"), FoundTv("Den TV 2", "192.0.2.20"))
        val rows = deviceRows(emptyList(), activeId = null, found = found, healed = emptyMap())
        assertEquals(1, rows.size)
    }

    @Test fun a_match_for_a_removed_tv_does_not_hide_the_found_tv() {
        val found = listOf(FoundTv("Den TV", "192.0.2.20"))
        val rows = deviceRows(listOf(a), activeId = "a", found = found, healed = mapOf("Den TV" to "gone"))
        assertEquals(2, rows.size)
        assertFalse(rows[1].saved)
    }

    @Test fun first_run_opens_once_with_no_tvs() {
        assertTrue(openOnStart(alreadyDecided = false, hasDevices = false, startupFailed = false))
        // Decided already (rotation, resume, a dismiss): never again by itself.
        assertFalse(openOnStart(alreadyDecided = true, hasDevices = false, startupFailed = false))
    }

    @Test fun first_run_stays_closed_with_tvs_or_a_failed_start() {
        assertFalse(openOnStart(alreadyDecided = false, hasDevices = true, startupFailed = false))
        assertFalse(openOnStart(alreadyDecided = false, hasDevices = false, startupFailed = true))
    }
}
