package com.shrug.shrugtv.ui.tv.player

import com.shrug.shrugtv.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pace of the chat of a recording, as a controller of its own. */
class VodChatControllerTest {

    private val scope = CoroutineScope(Job())
    private val chat = ChatViewModel()
    private var positionMs = 0L
    private var visible = true
    private var clock = 0L
    private val requested = ArrayList<Long>()
    private var stopped = 0
    private val vod = VodChatController(scope, chat, { positionMs }, { visible }, { clock })

    @After
    fun tearDown() = scope.cancel()

    private fun msg(offset: Int): ChatMessage =
        ChatMessage.Builder().text("m$offset").messageId("id$offset").vodOffsetSeconds(offset.toDouble()).build()

    private fun start(videoId: Long = 7L, base: Double = 0.0) =
        vod.start(videoId, base, { requested.add(it) }, { stopped++ })

    @Test
    fun `messages reach the chat when the position passes them`() {
        start()
        positionMs = 100_000L
        vod.tick()
        assertEquals(listOf(100L), requested)
        vod.onMessage(msg(101)); vod.onMessage(msg(103))
        positionMs = 102_000L
        vod.tick()
        assertEquals(listOf("m101"), chat.lines.value.map { it.message.text })
    }

    @Test
    fun `a clip counts from its place in the recording`() {
        start(base = 500.0)
        positionMs = 3_000L
        vod.tick()
        assertEquals(listOf(503L), requested)
    }

    @Test
    fun `nothing happens while the chat is hidden`() {
        start()
        visible = false
        positionMs = 100_000L
        vod.tick()
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `the same recording and zero point counts as running`() {
        assertFalse(vod.isRunning)
        start(videoId = 7L, base = 0.0)
        assertTrue(vod.runsFor(7L, 0.0))
        assertFalse(vod.runsFor(7L, 500.0))
        assertFalse(vod.runsFor(8L, 0.0))
    }

    @Test
    fun `stop closes the client and forgets the recording`() {
        start()
        vod.stop()
        assertEquals(1, stopped)
        assertFalse(vod.isRunning)
        assertEquals(0L, vod.videoId)
        vod.stop()
        assertEquals(1, stopped)
    }

    @Test
    fun `a new start closes the previous client`() {
        start(videoId = 7L)
        start(videoId = 8L)
        assertEquals(1, stopped)
        assertEquals(8L, vod.videoId)
    }
}
