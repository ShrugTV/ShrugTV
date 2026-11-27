package com.shrug.shrugtv.ui.tv.player

import androidx.media3.common.Player
import com.shrug.shrugtv.model.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Multiview as a controller of its own: who is heard, what is released. */
class MultiviewControllerTest {

    /** A player that only knows its volume and whether it was released. */
    private class FakePlayer {
        var volume = 1f
        var released = false
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "getVolume" -> volume
                "setVolume" -> { volume = args[0] as Float; null }
                "release" -> { released = true; null }
                else -> defaultOf(m.returnType)
            }
        } as Player

        private fun defaultOf(t: Class<*>): Any? = when (t) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    }

    private val main = FakePlayer()
    private val seconds = ArrayList<FakePlayer>()
    private val pending = ArrayList<(String) -> Unit>()
    private val mv = MultiviewController(
        mainPlayer = { main.player },
        createPlayer = { FakePlayer().also { seconds.add(it) }.player },
        resolveUrl = { _, done -> pending.add(done) }
    )

    @Test
    fun `the second stream starts muted and the main one stays heard`() {
        val second = Movie()
        mv.start(second)
        assertTrue(mv.active.value)
        assertSame(second, mv.movie.value)
        assertEquals(0f, seconds.single().volume)
        assertEquals(1f, main.volume)
        assertTrue(mv.primaryAudio.value)
    }

    @Test
    fun `swapping moves the sound between the two`() {
        mv.start(Movie())
        mv.swapAudio()
        assertFalse(mv.primaryAudio.value)
        assertEquals(0f, main.volume)
        assertEquals(1f, seconds.single().volume)
        mv.swapAudio()
        assertEquals(1f, main.volume)
        assertEquals(0f, seconds.single().volume)
    }

    @Test
    fun `stop releases the second player and gives the sound back`() {
        mv.start(Movie())
        mv.swapAudio()
        mv.stop()
        assertFalse(mv.active.value)
        assertNull(mv.movie.value)
        assertNull(mv.player)
        assertTrue(seconds.single().released)
        assertEquals(1f, main.volume)
        assertTrue(mv.primaryAudio.value)
        // The address arrives after the end: nothing is loaded
        pending.single().invoke("https://example.invalid/x.m3u8")
        assertNull(mv.player)
    }

    @Test
    fun `a second start keeps the player`() {
        mv.start(Movie())
        mv.start(Movie())
        assertEquals(1, seconds.size)
    }
}
