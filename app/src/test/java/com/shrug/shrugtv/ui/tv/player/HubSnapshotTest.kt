package com.shrug.shrugtv.ui.tv.player

import androidx.media3.common.C
import com.shrug.shrugtv.hub.PlaybackSnapshot
import com.shrug.shrugtv.model.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hub snapshot of the player as a mapping of its own. */
class HubSnapshotTest {

    private fun state(movie: Movie?, vod: Boolean, liveOffsetMs: Long = C.TIME_UNSET) = HubSnapshot.State(
        movie = movie, vod = vod, status = PlaybackStatus.PLAYING,
        positionMs = 125_400L, durationMs = 3_600_000L, liveOffsetMs = liveOffsetMs,
        avatarUrl = "https://example.invalid/a.png", quality = "720p60", qualities = listOf("Auto", "720p60"),
        chatVisible = true, chatLayout = "side", multiview = false, volume = 80, audioOnly = false, followed = true
    )

    @Test
    fun `nothing playing is idle`() {
        assertSame(PlaybackSnapshot.IDLE, HubSnapshot.of(state(null, vod = false)))
    }

    @Test
    fun `a live stream reports how far it is behind and no positions`() {
        val m = Movie().apply { login = "chan"; userId = 5; gameName = "Chess" }
        val s = HubSnapshot.of(state(m, vod = false, liveOffsetMs = 7_900L))
        assertEquals("playing", s.status)
        assertEquals("live", s.kind)
        assertEquals("chan", s.channel) // no display name: the login
        assertEquals("5", s.userId)
        assertEquals("Chess", s.category)
        assertEquals(7, s.behindLiveSeconds)
        assertNull(s.vodId)
        assertNull(s.vodPositionSeconds)
        assertEquals("720p60", s.quality)
        assertEquals(80, s.volume)
        assertTrue(s.followed)
    }

    @Test
    fun `a recording reports its id, position and length`() {
        val m = Movie().apply { login = "chan"; displayName = "Chan"; setVideoIdFromString("123") }
        val s = HubSnapshot.of(state(m, vod = true, liveOffsetMs = 9_000L))
        assertEquals("vod", s.kind)
        assertEquals("Chan", s.channel)
        assertEquals(123L, s.vodId)
        assertEquals(125, s.vodPositionSeconds)
        assertEquals(3_600, s.vodLengthSeconds)
        assertEquals(0, s.behindLiveSeconds) // a recording is never behind live
        assertNull(s.startedAtMs)
        assertNull(s.category)
    }
}
