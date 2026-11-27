package com.shrug.shrugtv.ui.tv.player

import com.shrug.shrugtv.chat.ChatDelay
import com.shrug.shrugtv.chat.ChatUiEvent
import com.shrug.shrugtv.chat.ChatUiSink

/**
 * The live chat of the player: one client at a time, its events moved to the main thread and held back towards the
 * picture; split from PlayerViewModel. Every start and stop bumps a
 * generation, so events of the previous channel that still wait for their delay are dropped.
 *
 * [post] and [postDelayed] run on the main thread (where the player may be read), [clock] is its uptime in ms,
 * [deliver] gets the events in order, [stopClient] ends a client.
 */
class LiveChatController<C : Any>(
    private val post: (() -> Unit) -> Unit,
    private val postDelayed: (() -> Unit, Long) -> Unit,
    private val clock: () -> Long,
    private val deliver: (ChatUiEvent) -> Unit,
    private val stopClient: (C) -> Unit
) {
    private var generation = 0

    /** The running client, null without live chat. */
    var client: C? = null
        private set

    /** Delay of the running live chat. */
    var delay: ChatDelay? = null
        private set

    /** Ends the client; events still on their way are dropped. */
    fun stop() {
        client?.let(stopClient)
        client = null
        generation++
        delay = null
    }

    /**
     * Starts a client through [connect] with a sink that applies [chatDelay]: history comes at once, everything else
     * waits for the delay of the moment. Ends a running client first.
     */
    fun start(chatDelay: ChatDelay, connect: (ChatUiSink) -> C) {
        stop()
        val gen = ++generation
        delay = chatDelay
        val sink = ChatUiSink { event ->
            post {
                val historical = event is ChatUiEvent.Message && event.message.isHistorical
                val ms = if (historical) 0L else chatDelay.delayFor(clock())
                if (ms <= 0L) {
                    deliver(event)
                } else {
                    postDelayed({ if (gen == generation) deliver(event) }, ms)
                }
            }
        }
        // The client starts itself on its own thread (ChatClient.init); calling startChat() as well ran it twice (C0.2)
        client = connect(sink)
    }
}
