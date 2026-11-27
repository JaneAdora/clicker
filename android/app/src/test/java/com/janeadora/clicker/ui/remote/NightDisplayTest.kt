package com.janeadora.clicker.ui.remote

import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.Volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightDisplayTest {
    private val tv = "Living Room"

    @Test fun connected_reads_the_volume() {
        val r = nightReadout(Link.Connected, Volume(24, 100, muted = false), tv)
        assertEquals("VOL 24", r.text)
        assertEquals("VOL", r.label)
        assertEquals("24", r.figure)
        assertEquals(0.24f, r.level!!, 1e-6f)
        assertFalse(r.muted)
    }

    @Test fun muted_reads_muted_and_keeps_the_level_for_the_meter() {
        val r = nightReadout(Link.Connected, Volume(24, 100, muted = true), tv)
        assertEquals("MUTED", r.text)
        assertTrue(r.muted)
        assertEquals(0.24f, r.level!!, 1e-6f)
    }

    @Test fun connected_before_the_tv_reports_a_volume() {
        val r = nightReadout(Link.Connected, null, tv)
        assertEquals("VOL --", r.text)
        assertNull(r.level)
    }

    @Test fun a_level_outside_the_range_is_held_to_it() {
        assertEquals(1f, nightReadout(Link.Connected, Volume(120, 100, false), tv).level!!, 0f)
        assertEquals(0f, nightReadout(Link.Connected, Volume(5, 0, false), tv).level!!, 0f)
    }

    @Test fun not_connected_reads_the_status_in_capitals() {
        assertEquals("CONNECTING…", nightReadout(Link.Connecting, null, tv).text)
        assertEquals("TV NOT REACHABLE", nightReadout(Link.Unreachable, null, tv).text)
        assertEquals("WI-FI IS OFF", nightReadout(Link.NoWifi, null, tv).text)
        assertEquals("PAIRING…", nightReadout(Link.Pairing, null, tv).text)
        assertEquals("NOT CONNECTED", nightReadout(Link.Idle, null, tv).text)
    }

    @Test fun nothing_saved_reads_no_tv_and_leaves_the_prompt_to_the_top_row() {
        assertEquals("NO TV", nightReadout(Link.Idle, null, "").text)
        assertEquals("Add a TV", plateName("", anySaved = false))
    }

    @Test fun missing_local_network_access_says_so_whatever_the_link() {
        for (link in Link.entries) {
            val r = nightReadout(link, Volume(24, 100, false), tv, permissionMissing = true)
            assertEquals("$link", "LOCAL NETWORK OFF", r.text)
            assertNull("$link", r.level)
        }
    }

    @Test fun the_key_beside_the_display_says_what_it_does() {
        for (link in Link.entries) {
            assertEquals("$link", PlateKey.Allow, plateKey(link, permissionMissing = true))
            assertEquals("$link", PlateKey.Settings, plateKey(link, permissionMissing = true, opensSettings = true))
            assertEquals("$link", if (link == Link.Unreachable) PlateKey.Retry else null, plateKey(link, permissionMissing = false))
        }
        assertEquals(listOf("Retry", "Allow", "Settings"), PlateKey.entries.map { it.label })
    }

    @Test fun saved_tvs_but_none_current_reads_not_connected() =
        assertEquals("NOT CONNECTED", nightReadout(Link.Idle, null, "", anySaved = true).text)

    @Test fun not_connected_has_no_figure_and_no_meter_even_with_a_stale_volume() {
        val r = nightReadout(Link.Unreachable, Volume(24, 100, false), tv)
        assertEquals("", r.figure)
        assertNull(r.level)
    }

    @Test fun talkback_hears_the_volume_in_words() {
        assertEquals("Volume 24 of 100", readoutDescription(Volume(24, 100, false)))
        assertEquals("Muted, volume 24 of 100", readoutDescription(Volume(24, 100, true)))
        assertEquals("Volume not known yet", readoutDescription(null))
    }

    @Test fun the_meter_lights_in_step_with_the_level() {
        assertEquals(18, METER_SEGMENTS)
        assertEquals(0, litSegments(0f, METER_SEGMENTS))
        assertEquals(1, litSegments(0.01f, METER_SEGMENTS))
        assertEquals(9, litSegments(0.5f, METER_SEGMENTS))
        assertEquals(18, litSegments(1f, METER_SEGMENTS))
    }

    @Test fun the_top_row_names_the_tv_or_asks_for_one() {
        assertEquals("Living Room", plateName("Living Room"))
        assertEquals("Choose a TV", plateName("", anySaved = true))
        assertEquals("Add a TV", plateName(""))
    }
}
