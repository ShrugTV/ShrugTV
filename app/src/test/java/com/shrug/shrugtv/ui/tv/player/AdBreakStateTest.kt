package com.shrug.shrugtv.ui.tv.player

import com.shrug.shrugtv.R
import com.shrug.shrugtv.hub.HubEvent
import com.shrug.shrugtv.player.hls.TwitchAdBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ad-break sign as a state of its own. */
class AdBreakStateTest {

    private val events = ArrayList<HubEvent>()
    private val state = AdBreakState({ events.add(it) }, { "chan" })

    @Test
    fun `a break shows its sign and is told once at start and end`() {
        state.onBlockerLabel(TwitchAdBlocker.LABEL_BLOCKING)
        assertEquals(R.string.player_ad_blocking, state.label.value)
        assertTrue(state.inBreak)
        state.onBlockerLabel("stripping")
        assertEquals(R.string.player_ad_stripping, state.label.value)
        state.onBlockerLabel(null)
        assertNull(state.label.value)
        assertFalse(state.inBreak)
        assertEquals(listOf(HubEvent.AD_BREAK_START, HubEvent.AD_BREAK_END), events.map { it.type })
        assertEquals("chan", events[0].data["channel"])
    }

    @Test
    fun `no break, no event`() {
        state.onBlockerLabel(null)
        assertTrue(events.isEmpty())
    }
}
