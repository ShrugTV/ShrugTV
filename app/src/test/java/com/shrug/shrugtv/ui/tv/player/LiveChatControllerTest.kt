package com.shrug.shrugtv.ui.tv.player

import com.shrug.shrugtv.chat.ChatDelay
import com.shrug.shrugtv.chat.ChatMessage
import com.shrug.shrugtv.chat.ChatUiEvent
import com.shrug.shrugtv.chat.ChatUiSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live chat pipe as a controller of its own: delay, order, dropped events. */
class LiveChatControllerTest {

    private class Client(val sink: ChatUiSink) { var stopped = false }

    private var now = 1_000L
    private val posted = ArrayList<() -> Unit>()
    private val delayed = ArrayList<Pair<() -> Unit, Long>>()
    private val delivered = ArrayList<ChatUiEvent>()
    private val chat = LiveChatController<Client>(
        post = { posted.add(it) },
        postDelayed = { r, ms -> delayed.add(r to ms) },
        clock = { now },
        deliver = { delivered.add(it) },
        stopClient = { it.stopped = true }
    )

    private fun runPosted() { val p = ArrayList(posted); posted.clear(); p.forEach { it() } }
    private fun runDelayed() { val d = ArrayList(delayed); delayed.clear(); d.forEach { it.first() } }
    private fun fixed(ms: Long) = ChatDelay(false, ms) { null }
    private fun message(historical: Boolean) = ChatUiEvent.Message(ChatMessage.Builder().text("hi").historical(historical).build())

    @Test
    fun `without a delay events arrive on the main thread at once`() {
        chat.start(fixed(0L)) { Client(it) }
        chat.client!!.sink.onEvent(ChatUiEvent.ClearAll)
        assertTrue(delivered.isEmpty())
        runPosted()
        assertEquals(listOf<ChatUiEvent>(ChatUiEvent.ClearAll), delivered)
        assertTrue(delayed.isEmpty())
    }

    @Test
    fun `messages wait for the delay, history does not`() {
        chat.start(fixed(3_000L)) { Client(it) }
        val live = message(historical = false)
        val old = message(historical = true)
        chat.client!!.sink.onEvent(live)
        chat.client!!.sink.onEvent(old)
        runPosted()
        assertEquals(listOf<ChatUiEvent>(old), delivered)
        assertEquals(3_000L, delayed.single().second)
        runDelayed()
        assertEquals(listOf<ChatUiEvent>(old, live), delivered)
        assertEquals(3_000L, chat.delay?.currentMs())
    }

    @Test
    fun `events of the previous channel that still wait are dropped`() {
        chat.start(fixed(3_000L)) { Client(it) }
        val first = chat.client!!
        first.sink.onEvent(ChatUiEvent.Delete("x"))
        runPosted()
        chat.start(fixed(3_000L)) { Client(it) }
        assertTrue(first.stopped)
        runDelayed()
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `stop ends the client and drops what waits`() {
        chat.start(fixed(3_000L)) { Client(it) }
        val c = chat.client!!
        c.sink.onEvent(ChatUiEvent.ClearUser("u"))
        runPosted()
        chat.stop()
        runDelayed()
        assertTrue(c.stopped)
        assertNull(chat.client)
        assertNull(chat.delay)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `the running client is the one connect returned`() {
        var made: Client? = null
        chat.start(fixed(0L)) { Client(it).also { c -> made = c } }
        assertSame(made, chat.client)
    }
}
