package com.janeadora.clicker.ui.remote

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalmZonesTest {
    private val a = Rect(0f, 0f, 10f, 10f)
    private val b = Rect(0f, 20f, 10f, 30f)
    private val c = Rect(0f, 40f, 10f, 50f)

    @Test fun order_is_by_key_not_by_arrival() {
        val first = orderedCalm(linkedMapOf("status" to a, "dpad" to b, "nav" to c), listOf("status", "dpad", "nav"))
        val second = orderedCalm(linkedMapOf("nav" to c, "status" to a, "dpad" to b), listOf("nav", "dpad", "status"))
        assertEquals(listOf(b, c, a), first)
        assertEquals(first, second)
    }

    @Test fun nothing_until_every_group_is_placed() {
        assertNull(orderedCalm(mapOf("dpad" to b), listOf("dpad", "nav")))
        assertNull(orderedCalm(emptyMap<String, Rect>(), emptyList()))
    }
}
