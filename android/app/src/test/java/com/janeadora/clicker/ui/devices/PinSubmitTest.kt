package com.janeadora.clicker.ui.devices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinSubmitTest {
    @Test fun five_to_six_submits() = assertTrue(shouldSubmit("A1B2C", "A1B2C3", lastSent = null, inFlight = false))

    @Test fun the_same_six_again_does_not() = assertFalse(shouldSubmit("A1B2C3", "A1B2C3", lastSent = null, inFlight = false))

    @Test fun a_seventh_key_capped_back_to_six_does_not() =
        assertFalse(shouldSubmit("A1B2C3", pinFieldText("A1B2C3D"), lastSent = "A1B2C3", inFlight = false))

    @Test fun a_selection_only_change_does_not() =
        // The text is unchanged when only the cursor moves.
        assertFalse(shouldSubmit("A1B2C3", "A1B2C3", lastSent = "A1B2C3", inFlight = false))

    @Test fun in_flight_does_not() = assertFalse(shouldSubmit("A1B2C", "A1B2C4", lastSent = "A1B2C3", inFlight = true))

    @Test fun the_code_already_sent_does_not_go_twice() =
        // Delete a character and retype it: the same code is not sent again.
        assertFalse(shouldSubmit("A1B2C", "A1B2C3", lastSent = "A1B2C3", inFlight = false))

    @Test fun a_different_six_after_an_error_does() =
        // After an error the field is cleared and the last code forgotten.
        assertTrue(shouldSubmit("D4E5F", "D4E5F6", lastSent = null, inFlight = false))

    @Test fun fewer_than_six_does_not() = assertFalse(shouldSubmit("A1B2", "A1B2C", lastSent = null, inFlight = false))
}
