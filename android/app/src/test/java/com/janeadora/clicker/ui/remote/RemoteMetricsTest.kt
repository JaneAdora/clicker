package com.janeadora.clicker.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class RemoteMetricsTest {
    // The three screens the body is checked on: the regular emulator, a
    // compact phone, and the owner's phone (360 x 780dp, larger text, three
    // button navigation). The owner's phone twice: with her own status bar,
    // and with the emulator's taller one.
    private fun regular() = remoteMetrics(914f, 1f, bottomInsetDp = 24f, topInsetDp = 52f, widthDp = 411f)
    private fun compact() = remoteMetrics(731f, 1f, bottomInsetDp = 24f, topInsetDp = 24f, widthDp = 411f)
    private fun owner() = remoteMetrics(780f, 1.15f, bottomInsetDp = 48f, topInsetDp = 33f, widthDp = 360f)
    private fun ownerEmulated() = remoteMetrics(780f, 1.15f, bottomInsetDp = 48f, topInsetDp = 44.4f, widthDp = 360f)
    private val three = listOf(Triple("regular", ::regular, 411f), Triple("compact", ::compact, 411f), Triple("owner", ::owner, 360f), Triple("owner emulated", ::ownerEmulated, 360f))

    @Test fun tall_phone_gets_the_regular_set() {
        val m = regular()
        assertFalse(m.compact)
        assertEquals(240f, m.dpad)
        assertEquals(88f, m.ok)
        assertEquals(72f, m.tile)
        assertTrue(m.fits)
    }

    @Test fun short_phone_gets_the_compact_set() {
        val m = compact()
        assertTrue(m.compact)
        assertEquals(208f, m.dpad)
        assertEquals(76f, m.ok)
        assertEquals(64f, m.tile)
        assertTrue(m.fits)
    }

    @Test fun the_owners_phone_fits_without_scrolling_and_keeps_the_compact_dpad() {
        for (m in listOf(owner(), ownerEmulated())) {
            assertTrue(m.fits)
            assertEquals(208f, m.dpad)
            assertEquals(64f, m.tile)
        }
        // The body starts under the status bar, with its margin.
        assertTrue("top ${owner().shellTop}", owner().shellTop >= 33f + SHELL_TOP)
        assertTrue(ownerEmulated().shellTop >= 44.4f + SHELL_TOP)
    }

    @Test fun the_body_does_not_depend_on_link_state_or_notice() {
        // remoteMetrics takes the screen and nothing else, so the link cannot
        // reach it. What does change with the link is what the display row
        // holds, and that row is a key tall whatever it holds.
        for ((name, make, _) in three) {
            val m = make()
            for (link in com.janeadora.clicker.core.Link.entries) {
                for (permissionMissing in listOf(false, true)) {
                    val at = "$name $link missing=$permissionMissing"
                    assertEquals(at, m, make())
                    assertEquals(at, m.key, m.height(BodyRow.Display))
                    // With or without a key beside the display.
                    plateKey(link, permissionMissing)
                    assertTrue(at, m.height(BodyRow.Display) >= ROW_HEIGHT)
                }
            }
        }
    }

    @Test fun the_body_is_the_sum_of_its_rows() {
        // The Portrait column walks BodyRow.entries with gapBefore and height; so does this.
        for ((name, make, _) in three) {
            val m = make()
            var y = m.shellTop + SHELL_PAD
            for (row in BodyRow.entries) {
                y += m.gapBefore(row)
                assertEquals("$name $row top", y, m.top(row), 1e-3f)
                y += m.height(row)
            }
            assertEquals("$name height", y + SHELL_PAD - m.shellTop, m.bodyHeight, 1e-3f)
        }
    }

    @Test fun the_body_ends_on_the_bottom_bar_with_its_margin() {
        val m = owner()
        assertEquals(780f - 48f - BOTTOM_PAD - SHELL_DEPTH, m.shellTop + m.bodyHeight, 1e-3f)
    }

    @Test fun every_row_is_at_least_a_touch_target_tall() {
        for ((name, make, _) in three) {
            val m = make()
            for (row in BodyRow.entries) assertTrue("$name $row ${m.height(row)}", m.height(row) >= 48f)
            assertEquals("$name display is as tall as a key", m.key, m.display)
        }
    }

    @Test fun gaps_never_go_below_8_and_keys_never_below_48() {
        for (h in listOf(600f, 640f, 731f, 760f, 780f, 800f, 840f, 914f, 1000f, 1400f)) {
            for (scale in listOf(1f, 1.15f, 1.3f, 2f)) {
                for (w in listOf(320f, 360f, 411f)) {
                    val m = remoteMetrics(h, scale, widthDp = w)
                    val at = "h=$h scale=$scale w=$w"
                    assertTrue(at, m.gap >= 8f)
                    assertTrue(at, m.key >= 48f)
                    assertTrue(at, m.tile >= 48f)
                    assertTrue(at, m.display >= 48f)
                    assertTrue(at, m.bayPad >= 10f)
                    assertTrue(at, m.bayDrop >= 0f)
                }
            }
        }
    }

    @Test fun the_dpad_never_goes_below_200() {
        for (h in 480..1400 step 20) {
            for (scale in listOf(1f, 1.3f, 2f)) assertTrue("h=$h scale=$scale", remoteMetrics(h.toFloat(), scale).dpad >= 200f)
        }
    }

    @Test fun the_rack_shrinks_before_the_dpad() {
        // Walk down from a height that holds the compact set: tiles go 64, 56, 48 while the D-pad stays 208.
        var last = remoteMetrics(731f, 1f)
        for (h in 730 downTo 560) {
            val m = remoteMetrics(h.toFloat(), 1f)
            if (!m.fits) break
            assertTrue("h=$h tile grew", m.tile <= last.tile)
            if (m.tile > 48f) assertEquals("h=$h", 208f, m.dpad)
            last = m
        }
        assertEquals(48f, last.tile)
        assertEquals(200f, last.dpad)
    }

    @Test fun too_short_a_screen_says_so_and_scrolls() {
        val m = remoteMetrics(480f, 1f)
        assertFalse(m.fits)
        assertEquals(200f, m.dpad)
    }

    @Test fun the_dpad_centre_is_in_the_lower_two_thirds() {
        for ((name, m, h) in listOf(Triple("regular", regular(), 914f), Triple("compact", compact(), 731f), Triple("owner", owner(), 780f))) {
            assertTrue("$name centre ${m.dpadCentre}", m.dpadCentre >= h / 3f)
        }
        for (h in 600..1400 step 20) {
            val m = remoteMetrics(h.toFloat(), 1f)
            if (m.fits) assertTrue("h=$h centre ${m.dpadCentre}", m.dpadCentre >= h / 3f)
        }
    }

    @Test fun a_body_that_fits_stays_between_the_bars() {
        for (h in 600..1400 step 20) {
            for (scale in listOf(1f, 1.15f, 1.3f)) {
                val m = remoteMetrics(h.toFloat(), scale, bottomInsetDp = 48f, topInsetDp = 33f, widthDp = 360f)
                if (m.fits) assertTrue("h=$h scale=$scale top ${m.shellTop}", m.shellTop >= 33f + SHELL_TOP)
            }
        }
    }

    @Test fun spare_height_fills_the_body_before_it_is_left_above() {
        // The regular emulator has room to spare: gaps, keys and the room around the D-pad all reach their largest.
        val m = regular()
        assertEquals(16f, m.gap)
        assertEquals(56f, m.key)
        assertEquals(56f, m.display)
        assertEquals(40f, m.bayPad)
    }

    @Test fun corner_keys_keep_8dp_clear_of_the_ring_everywhere() {
        for (w in listOf(320f, 340f, 360f, 393f, 411f, 600f)) {
            for (h in listOf(640f, 700f, 780f, 914f)) {
                val m = remoteMetrics(h, 1f, widthDp = w)
                val clear = cornerKeyClearance(bayWidth(w), m.dpad, m.bayPad + m.bayDrop)
                assertTrue("w=$w h=$h clear $clear", clear >= MIN_CLEARANCE)
            }
        }
    }

    @Test fun corner_keys_get_16dp_clear_at_the_three_geometries() {
        for ((name, make, w) in three) {
            val m = make()
            val clear = cornerKeyClearance(bayWidth(w), m.dpad, m.bayPad + m.bayDrop)
            assertTrue("$name clear $clear", clear >= WANTED_CLEARANCE)
        }
    }

    @Test fun the_room_under_the_ring_is_never_less_than_above_it() {
        for ((name, make, _) in three) assertTrue(name, make().bayDrop >= 0f)
        // On a 360dp phone the keys need more room below than above.
        assertTrue(owner().bayDrop > 0f)
    }

    @Test fun corner_key_clearance_is_measured_from_the_keys_inner_corner() {
        // A 300 wide bay with a 200 ring and 47 under its 3dp base: the key's
        // corner is 56 in from the side and the floor, 94 from the centre on each axis.
        assertEquals(94f * sqrt(2f) - 100f, cornerKeyClearance(300f, 200f, 47f), 1e-3f)
    }

    @Test fun the_bay_is_as_wide_as_the_body_less_its_padding() {
        assertEquals(360f - 24f - SHELL_DEPTH - 2 * SHELL_PAD, bayWidth(360f))
        // A tablet's body stops at its widest.
        assertEquals(SHELL_MAX_WIDTH - SHELL_DEPTH - 2 * SHELL_PAD, bayWidth(900f))
    }
}
