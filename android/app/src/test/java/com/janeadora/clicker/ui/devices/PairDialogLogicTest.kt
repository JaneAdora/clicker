package com.janeadora.clicker.ui.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairDialogLogicTest {
    @Test fun a_wrong_code_says_the_code_did_not_match() =
        assertEquals(PIN_MISMATCH, pairMessage("PIN checksum mismatch", wrongCode = true))

    @Test fun a_tv_that_went_away_says_it_is_not_answering() =
        assertEquals("The TV is not answering. Check that it is on.", pairMessage("pairing connect failed: x", wrongCode = false))

    @Test fun no_error_no_message() = assertNull(pairMessage(null, wrongCode = false))

    @Test fun a_wrong_code_clears_the_field_for_the_new_one() =
        assertEquals(PinEntry(), PinEntry(pin = "A1B2C3", lastSent = "A1B2C3").afterFailure(wrongCode = true))

    @Test fun a_tv_that_went_away_keeps_the_half_typed_code() =
        assertEquals(PinEntry(pin = "A1B", lastSent = null), PinEntry(pin = "A1B", lastSent = null).afterFailure(wrongCode = false))

    @Test fun after_the_tv_went_away_a_full_code_can_be_sent_again() {
        // A code typed while the TV was away never reached it, so it may go again.
        val kept = PinEntry(pin = "A1B2C3", lastSent = "A1B2C3").afterFailure(wrongCode = false)
        assertEquals("A1B2C3", kept.pin)
        assertTrue(shouldSubmit("A1B2C", "A1B2C3", kept.lastSent, inFlight = false))
    }

    @Test fun a_code_with_no_answer_leaves_the_dialog_usable() {
        // The finish timeout reaches the dialog as a failed code: cleared, ready for the next.
        val after = PinEntry(pin = "A1B2C3", lastSent = "A1B2C3").afterFailure(wrongCode = true)
        assertTrue(shouldSubmit("D4E5F", "D4E5F6", after.lastSent, inFlight = false))
    }
}
