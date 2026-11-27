package com.shrug.shrugtv.ui.tv.player

import androidx.media3.common.Player
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.player.LiveArchiveInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Rewinding a live stream into its recording as a controller of its own. */
class LiveRewindControllerTest {

    /** A player with a 32 s live window, 5 s behind the edge. */
    private class FakePlayer {
        var position = 0L
        var duration = 32_000L
        var liveOffset = 5_000L
        val seeks = ArrayList<Long>()
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "getCurrentPosition" -> position
                "getDuration" -> duration
                "getCurrentLiveOffset" -> liveOffset
                "seekTo" -> { if (args.size == 1) seeks.add(args[0] as Long); null }
                else -> when (m.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player
    }

    private val now = 10_000_000L
    private val info = LiveArchiveInfo(userId = 7, streamId = "s1", videoId = 99L, streamStartMs = now - 3_600_000L)
    private val fake = FakePlayer()
    private val live = Movie().apply { userId = 7; login = "chan" }
    private var current: Movie? = live
    private var loaded: Movie? = live
    private val plays = ArrayList<Pair<Movie, Long?>>()
    private var backToLive = 0
    private var found: LiveArchiveInfo? = info
    private val chapters = ArrayList<Long>()
    private var enabled = true
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private lateinit var rc: LiveRewindController

    init {
        rc = LiveRewindController(
            scope = scope,
            player = { fake.player },
            currentMovie = { current },
            loadedMovie = { loaded },
            isVod = { it.videoId > 0L },
            // Like PlayerViewModel.play(): the new item is shown at once
            play = { m, at -> plays.add(m to at); rc.onPlay(m); current = m },
            onBackToLive = { backToLive++ },
            find = { found },
            onFound = { chapters.add(it) },
            enabled = { enabled },
            io = Dispatchers.Unconfined,
            now = { now }
        )
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `without a recording the timeline is the player position`() {
        fake.position = 1_234L
        assertFalse(rc.isActive())
        assertArrayEquals(longArrayOf(1_234L, 32_000L), rc.timelineMs())
    }

    @Test
    fun `the lookup finds the recording and loads its categories`() {
        rc.lookup(live)
        assertSame(info, rc.archive.value)
        assertEquals(listOf(99L), chapters)
        assertTrue(rc.isActive())
        // Live: one hour since the start, 5 s behind the edge
        assertArrayEquals(longArrayOf(3_595_000L, 3_600_000L), rc.timelineMs())
    }

    @Test
    fun `switched off nothing is looked up`() {
        enabled = false
        rc.lookup(live)
        assertNull(rc.archive.value)
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun `a short step back stays in the live window`() {
        rc.lookup(live)
        assertEquals(3_585_000L, rc.rewindBy(-10_000L))
        assertEquals(listOf(17_000L), fake.seeks)
        assertTrue(plays.isEmpty())
    }

    @Test
    fun `a long step back switches into the recording and lands there once it is ready`() {
        rc.lookup(live)
        assertEquals(2_995_000L, rc.rewindBy(-600_000L))
        val (archive, at) = plays.single()
        assertTrue(archive.archiveOfLive)
        assertEquals(99L, archive.videoId)
        assertEquals(2_995_000L, at)
        assertEquals(LiveRewindController.SWITCH_TO_ARCHIVE, rc.switch.value)
        assertTrue(rc.loading)
        assertTrue(rc.switching)
        // Presses while it loads only move the target
        assertEquals(2_935_000L, rc.rewindBy(-60_000L))
        assertArrayEquals(longArrayOf(2_935_000L, 3_600_000L), rc.timelineMs())
        assertTrue(fake.seeks.isEmpty())
        // Ready: the player is moved to the target, the switch is over
        loaded = archive
        fake.position = 2_995_000L
        rc.onReady()
        assertFalse(rc.loading)
        assertFalse(rc.switching)
        assertEquals(LiveRewindController.SWITCH_NONE, rc.switch.value)
        assertEquals(listOf(2_935_000L), fake.seeks)
    }

    @Test
    fun `back to live plays the live stream again`() {
        rc.lookup(live)
        rc.rewindBy(-600_000L)
        rc.exitArchive()
        assertEquals(1, backToLive)
        assertSame(live, plays.last().first)
        assertNull(plays.last().second)
        assertEquals(LiveRewindController.SWITCH_TO_LIVE, rc.switch.value)
    }

    @Test
    fun `another channel forgets the way back`() {
        rc.lookup(live)
        rc.rewindBy(-600_000L)
        val other = Movie().apply { userId = 8 }
        rc.onPlay(other)
        current = other
        rc.exitArchive()
        assertEquals(0, backToLive)
        assertEquals(1, plays.size)
        assertEquals(LiveRewindController.SWITCH_NONE, rc.switch.value)
    }
}
