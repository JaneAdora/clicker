package com.janeadora.clicker.ui.theme

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotGroundTest {
    private fun box(l: Float, t: Float, r: Float, b: Float) = CalmZone.Rounded(Rect(l, t, r, b), 0f)

    @Test fun dots_vanish_inside_calm_zones() =
        assertEquals(0f, dotRadius(100f, 100f, 400f, 800f, listOf(box(50f, 50f, 150f, 150f))))

    @Test fun dots_vanish_in_the_6dp_margin_around_calm_zones() {
        val calm = listOf(box(300f, 50f, 350f, 100f))
        // 5px to the right of the zone at density 1, near the busy top-right corner.
        assertEquals(0f, dotRadius(355f, 75f, 400f, 800f, calm))
        // The same point with no calm zone has a dot.
        assertTrue(dotRadius(355f, 75f, 400f, 800f, emptyList()) > 0f)
        // At density 3 the margin is 18px.
        val calm3 = listOf(box(900f, 150f, 1050f, 300f))
        assertEquals(0f, dotRadius(1067f, 225f, 1200f, 2400f, calm3, density = 3f))
    }

    @Test fun inside_a_circle_zone_is_zero() {
        val c = listOf(CalmZone.Circle(350f, 60f, 24f))
        assertEquals(0f, dotRadius(350f, 60f, 400f, 800f, c))
        assertEquals(0f, dotRadius(370f, 60f, 400f, 800f, c))
    }

    @Test fun the_clearing_fades_out_softly() {
        val c = listOf(CalmZone.Circle(200f, 400f, 24f))
        // Just past the margin the dot is only partly back; far away it is whole.
        val near = calmFactor(200f + 24f + 6f + 6f, 400f, c)
        val far = calmFactor(200f + 24f + 6f + 40f, 400f, c)
        assertTrue("near $near", near > 0f && near < far)
        assertEquals(1f, far)
        // So the same spot with and without the zone: smaller beside it.
        val bare = dotRadius(360f, 70f, 400f, 800f, emptyList())
        val beside = dotRadius(360f, 70f, 400f, 800f, listOf(CalmZone.Circle(320f, 70f, 24f)))
        assertTrue("beside $beside bare $bare", beside > 0f && beside < bare)
    }

    @Test fun a_circle_clears_the_same_on_every_side() {
        val c = listOf(CalmZone.Circle(200f, 400f, 24f))
        val d = 24f + 6f + 14f
        val right = calmFactor(200f + d, 400f, c)
        val left = calmFactor(200f - d, 400f, c)
        val up = calmFactor(200f, 400f - d, c)
        val diagonal = calmFactor(200f + d * 0.6f, 400f + d * 0.8f, c)
        assertEquals(right, left, 1e-5f)
        assertEquals(right, up, 1e-5f)
        assertEquals(right, diagonal, 1e-4f)
    }

    @Test fun rounded_zone_distance_follows_the_corner() {
        val z = CalmZone.Rounded(Rect(0f, 0f, 100f, 40f), 20f)
        assertEquals(0f, z.distanceTo(50f, 0f), 1e-4f)
        assertEquals(10f, z.distanceTo(110f, 20f), 1e-4f)
        // Off the rounded corner the distance is to the arc, not the box corner.
        val corner = z.distanceTo(100f + 10f, -10f)
        assertEquals(kotlin.math.hypot(30f, 30f) - 20f, corner, 1e-3f)
        assertTrue(z.distanceTo(50f, 20f) < 0f)
    }

    @Test fun the_top_right_reaches_further_than_before() =
        // Halfway down the right edge, beside where the D-pad sits, there are dots.
        assertTrue(dotRadius(395f, 380f, 400f, 800f, emptyList()) > 0f)

    @Test fun dots_grow_toward_top_right() =
        assertTrue(dotRadius(390f, 10f, 400f, 800f, emptyList()) > dotRadius(200f, 400f, 400f, 800f, emptyList()))

    @Test fun dots_grow_toward_bottom_left() =
        assertTrue(dotRadius(10f, 790f, 400f, 800f, emptyList()) > dotRadius(200f, 400f, 400f, 800f, emptyList()))

    @Test fun the_middle_and_the_other_corners_stay_quiet() {
        assertEquals(0f, dotRadius(200f, 400f, 400f, 800f, emptyList()))
        assertEquals(0f, dotRadius(5f, 5f, 400f, 800f, emptyList()))
        assertEquals(0f, dotRadius(395f, 795f, 400f, 800f, emptyList()))
    }

    @Test fun dots_never_exceed_max() {
        val w = 400f
        val h = 800f
        var largest = 0f
        for (i in 0..19) for (j in 0..19) {
            val r = dotRadius(i * w / 19f, j * h / 19f, w, h, emptyList())
            assertTrue("radius $r at ($i, $j)", r in 0f..2.2f)
            largest = maxOf(largest, r)
        }
        // The corners reach the maximum, so the field is not empty.
        assertEquals(2.2f, largest, 0.01f)
    }

    @Test fun ink_bands_run_small_mid_large() {
        assertEquals(0, inkBand(0.5f, density = 1f))
        assertEquals(1, inkBand(1.2f, density = 1f))
        assertEquals(2, inkBand(2.2f, density = 1f))
        assertEquals(2, inkBand(6.6f, density = 3f))
    }
}
