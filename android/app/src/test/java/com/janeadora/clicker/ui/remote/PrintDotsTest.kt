package com.janeadora.clicker.ui.remote

import com.janeadora.clicker.ui.theme.ENTRANCE_BAND
import com.janeadora.clicker.ui.theme.ENTRANCE_MS
import com.janeadora.clicker.ui.theme.arrived
import com.janeadora.clicker.ui.theme.entranceFront
import com.janeadora.clicker.ui.theme.seedDistance
import kotlin.math.ceil
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintDotsTest {
    // A 1080 by 2340 window at 3px to the dp, and a body inset in it.
    private val w = 1080f
    private val h = 2340f
    private val pitch = 21f
    private val left = 36f
    private val top = 168f
    private val width = 1008f
    private val height = 2040f
    private val step = pitch * PRINT_STEP
    private val full = step * PRINT_DOT

    private data class Dot(val x: Float, val y: Float, val radius: Float)

    private fun walked(front: Float): List<Dot> {
        val dots = mutableListOf<Dot>()
        printDots(front, left, top, width, height, w, h, pitch) { x, y, r -> dots += Dot(x, y, r) }
        return dots
    }

    // Every print grid point on or near the body, each asked how far it has grown.
    private fun everyPoint(front: Float): List<Dot> {
        val dots = mutableListOf<Dot>()
        var row = ceil((top - full - pitch / 2f) / step).toInt()
        while (pitch / 2f + row * step - top <= height + full) {
            val y = pitch / 2f + row * step
            var column = ceil((left - full - pitch / 2f) / step).toInt()
            while (pitch / 2f + column * step <= left + width + full) {
                val x = pitch / 2f + column * step
                dots += Dot(x - left, y - top, full * arrived(front, seedDistance(x, y, w, h)))
                column++
            }
            row++
        }
        return dots
    }

    private val fronts = (0..ENTRANCE_MS step 8).map { entranceFront(it) }

    @Test fun the_walk_finds_the_same_growing_dots_as_asking_every_point() {
        // A dot within a hair of nothing or of full size may fall either side of the rounding.
        val hair = full * 0.001f
        for (front in fronts) {
            val found = walked(front).associateBy { it.x to it.y }
            val all = everyPoint(front)
            val growing = all.filter { it.radius > hair && it.radius < full - hair }
            for (dot in growing) {
                val same = found[dot.x to dot.y]
                assertTrue("front $front misses $dot", same != null)
                assertEquals("front $front at $dot", dot.radius, same!!.radius, full * 0.001f)
            }
            val known = all.filter { it.radius > 0f && it.radius < full }.map { it.x to it.y }.toSet()
            for (dot in found.values) {
                assertTrue("front $front has $dot twice or off the grid", (dot.x to dot.y) in known || dot.radius < hair || dot.radius > full - hair)
            }
            assertEquals("front $front repeats a dot", found.size, walked(front).size)
        }
    }

    @Test fun every_dot_is_still_growing() {
        for (front in fronts) for (dot in walked(front)) assertTrue("front $front $dot", dot.radius > 0f && dot.radius < full)
    }

    @Test fun nothing_is_printed_before_the_start_or_once_the_front_has_landed() {
        assertTrue(walked(0f).isEmpty())
        assertTrue(walked(1f).isEmpty())
    }

    @Test fun behind_the_ring_the_body_is_whole_out_to_the_printed_radius() {
        // Right behind the ring a dot is at full size, so the disc and the dots meet without a gap.
        for (front in fronts.filter { it > 0f && it < 1f }) {
            val whole = printedRadius(front, w, h)
            for (dot in walked(front)) assertTrue(hypot(w - (dot.x + left), dot.y + top) > whole)
            assertEquals(whole + ENTRANCE_BAND * hypot(w, h), front * (1f + ENTRANCE_BAND) * hypot(w, h), 0.01f)
        }
        assertTrue(printedRadius(0f, w, h) < 0f)
        assertTrue(printedRadius(1f, w, h) >= hypot(w, h))
    }

    @Test fun a_frame_clips_to_a_few_hundred_dots_at_most() {
        val most = fronts.maxOf { walked(it).size }
        assertTrue("$most dots", most in 1..400)
    }

    @Test fun full_grown_print_dots_overlap_so_the_print_closes_up() {
        // A square grid is covered once each dot reaches the middle of its cell: half the cell's diagonal.
        assertTrue(PRINT_DOT > 0.7072f)
    }
}
