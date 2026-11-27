package com.janeadora.clicker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalNetworkActionTest {
    @Test fun granted_needs_no_action() =
        assertNull(localNetworkAction(granted = true, refused = true, canAskAgain = false))

    @Test fun never_asked_asks() =
        // Before the first prompt the system reports no rationale either; only a refusal counts.
        assertEquals(LocalNetworkAction.Ask, localNetworkAction(granted = false, refused = false, canAskAgain = false))

    @Test fun refused_once_asks_again() =
        assertEquals(LocalNetworkAction.Ask, localNetworkAction(granted = false, refused = true, canAskAgain = true))

    @Test fun refused_for_good_opens_settings() =
        assertEquals(LocalNetworkAction.OpenSettings, localNetworkAction(granted = false, refused = true, canAskAgain = false))
}
