package com.janeadora.clicker.ui.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.clicker_ffi.ShortcutSlot

class ShortcutEditTest {
    private val order = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    private fun slots(vararg filled: Pair<String, String>): List<ShortcutSlot> {
        val m = filled.toMap()
        return order.map { s -> ShortcutSlot(s, m[s].orEmpty(), if (s in m) "https://$s.example" else "") }
    }

    @Test fun filled_slots_in_slot_order_then_add_on_the_first_empty_slot() {
        val tiles = rowTiles(slots("0" to "Zero", "2" to "YouTube", "1" to "Netflix"))
        assertEquals(
            listOf(RowTile.Filled("1", "Netflix"), RowTile.Filled("2", "YouTube"), RowTile.Filled("0", "Zero"), RowTile.Add("3")),
            tiles,
        )
    }

    @Test fun no_add_tile_when_every_slot_is_filled() {
        val tiles = rowTiles(slots(*order.map { it to "App $it" }.toTypedArray()))
        assertEquals(10, tiles.size)
        assertTrue(tiles.all { it is RowTile.Filled })
        assertEquals("0", (tiles.last() as RowTile.Filled).slot)
    }

    @Test fun only_the_add_tile_when_every_slot_is_empty() =
        assertEquals(listOf(RowTile.Add("1")), rowTiles(slots()))

    @Test fun add_after_nine_is_zero() {
        val tiles = rowTiles(slots(*order.dropLast(1).map { it to "App $it" }.toTypedArray()))
        assertEquals(RowTile.Add("0"), tiles.last())
    }

    @Test fun empty_label_reads_as_its_slot() {
        val tiles = rowTiles(listOf(ShortcutSlot("4", "", "https://x.example")))
        assertEquals(RowTile.Filled("4", ""), tiles.first())
        assertEquals("Shortcut 4", (tiles.first() as RowTile.Filled).description)
        assertEquals("Netflix", RowTile.Filled("1", "Netflix").description)
    }

    @Test fun empty_link_is_refused() {
        assertEquals(Problem(Field.Link, LINK_NEEDED), checkShortcut("Netflix", "   ", hasImage = true))
    }

    @Test fun empty_label_needs_an_image() {
        assertEquals(Problem(Field.Label, NAME_OR_IMAGE_NEEDED), checkShortcut("  ", "https://x", hasImage = false))
        assertNull(checkShortcut("", "https://x", hasImage = true))
        assertNull(checkShortcut("Plex", "com.plexapp.android", hasImage = false))
    }

    @Test fun kept_image_shows_the_saved_one() {
        val d = ImageDraft(saved = true)
        assertTrue(d.shows)
        assertEquals(ImageSave.Nothing, d.onSave)
    }

    @Test fun chosen_image_shows_but_only_lands_on_save() {
        val d = ImageDraft(saved = false).chosen()
        assertTrue(d.shows)
        assertTrue(d.pending)
        assertEquals(ImageSave.Commit, d.onSave)
    }

    @Test fun removed_image_hides_and_removes_on_save() {
        val d = ImageDraft(saved = true).removed()
        assertFalse(d.shows)
        assertFalse(d.pending)
        assertEquals(ImageSave.Remove, d.onSave)
    }

    @Test fun choosing_after_removing_replaces_and_removing_a_choice_drops_it() {
        assertEquals(ImageSave.Commit, ImageDraft(saved = true).removed().chosen().onSave)
        val dropped = ImageDraft(saved = false).chosen().removed()
        assertFalse(dropped.shows)
        assertFalse(dropped.pending)
        // Nothing was saved before, so there is nothing to remove, but removing is harmless.
        assertEquals(ImageSave.Remove, dropped.onSave)
    }

    @Test fun a_known_picture_never_shows_the_label_while_it_loads() {
        assertEquals(TilePicture.Loading, tilePicture(null, onDisk = true))
        assertEquals(TilePicture.None, tilePicture(null, onDisk = false))
        // Not looked up yet: the label, since most tiles have none.
        assertEquals(TilePicture.None, tilePicture(null, onDisk = null))
    }

    @Test fun save_is_offered_only_when_none_is_in_flight() {
        assertTrue(SaveGate().canSave)
        assertFalse(SaveGate().started("3").canSave)
    }

    @Test fun the_outcome_of_the_save_in_flight_is_taken() {
        val gate = SaveGate().started("3")
        assertTrue(gate.takes("3", editing = "3"))
        assertTrue(gate.finished().canSave)
    }

    @Test fun a_second_outcome_after_the_editor_closed_is_ignored() {
        // The first outcome closed the editor; one more must not close a reopened one.
        val after = SaveGate().started("3").finished()
        assertFalse(after.takes("3", editing = "3"))
        assertFalse(SaveGate().started("3").takes("3", editing = null))
        assertFalse(SaveGate().started("3").takes("4", editing = "4"))
    }
}
