package com.shrug.shrugtv.ui.tv.player

import com.shrug.shrugtv.chat.ChatMessage
import com.shrug.shrugtv.chat.VodChatPacer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The pace of the chat of a recording, split from PlayerViewModel.
 *
 * The client loads comment pages by offset on its own thread; the messages arrive at once, so the pacer holds them and
 * this controller hands them to [chat] once a second as the playback position passes their offset. Main thread only.
 */
class VodChatController(
    private val scope: CoroutineScope,
    private val chat: ChatViewModel,
    /** Playback position of the player in ms. */
    private val positionMs: () -> Long,
    /** The panel is shown; while it is hidden nothing is handed out or fetched. */
    private val chatVisible: () -> Boolean,
    now: () -> Long
) {
    private val pacer = VodChatPacer(now)
    private var job: Job? = null
    private var requestPage: ((Long) -> Unit)? = null
    private var stopClient: (() -> Unit)? = null

    /** The recording whose chat goes along: the video itself, or the one a clip was cut from; 0 = none. */
    var videoId = 0L
        private set

    /** Second of the recording at which the player position 0 lies (a clip starts somewhere in the middle of it). */
    var base = 0.0
        private set

    val isRunning: Boolean get() = requestPage != null

    /** True if the chat of [videoId] from [base] on is running already (a quality change or handover restarts nothing). */
    fun runsFor(videoId: Long, base: Double): Boolean = isRunning && this.videoId == videoId && this.base == base

    /** Starts the pace for a client that loads pages with [requestPage] and is closed with [stopClient]. */
    fun start(videoId: Long, base: Double, requestPage: (Long) -> Unit, stopClient: () -> Unit) {
        stop()
        this.videoId = videoId
        this.base = base
        this.requestPage = requestPage
        this.stopClient = stopClient
        job = scope.launch {
            while (true) {
                delay(1000)
                tick()
            }
        }
    }

    /** A message of a loaded page, already moved to the main thread. */
    fun onMessage(message: ChatMessage) = pacer.onMessage(message)

    /** Once a second: follows seeks, releases what is due, fetches ahead. */
    fun tick() {
        if (!chatVisible()) return
        val step = pacer.tick(base + positionMs() / 1000.0)
        if (step.reset) chat.clear()
        step.due.forEach { chat.addMessage(it) }
        step.fetchOffset?.let { offset -> requestPage?.invoke(offset) }
    }

    fun stop() {
        job?.cancel()
        job = null
        stopClient?.invoke()
        stopClient = null
        requestPage = null
        videoId = 0L
        base = 0.0
        pacer.clear()
    }
}
