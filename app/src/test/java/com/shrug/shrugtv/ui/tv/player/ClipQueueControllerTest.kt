package com.shrug.shrugtv.ui.tv.player

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.StreamQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** The clip queue and the preload slot as controllers of their own. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ClipQueueControllerTest {

    private class FakePlayer {
        var volume = 1f
        var playWhenReady = true
        var item: MediaItem? = null
        var released = false
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "getVolume" -> volume
                "setVolume" -> { volume = args[0] as Float; null }
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { playWhenReady = args[0] as Boolean; null }
                "setMediaItem" -> { item = args[0] as MediaItem; null }
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

    private fun clip(slug: String) = Movie().apply { setClipSlug(slug) }

    private val a = clip("a")
    private val b = clip("b")
    private val queue = listOf(a, b)
    private var current: Movie? = a
    private val created = ArrayList<FakePlayer>()
    private val plays = ArrayList<Pair<Movie, Player?>>()
    private val seen = ArrayList<String?>()
    private var qualities: List<StreamQuality>? = listOf(StreamQuality("1080p60", "https://example.invalid/b.mp4"))
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val slot = PreloadSlot<Player>()
    private val clips = ClipQueueController(
        scope = scope,
        slot = slot,
        current = { current },
        nextOf = { c -> queue.getOrNull(queue.indexOf(c) + 1) },
        neighbor = { c, step -> queue.getOrNull(queue.indexOf(c) + step) },
        markSeen = { seen.add(it.clipSlug) },
        resolve = { qualities },
        createPlayer = { FakePlayer().also { created.add(it) }.player },
        play = { m, p -> plays.add(m to p); current = m },
        preloadDelayMs = 0L,
        fallbackDelayMs = 0L
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `the next clip is loaded ahead muted and paused`() {
        clips.preloadNext(a)
        assertSame(b, slot.movie)
        val p = created.single()
        assertEquals(0f, p.volume)
        assertFalse(p.playWhenReady)
        assertEquals("https://example.invalid/b.mp4", p.item?.localConfiguration?.uri.toString())
        assertEquals(qualities, b.qualities)
    }

    @Test
    fun `at the end the preloaded clip is taken over without a pause`() {
        clips.preloadNext(a)
        assertNull(clips.playNext(a))
        val (movie, player) = plays.single()
        assertSame(b, movie)
        assertSame(created.single().player, player)
        assertEquals(1f, created.single().volume)
        assertTrue(created.single().playWhenReady)
        assertFalse(created.single().released)
        assertNull(slot.movie)
        assertEquals(listOf<String?>("b"), seen)
    }

    @Test
    fun `without a preload the next clip starts after the pause in a fresh player`() {
        val job = clips.playNext(a)
        assertNotNull(job)
        val (movie, player) = plays.single()
        assertSame(b, movie)
        assertNull(player)
    }

    @Test
    fun `a preload is dropped when another clip plays meanwhile`() {
        current = b
        clips.preloadNext(a)
        assertNull(slot.movie)
        assertTrue(created.isEmpty())
    }

    @Test
    fun `the end of the list plays nothing`() {
        assertNull(clips.playNext(b))
        assertTrue(plays.isEmpty())
    }

    @Test
    fun `stepping back plays the neighbour and releases nothing it does not hold`() {
        current = b
        clips.step(-1)
        assertSame(a, plays.single().first)
        assertNull(plays.single().second)
    }

    @Test
    fun `release frees the held player`() {
        clips.preloadNext(a)
        slot.release()
        assertTrue(created.single().released)
        assertNull(slot.take { true })
    }
}
