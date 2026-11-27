package com.shrug.shrugtv.ui.tv.player

import androidx.media3.common.Player
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.VodChapter
import com.shrug.shrugtv.player.SeekStepper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Seeking, the seek HUD and instant replay as a controller of their own. */
class SeekControllerTest {

    /** A player at 60 s of 120 s; live: a 32 s window, 5 s behind the edge. */
    private class FakePlayer {
        var position = 60_000L
        var duration = 120_000L
        var liveOffset = 5_000L
        var playing = true
        val seeks = ArrayList<Long>()
        var toLive = 0
        var plays = 0
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "getCurrentPosition" -> position
                "getDuration" -> duration
                "getCurrentLiveOffset" -> liveOffset
                "isPlaying" -> playing
                "seekTo" -> { if (args.size == 1) seeks.add(args[0] as Long); null }
                "seekToDefaultPosition" -> { if (args == null || args.isEmpty()) toLive++; null }
                "play" -> { plays++; null }
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

    private val fake = FakePlayer()
    private val vod = Movie().apply { setVideoIdFromString("100"); login = "chan" }
    private val live = Movie().apply { userId = 7; login = "chan" }
    private var current: Movie? = vod
    private var chapters = listOf(
        VodChapter("Just Chatting", 0L, 0L, ""),
        VodChapter("Chess", 90_000L, 0L, ""),
        VodChapter("Art", 200_000L, 0L, "")
    )
    private var uptime = 0L
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    /** The HUD timer and the replay watch each hang here until the test lets them go; a send without a waiter is lost. */
    private val hudGate = Channel<Unit>()
    private val replayGate = Channel<Unit>()
    private val hudWaits = ArrayList<Long>()

    /** No recording of the live stream: the rewinding stays out of the way. */
    private val rewind = LiveRewindController(
        scope = scope,
        player = { fake.player },
        currentMovie = { current },
        loadedMovie = { current },
        isVod = { it.videoId > 0L },
        play = { _, _ -> },
        onBackToLive = {},
        find = { null },
        onFound = {},
        enabled = { false }
    )

    private val seek = SeekController(
        scope = scope,
        player = { fake.player },
        current = { current },
        isVod = { it.videoId > 0L },
        rewind = rewind,
        chapters = { chapters },
        replayLengthMs = { 30_000L },
        backToLiveText = { "Live again" },
        replayText = { s -> "Replay $s s" },
        uptime = { uptime },
        wait = { ms -> if (ms == 250L) replayGate.receive() else { hudWaits.add(ms); hudGate.receive() } }
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `seekRelative seeks by the delta, not before 0, and shows it for 1_2 s`() {
        seek.seekRelative(10_000L)
        assertEquals(70_000L, fake.seeks.last())
        assertEquals("+10 s", seek.hudText.value)
        assertEquals(1200L, hudWaits.last())
        hudGate.trySend(Unit)
        assertNull(seek.hudText.value)
        seek.seekRelative(-90_000L)
        assertEquals(0L, fake.seeks.last())
        assertEquals("-90 s", seek.hudText.value)
    }

    @Test
    fun `seekStep on a recording uses the growing steps and stops at the end`() {
        val expected = SeekStepper { uptime }
        seek.seekStep(1)
        val step = expected.press(1)
        assertEquals(60_000L + step.stepMs, fake.seeks.last())
        assertEquals(SeekStepper.hudText(step.totalMs, 60_000L + step.stepMs), seek.hudText.value)
        fake.position = 119_000L
        seek.seekStep(1)
        assertEquals(120_000L, fake.seeks.last())
    }

    @Test
    fun `jumpChapter goes to the next and the previous category`() {
        seek.jumpChapter(1)
        assertEquals(90_000L, fake.seeks.last())
        assertEquals("▶ Chess", seek.hudText.value)
        fake.position = 100_000L
        seek.jumpChapter(-1)
        assertEquals(90_000L, fake.seeks.last())
        fake.position = 210_000L
        fake.duration = 300_000L
        val before = fake.seeks.size
        seek.jumpChapter(1)
        assertEquals(before, fake.seeks.size)
    }

    @Test
    fun `showHud shows a note for 2 s`() {
        seek.showHud("Zapping is not possible here")
        assertEquals("Zapping is not possible here", seek.hudText.value)
        assertEquals(2000L, hudWaits.last())
        hudGate.trySend(Unit)
        assertNull(seek.hudText.value)
    }

    @Test
    fun `jumpToLive seeks to the live edge and plays`() {
        current = live
        seek.jumpToLive()
        assertEquals(1, fake.toLive)
        assertEquals(1, fake.plays)
    }

    @Test
    fun `replay jumps back in the live window and returns to live once the part played again`() {
        current = live
        fake.duration = 32_000L
        seek.replay()
        // Window position 27 s, 30 s back: the start of the window
        assertEquals(0L, fake.seeks.single())
        assertEquals("Replay 30 s", seek.hudText.value)
        // 30 s at 250 ms per tick; paused ticks do not count
        fake.playing = false
        repeat(10) { replayGate.trySend(Unit) }
        assertEquals(0, fake.toLive)
        fake.playing = true
        repeat(119) { replayGate.trySend(Unit) }
        assertEquals(0, fake.toLive)
        replayGate.trySend(Unit)
        assertEquals(1, fake.toLive)
        assertEquals(1, fake.plays)
        assertEquals("Live again", seek.hudText.value)
    }

    @Test
    fun `replay does nothing on a recording, seeking or zapping ends it without a jump`() {
        seek.replay()
        assertTrue(fake.seeks.isEmpty())

        current = live
        fake.duration = 32_000L
        seek.replay()
        seek.seekRelative(-10_000L)
        repeat(200) { replayGate.trySend(Unit) }
        assertEquals(0, fake.toLive)

        seek.replay()
        current = Movie().apply { userId = 8; login = "other" }
        repeat(200) { replayGate.trySend(Unit) }
        assertEquals(0, fake.toLive)
    }
}
