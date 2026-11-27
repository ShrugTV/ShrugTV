package com.shrug.shrugtv.ui.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Values of the statistics overlay. */
class PlayerStatsControllerTest {

    private fun sample(
        width: Int? = 1920, height: Int? = 1080, bitrate: Int = 6_000_000, bufferedMs: Long = 2_500,
        latencyMs: Long? = 3_000, chatDelayMs: Long? = null
    ) = PlayerStatsController.Sample(width, height, 60f, bitrate, bufferedMs, 3, 997, latencyMs, chatDelayMs, 120, 30f, 4)

    @Test
    fun `a reading becomes the shown values`() {
        val c = PlayerStatsController()
        c.update(sample())
        val s = c.stats.value
        assertEquals("1920 × 1080 @ 60 fps", s.resolution)
        assertEquals(6000, s.bitrateKbps)
        assertEquals(2.5f, s.bufferSeconds, 0.001f)
        assertEquals(1000, s.totalFrames)
        assertEquals(3f, s.latencySeconds, 0.001f)
        assertEquals(-1f, s.chatDelaySeconds, 0.001f)
        assertEquals(listOf(6000f), s.bitrateHistory)
    }

    @Test
    fun `without a picture there is no resolution and no bitrate`() {
        val s = PlayerStatsController.next(PlayerStatsState(), sample(width = null, height = null, latencyMs = null))
        assertEquals("–", s.resolution)
        assertEquals(0, s.bitrateKbps)
        assertEquals(-1f, s.latencySeconds, 0.001f)
    }

    @Test
    fun `the charts keep the last thirty values`() {
        val c = PlayerStatsController()
        for (i in 1..40) c.update(sample(bitrate = i * 1000))
        val h = c.stats.value.bitrateHistory
        assertEquals(PlayerStatsController.HISTORY, h.size)
        assertEquals(11f, h.first())
        assertEquals(40f, h.last())
    }
}
