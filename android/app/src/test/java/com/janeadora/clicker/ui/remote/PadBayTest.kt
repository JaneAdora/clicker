package com.janeadora.clicker.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the swipe pad and the two corner keys sit in the D-pad's compartment. */
class PadBayTest {
    private val geometries = listOf(
        "regular" to (411f to remoteMetrics(914f, 1f, bottomInsetDp = 24f, topInsetDp = 52f, widthDp = 411f)),
        "compact" to (411f to remoteMetrics(731f, 1f, bottomInsetDp = 24f, topInsetDp = 24f, widthDp = 411f)),
        "owner" to (360f to remoteMetrics(780f, 1.15f, bottomInsetDp = 48f, topInsetDp = 33f, widthDp = 360f)),
        "owner emulated" to (360f to remoteMetrics(780f, 1.15f, bottomInsetDp = 48f, topInsetDp = 44.4f, widthDp = 360f)),
        "narrow and short" to (320f to remoteMetrics(640f, 1f, widthDp = 320f)),
    )

    @Test fun the_swipe_pad_never_lies_under_a_key() {
        for ((name, g) in geometries) {
            val (width, m) = g
            val pad = swipePadRect(bayWidth(width), m.bay)
            val (rewind, forward) = cornerKeys(bayWidth(width), m.bay)
            assertFalse("$name rewind", pad.intersects(rewind))
            assertFalse("$name forward", pad.intersects(forward))
            assertFalse("$name keys", rewind.intersects(forward))
            // A clear strip between the pad and the keys under it.
            assertEquals(name, BAY_INSET, rewind.top - pad.bottom, 1e-3f)
        }
    }

    @Test fun the_keys_are_full_touch_targets_in_the_bottom_corners() {
        for ((name, g) in geometries) {
            val (width, m) = g
            val w = bayWidth(width)
            val (rewind, forward) = cornerKeys(w, m.bay)
            for (k in listOf(rewind, forward)) {
                assertEquals(name, 48f, k.width, 1e-3f)
                assertEquals(name, 48f, k.height, 1e-3f)
                assertEquals(name, m.bay - BAY_INSET, k.bottom, 1e-3f)
            }
            assertEquals(name, BAY_INSET, rewind.left, 1e-3f)
            assertEquals(name, w - BAY_INSET, forward.right, 1e-3f)
        }
    }

    @Test fun the_swipe_pad_stays_inside_the_bay_and_is_big_enough_to_swipe_on() {
        for ((name, g) in geometries) {
            val (width, m) = g
            val w = bayWidth(width)
            val pad = swipePadRect(w, m.bay)
            assertTrue(name, pad.left >= BAY_INSET && pad.top >= BAY_INSET && pad.right <= w - BAY_INSET)
            // Three 48dp steps of travel each way at the least.
            assertTrue("$name height ${pad.height}", pad.height >= 144f)
            assertTrue("$name width ${pad.width}", pad.width >= 144f)
        }
    }

    @Test fun rectangles_that_only_touch_do_not_intersect() {
        val a = DpRect(0f, 0f, 10f, 10f)
        assertFalse(a.intersects(DpRect(10f, 0f, 20f, 10f)))
        assertFalse(a.intersects(DpRect(0f, 10f, 10f, 20f)))
        assertTrue(a.intersects(DpRect(9f, 9f, 20f, 20f)))
    }
}
