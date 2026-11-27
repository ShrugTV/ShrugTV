package com.shrug.shrugtv.ui.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** The open overlay of the player as a controller of its own. */
class PlayerOverlayControllerTest {

    private val left = ArrayList<PlayerOverlayType>()
    private val overlays = PlayerOverlayController { left.add(it) }

    @Test
    fun `starts with no overlay`() {
        assertEquals(PlayerOverlayType.NONE, overlays.active.value)
    }

    @Test
    fun `toggle opens an overlay and closes it on the second call`() {
        overlays.toggle(PlayerOverlayType.STATS)
        assertEquals(PlayerOverlayType.STATS, overlays.active.value)
        overlays.toggle(PlayerOverlayType.QUALITY)
        assertEquals(PlayerOverlayType.QUALITY, overlays.active.value)
        overlays.toggle(PlayerOverlayType.QUALITY)
        assertEquals(PlayerOverlayType.NONE, overlays.active.value)
    }

    @Test
    fun `show keeps an open overlay open, dismiss closes every overlay`() {
        overlays.show(PlayerOverlayType.CONTROLS)
        overlays.show(PlayerOverlayType.CONTROLS)
        assertEquals(PlayerOverlayType.CONTROLS, overlays.active.value)
        overlays.dismiss()
        assertEquals(PlayerOverlayType.NONE, overlays.active.value)
    }

    @Test
    fun `menus of the bar go back to the bar, the bar and the switcher to the video`() {
        val toBar = listOf(
            PlayerOverlayType.QUALITY, PlayerOverlayType.SPEED, PlayerOverlayType.CHAPTERS, PlayerOverlayType.HIGHLIGHTS, PlayerOverlayType.STATS,
            PlayerOverlayType.SEARCH, PlayerOverlayType.SEND_MESSAGE, PlayerOverlayType.LAYOUT_EDITOR
        )
        for (o in PlayerOverlayType.entries) {
            overlays.show(o)
            overlays.back()
            assertEquals(o.name, if (o in toBar) PlayerOverlayType.CONTROLS else PlayerOverlayType.NONE, overlays.active.value)
        }
    }

    @Test
    fun `back tells which overlay it leaves`() {
        overlays.show(PlayerOverlayType.LAYOUT_EDITOR)
        overlays.back()
        overlays.back()
        assertEquals(listOf(PlayerOverlayType.LAYOUT_EDITOR, PlayerOverlayType.CONTROLS), left)
    }
}
