package com.janeadora.clicker.ui.typing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeBarLogicTest {
    @Test fun a_new_request_offers_the_bar() = assertTrue(shouldOfferTypeBar(prevShows = 0, nowShows = 1, active = true, dismissed = false))
    @Test fun stays_closed_if_dismissed() = assertFalse(shouldOfferTypeBar(0, 1, active = true, dismissed = true))
    @Test fun no_reopen_without_a_new_request() = assertFalse(shouldOfferTypeBar(1, 1, active = true, dismissed = false))
    @Test fun no_offer_when_the_field_is_not_active() = assertFalse(shouldOfferTypeBar(1, 2, active = false, dismissed = false))

    /** The TV asked for its keyboard once more. */
    private fun TypeFocus.shown() = seen(true, shows + 1)

    @Test fun first_request_offers() = assertTrue(TypeFocus().offers(true, 1))

    // The TV's field stays active; the TypeFocus memory carries the dismissal.
    @Test fun dismissal_holds_while_the_field_stays_active() {
        val dismissed = TypeFocus().shown().opened().closed()
        assertFalse(dismissed.offers(true, dismissed.shows))
        assertTrue(dismissed.seen(true, dismissed.shows).dismissed)
    }

    @Test fun a_second_request_after_a_manual_close_does_not_offer() {
        val closed = TypeFocus().shown().opened().closed()
        assertFalse(closed.offers(true, closed.shows + 1))
        assertFalse(closed.shown().offers(true, closed.shows + 2))
    }

    @Test fun submitting_resets_the_dismissal() {
        // Search sends the query and closes the bar: that is not a dismissal.
        val searched = TypeFocus().shown().opened().submitted().closed()
        assertFalse(searched.dismissed)
    }

    @Test fun a_second_request_after_a_submit_offers_the_bar() {
        val searched = TypeFocus().shown().opened().submitted().closed()
        assertTrue(searched.offers(true, searched.shows + 1))
    }

    @Test fun a_manual_close_after_reopening_still_dismisses() {
        // Searched once, then opened by hand and closed without searching.
        val f = TypeFocus().shown().opened().submitted().closed().opened().closed()
        assertTrue(f.dismissed)
        assertFalse(f.offers(true, f.shows + 1))
    }

    @Test fun submitting_after_a_dismissal_lets_the_next_request_offer() {
        val f = TypeFocus().shown().opened().closed().opened().submitted().closed()
        assertTrue(f.offers(true, f.shows + 1))
    }

    @Test fun dismissal_resets_after_the_link_drops() {
        // A disconnect reports the field inactive.
        val fell = TypeFocus().shown().opened().closed().seen(false, 1)
        assertFalse(fell.dismissed)
        assertTrue(fell.offers(true, 2))
    }

    @Test fun closing_with_no_tv_field_active_is_not_a_dismissal() {
        val closed = TypeFocus().opened().closed()
        assertFalse(closed.dismissed)
        assertTrue(closed.offers(true, 1))
    }

    @Test fun sends_a_changed_text_when_connected() = assertEquals("blu", textToSend(lastSent = "bl", text = "blu", connected = true))

    @Test fun never_sends_the_same_text_twice_in_a_row() = assertNull(textToSend("blu", "blu", connected = true))

    @Test fun sends_nothing_when_not_connected() = assertNull(textToSend("bl", "blu", connected = false))

    @Test fun sends_an_empty_text_after_a_clear() = assertEquals("", textToSend("bluey", "", connected = true))

    @Test fun sends_anything_when_nothing_was_sent_yet() = assertEquals("b", textToSend(null, "b", connected = true))

    @Test fun an_edit_while_connected_is_kept_and_sent() {
        val (after, send) = TypeMemory(text = "blu", lastSent = "blu").edit("blue", connected = true)
        assertEquals(TypeMemory("blue", "blue"), after)
        assertEquals("blue", send)
    }

    @Test fun a_cursor_move_keeps_the_text_and_sends_nothing() {
        val (after, send) = TypeMemory("blue", "blue").edit("blue", connected = true)
        assertEquals(TypeMemory("blue", "blue"), after)
        assertNull(send)
    }

    @Test fun no_text_is_accepted_while_disconnected() {
        val before = TypeMemory()
        val (after, send) = before.edit("secret", connected = false)
        assertEquals(before, after)
        assertNull(send)
    }

    @Test fun a_disconnect_clears_the_draft_and_what_was_sent() =
        assertEquals(TypeMemory(text = "", lastSent = ""), TypeMemory("bluey", "bluey").linkLost())

    @Test fun after_a_reconnect_only_text_typed_since_goes_out() {
        var m = TypeMemory()
        m = m.edit("blu", connected = true).first
        m = m.linkLost()
        m = m.edit("ey", connected = false).first
        val (after, send) = m.edit("x", connected = true)
        assertEquals("x", send)
        assertEquals(TypeMemory("x", "x"), after)
    }

    @Test fun clearing_after_a_reconnect_sends_nothing_when_nothing_was_typed() =
        assertNull(TypeMemory("blu", "blu").linkLost().edit("", connected = true).second)
}
