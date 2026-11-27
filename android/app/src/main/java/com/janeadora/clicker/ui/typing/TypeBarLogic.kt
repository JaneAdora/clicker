package com.janeadora.clicker.ui.typing

/**
 * Whether the type bar opens by itself: when the TV asked for its keyboard
 * again ([nowShows] differs from the count last seen, [prevShows]) while
 * its field is [active], and the user has not closed the bar during this
 * focus ([dismissed]).
 */
fun shouldOfferTypeBar(prevShows: Int, nowShows: Int, active: Boolean, dismissed: Boolean): Boolean =
    active && nowShows != prevShows && !dismissed

/**
 * What the app remembers about the TV's text field: whether it was last
 * reported [active], how many keyboard requests have been seen ([shows]),
 * and whether the user closed the bar without searching ([dismissed]).
 *
 * The TV only ever reports a field becoming active, never inactive, so
 * each request counts as a new chance to offer the bar. A close without a
 * search holds the bar back until the next search or the link drops (which
 * reports the field inactive). A search closes the bar too, but is not a
 * dismissal ([justSubmitted] marks it until the bar opens again).
 */
data class TypeFocus(
    val active: Boolean = false,
    val shows: Int = 0,
    val dismissed: Boolean = false,
    val justSubmitted: Boolean = false,
) {
    /** True when the TV reporting [nowActive] with [nowShows] requests should open the bar. */
    fun offers(nowActive: Boolean, nowShows: Int): Boolean = shouldOfferTypeBar(shows, nowShows, nowActive, dismissed)

    /** After the TV reports [nowActive] with [nowShows] requests. */
    fun seen(nowActive: Boolean, nowShows: Int): TypeFocus =
        copy(active = nowActive, shows = nowShows, dismissed = nowActive && dismissed)

    /** After the bar opened, by hand or by itself. */
    fun opened(): TypeFocus = copy(justSubmitted = false)

    /** After the user searched: the next request offers the bar again. */
    fun submitted(): TypeFocus = copy(dismissed = false, justSubmitted = true)

    /** After the bar closed. Closing with no field active on the TV, or by searching, is not a dismissal. */
    fun closed(): TypeFocus = copy(dismissed = active && !justSubmitted)
}

/**
 * The text to send to the TV for a change in the bar, or null to send
 * nothing: nothing while not [connected] (text is never queued for later)
 * and nothing when [text] is what was sent last ([lastSent]).
 */
fun textToSend(lastSent: String?, text: String, connected: Boolean): String? =
    if (connected && text != lastSent) text else null

/**
 * What the bar remembers: the field's [text] and the text last sent to the
 * TV. An empty field counts as sent, so opening the bar never clears the
 * TV's field.
 */
data class TypeMemory(val text: String = "", val lastSent: String = "") {
    /**
     * The user changed the field to [newText]. Returns the memory after the
     * change and the text to send, or null. While not [connected] the
     * change is refused: the field stays as it is and nothing is sent.
     */
    fun edit(newText: String, connected: Boolean): Pair<TypeMemory, String?> {
        if (!connected) return this to null
        val send = textToSend(lastSent, newText, connected = true)
        return TypeMemory(newText, send ?: lastSent) to send
    }

    /**
     * The link dropped: the field empties and nothing counts as sent, so
     * nothing typed before or during the outage can go out after a reconnect.
     */
    fun linkLost(): TypeMemory = TypeMemory()
}
