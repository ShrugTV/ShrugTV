package com.shrug.shrugtv.ui.tv.player

import android.app.Application
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shrug.shrugtv.hub.HubEvent
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.RaidNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** The cards over the picture as a controller of their own. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlayerCardsControllerTest {

    private class FakePlayer {
        var volume = 0f
        var playWhenReady = false
        var released = false
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "getVolume" -> volume
                "setVolume" -> { volume = args[0] as Float; null }
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { playWhenReady = args[0] as Boolean; null }
                "release" -> { released = true; null }
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

    private fun channel(id: Int, login: String, favourite: Boolean = false) =
        Movie().apply { userId = id; this.login = login; isFavorite = favourite }

    private fun recording(id: Long) = Movie().apply { setVideoIdFromString(id.toString()); userId = 7 }

    private val live = channel(7, "chan")
    private var current: Movie? = live
    private val events = ArrayList<HubEvent>()
    private val plays = ArrayList<Pair<Movie, Player?>>()
    private var mode = "countdown"
    private var raidCards = true
    private var followed: List<Movie>? = listOf(channel(8, "other"), channel(9, "fav", favourite = true), channel(7, "chan"))
    private var next: Movie? = null
    private val preloadPlayer = FakePlayer()
    private var clipJob: Job? = null
    private val clipsPlayed = ArrayList<Movie>()
    private var archiveExits = 0
    private var now = 1_000_000L

    /** Every wait of a card hangs here until the test lets it go; a send without a waiter is lost. */
    private val waits = ArrayList<Long>()
    private val gate = Channel<Unit>()

    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val slot = PreloadSlot<Player>()

    private fun cards(vodNext: Boolean = true) = PlayerCardsController(
        scope = scope,
        slot = slot,
        current = { current },
        isVod = { it.videoId > 0L },
        endOfStreamMode = mode,
        vodNextEnabled = vodNext,
        raidCardEnabled = { raidCards },
        emit = { events.add(it) },
        followedLive = { followed },
        nextRecording = { next },
        preloadRecording = { m -> slot.hold(m, preloadPlayer.player, "https://example.invalid/next.m3u8") },
        playNextClip = { clipsPlayed.add(it); clipJob },
        exitArchive = { archiveExits++ },
        play = { m, p -> plays.add(m to p); current = m },
        elapsed = { now },
        wait = { ms -> waits.add(ms); gate.receive() }
    )

    /** Lets [n] waits run out. */
    private fun pass(n: Int = 1) = repeat(n) { gate.trySend(Unit) }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a raid goes to the hub and offers the card until its time runs out`() {
        val c = cards()
        c.onRaid(RaidNotification("chan", "Target", "42"))
        assertEquals(HubEvent.RAID, events.single().type)
        assertEquals("chan", events.single().data["from"])
        assertEquals("42", events.single().data["to_id"])
        assertEquals("Target", c.pendingRaid.value?.targetDisplayName)
        assertEquals(PlayerCardsController.RAID_OFFER_SECONDS * 1000L, waits.single())
        pass()
        assertNull(c.pendingRaid.value)
    }

    @Test
    fun `a raid without target does nothing and the setting hides only the card`() {
        val c = cards()
        c.onRaid(RaidNotification("chan", "Target", ""))
        assertTrue(events.isEmpty())
        raidCards = false
        c.onRaid(RaidNotification("chan", "Target", "42"))
        assertEquals(1, events.size)
        assertNull(c.pendingRaid.value)
    }

    @Test
    fun `OK on the raid card plays the raided channel by its id`() {
        val c = cards()
        c.onRaid(RaidNotification("chan", "Target", "42"))
        c.followRaid()
        assertNull(c.pendingRaid.value)
        val (target, player) = plays.single()
        assertEquals(42, target.userId)
        assertEquals("Target", target.displayName)
        assertNull(player)
    }

    @Test
    fun `the live card shows for another channel, not while a raid is offered, and OK switches`() {
        val c = cards()
        c.onFollowedLive(channel(7, "chan"))
        assertNull(c.liveCard.value)
        c.onRaid(RaidNotification("chan", "Target", "42"))
        c.onFollowedLive(channel(8, "other"))
        assertNull(c.liveCard.value)
        c.dismissRaid()
        val other = channel(8, "other")
        c.onFollowedLive(other)
        assertSame(other, c.liveCard.value)
        c.switchToLiveCard()
        assertNull(c.liveCard.value)
        assertSame(other, plays.single().first)
    }

    @Test
    fun `a second live card waits for the gap`() {
        val c = cards()
        c.onFollowedLive(channel(8, "other"))
        c.dismissLiveCard()
        now += 1_000L
        c.onFollowedLive(channel(9, "fav"))
        assertNull(c.liveCard.value)
        now += LiveCardGate.MIN_GAP_MS
        c.onFollowedLive(channel(9, "fav"))
        assertEquals("fav", c.liveCard.value?.login)
    }

    @Test
    fun `an ended live stream suggests the favourite and switches after the countdown`() {
        val c = cards()
        c.offerNext()
        assertEquals(HubEvent.STREAM_ENDED, events.single().type)
        assertEquals("fav", c.endSuggestion.value?.login)
        assertEquals(PlayerCardsController.END_COUNTDOWN_SECONDS, c.endCountdown.value)
        pass(PlayerCardsController.END_COUNTDOWN_SECONDS - 1)
        assertEquals(1, c.endCountdown.value)
        assertTrue(plays.isEmpty())
        pass()
        assertEquals("fav", plays.single().first.login)
        assertNull(c.endSuggestion.value)
        assertEquals(0, c.endCountdown.value)
    }

    @Test
    fun `suggest only shows the card, off only tells the hub`() {
        mode = "suggest"
        val c = cards()
        c.offerNext()
        assertEquals("fav", c.endSuggestion.value?.login)
        assertEquals(0, c.endCountdown.value)
        assertTrue(waits.isEmpty())
        mode = "off"
        current = channel(5, "x")
        val off = cards()
        off.offerNext()
        assertEquals(2, events.size)
        assertNull(off.endSuggestion.value)
    }

    @Test
    fun `no followed channel or a pending raid means no suggestion`() {
        followed = null
        val c = cards()
        c.offerNext()
        assertNull(c.endSuggestion.value)
        followed = listOf(channel(8, "other"))
        val raided = cards()
        raided.onRaid(RaidNotification("chan", "Target", "42"))
        raided.offerNext()
        assertNull(raided.endSuggestion.value)
    }

    @Test
    fun `Back on the card cancels the countdown`() {
        val c = cards()
        c.offerNext()
        c.cancelEndSuggestion()
        pass(PlayerCardsController.END_COUNTDOWN_SECONDS)
        assertNull(c.endSuggestion.value)
        assertEquals(0, c.endCountdown.value)
        assertTrue(plays.isEmpty())
    }

    @Test
    fun `a recording that ended offers the next one and plays it from the preload`() {
        val vod = recording(100L)
        current = vod
        next = recording(101L)
        val c = cards()
        c.offerNext(ended = true)
        assertSame(next, c.endSuggestion.value)
        assertSame(next, slot.movie)
        assertTrue(events.isEmpty())
        c.playEndSuggestion()
        val (movie, player) = plays.single()
        assertSame(next, movie)
        assertSame(preloadPlayer.player, player)
        assertEquals(1.0f, preloadPlayer.volume)
        assertTrue(preloadPlayer.playWhenReady)
        assertFalse(preloadPlayer.released)
        assertNull(slot.movie)
    }

    @Test
    fun `cancelling the next recording releases its preload, a failed recording offers nothing`() {
        current = recording(100L)
        next = recording(101L)
        val c = cards()
        c.offerNext(ended = true)
        c.cancelEndSuggestion()
        assertTrue(preloadPlayer.released)
        assertNull(slot.movie)

        val failed = cards()
        failed.offerNext(ended = false)
        assertNull(failed.endSuggestion.value)
        val off = cards(vodNext = false)
        off.offerNext(ended = true)
        assertNull(off.endSuggestion.value)
    }

    @Test
    fun `a clip goes on with the next clip, the recording of a live stream back to live`() {
        val clip = Movie().apply { setClipSlug("a") }
        current = clip
        val c = cards()
        c.offerNext(ended = true)
        assertSame(clip, clipsPlayed.single())
        current = Movie().apply { archiveOfLive = true; setVideoIdFromString("5") }
        c.offerNext(ended = true)
        assertEquals(1, archiveExits)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a notice is trimmed, keeps only web pictures and goes away after its time`() {
        val c = cards()
        c.showNotice("t".repeat(100), "x".repeat(300), "file:///sdcard/a.png", 99)
        val n = c.notice.value!!
        assertEquals(80, n.title.length)
        assertEquals(200, n.text!!.length)
        assertNull(n.imageUrl)
        assertEquals(60_000L, waits.single())
        pass()
        assertNull(c.notice.value)
        c.showNotice("Door", null, "https://example.invalid/cam.jpg", 1)
        assertEquals("https://example.invalid/cam.jpg", c.notice.value?.imageUrl)
        assertEquals(2_000L, waits.last())
        c.dismissNotice()
        assertNull(c.notice.value)
    }
}
