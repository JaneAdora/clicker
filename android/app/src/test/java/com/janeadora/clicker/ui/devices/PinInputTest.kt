package com.janeadora.clicker.ui.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinInputTest {
    @Test fun lowercase_and_spaces_are_accepted() = assertEquals("A1B2C3", normalizePin("  a1b2c3 "))

    @Test fun five_chars_rejected() = assertNull(normalizePin("A1B2C"))

    @Test fun seven_chars_rejected() = assertNull(normalizePin("A1B2C3D"))

    @Test fun non_hex_rejected() = assertNull(normalizePin("A1B2CG"))

    @Test fun inner_space_rejected() = assertNull(normalizePin("A1B 2C3"))

    @Test fun typing_keeps_hex_uppercased_and_stops_at_six() =
        assertEquals("A1B2C3", pinFieldText("a1-b2 g c3d4"))

    @Test fun typing_drops_everything_that_is_not_hex() = assertEquals("", pinFieldText("xyz!?"))
}
