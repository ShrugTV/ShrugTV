package com.shrug.shrugtv.ui.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leaving the player with Back and restoring it after the process ended. */
class PlayerExitTest {
    @Test
    fun `a Back right after leaving is swallowed, a later one is not`() {
        PlayerExit.mark(10_000L)
        assertTrue(PlayerExit.justLeft(10_000L))
        assertTrue(PlayerExit.justLeft(11_499L))
        assertFalse(PlayerExit.justLeft(11_500L))
        assertFalse(PlayerExit.justLeft(9_000L))
    }

    @Test
    fun `the stream comes back only shortly after the player was left`() {
        val left = 1_800_000_000_000L
        assertTrue(PlayerRestore.resume(left, left + 60_000L))
        assertTrue(PlayerRestore.resume(left, left + PlayerRestore.RESUME_WINDOW_MS - 1))
        assertFalse(PlayerRestore.resume(left, left + PlayerRestore.RESUME_WINDOW_MS))
        assertFalse(PlayerRestore.resume(left, left + 3 * 3_600_000L))
        assertFalse(PlayerRestore.resume(0L, left))
        assertFalse(PlayerRestore.resume(left, left - 5_000L))
    }
}
