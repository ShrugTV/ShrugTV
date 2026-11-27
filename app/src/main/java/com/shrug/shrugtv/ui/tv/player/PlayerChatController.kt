package com.shrug.shrugtv.ui.tv.player

import com.shrug.shrugtv.chat.ChatAppearance
import com.shrug.shrugtv.chat.ChatDelay
import com.shrug.shrugtv.chat.ChatLayout
import com.shrug.shrugtv.chat.ChatPrefs
import com.shrug.shrugtv.chat.ChatUiEvent
import com.shrug.shrugtv.chat.ChatUiSink
import com.shrug.shrugtv.chat.ClipSource
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.RaidNotification
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A chat client of a recording: loads comment pages by offset with [requestPage], closed with [stop]. */
class VodChatConnection(val requestPage: (Long) -> Unit, val stop: () -> Unit)

/** The owner of a recording opened by link; [login] and [displayName] are read only when the recording lacks them. */
class VodOwner(val userId: Int, val login: () -> String?, val displayName: () -> String?)

/** What the chat of the player needs from outside: the clients and two lookups (blocking, called off the main thread). */
interface PlayerChatConnector<C : Any> {
    /** Starts the live chat client of [movie] that delivers to [sink]. */
    fun live(movie: Movie, sink: ChatUiSink): C

    /** Ends a live chat client. */
    fun stopLive(client: C)

    /** Starts the chat client of the recording [videoId] (the one [movie] plays or was cut from). */
    fun vod(movie: Movie, videoId: Long, sink: ChatUiSink): VodChatConnection

    /** The recording a clip was cut from, null when it is gone. */
    fun clipSource(slug: String): ClipSource.Info?

    /** The owner of the recording [videoId], null when unknown; may throw. */
    fun vodOwner(videoId: Long): VodOwner?
}

/**
 * The chat of the player, split from PlayerViewModel: which chat goes with the item shown (live,
 * recording, the recording a clip was cut from), its visibility and its look from "Adjust chat"
 * (CA12). The live pipe is [LiveChatController], the pace of a recording's chat [VodChatController].
 * Main thread only, except the lookups that run on [io].
 *
 * [chat] holds the lines, [prefs] the chat settings, [saveAppearance] stores a changed look, [current] is the item shown,
 * [positionMs] and [liveOffsetMs] read the player, [onRaid] gets a raid of the live chat, [onClipSource] fills a clip
 * with what its source tells (channel, title) before the chat starts, [onError] records a failure, [post] and
 * [postDelayed] run on the main thread, [uptime] is its clock.
 */
class PlayerChatController<C : Any>(
    private val scope: CoroutineScope,
    private val chat: ChatViewModel,
    private val prefs: ChatPrefs,
    initialAppearance: ChatAppearance,
    private val saveAppearance: (ChatAppearance) -> Unit,
    private val connector: PlayerChatConnector<C>,
    private val isVod: (Movie) -> Boolean,
    private val current: () -> Movie?,
    positionMs: () -> Long,
    private val liveOffsetMs: () -> Long,
    private val onRaid: (RaidNotification) -> Unit,
    private val onClipSource: (Movie, ClipSource.Info) -> Unit,
    private val onError: (Exception) -> Unit,
    private val post: (() -> Unit) -> Unit,
    postDelayed: (() -> Unit, Long) -> Unit,
    uptime: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main
) {
    /** The live chat client with its delay towards the picture. */
    private val liveChat = LiveChatController<C>(
        post = post,
        postDelayed = postDelayed,
        clock = uptime,
        deliver = ::deliver,
        stopClient = connector::stopLive
    )

    private val _visible = MutableStateFlow(prefs.joinChat && prefs.showChat)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    // The pace lives in VodChatController
    private val vodChat = VodChatController(
        scope, chat,
        positionMs = positionMs,
        chatVisible = { _visible.value },
        now = uptime
    )

    /** Layout, width, position, opacity of the chat; "Adjust chat" changes it live (CA12). */
    private val _appearance = MutableStateFlow(initialAppearance)
    val appearance: StateFlow<ChatAppearance> = _appearance.asStateFlow()

    /** Text or emote size changed in "Adjust chat": the chat restarts when the window closes. */
    private var sizesChanged = false

    /** The clip on screen has no chat to show (its recording is gone, or recorded chat is off): the panel stays away. */
    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    /** The recording a clip was cut from is known and still there: the button "Recording" works. */
    private val _clipSourceReady = MutableStateFlow(false)
    val clipSourceReady: StateFlow<Boolean> = _clipSourceReady.asStateFlow()

    /** The running live chat client, null without live chat. */
    val liveClient: C? get() = liveChat.client

    /** Delay of the running live chat. */
    val delay: ChatDelay? get() = liveChat.delay

    fun updateAppearance(change: (ChatAppearance) -> ChatAppearance) {
        val old = _appearance.value
        val updated = change(old)
        if (updated.sizesDiffer(old)) sizesChanged = true
        _appearance.value = updated
        saveAppearance(updated)
    }

    /**
     * Shortcut button of the control bar: next to the video <-> over the video. "Transparent" (only
     * reachable through the settings) goes to next to the video. Saved like "Adjust chat".
     */
    fun toggleLayout() {
        updateAppearance {
            it.copy(layout = if (it.layout == ChatLayout.SIDE_BY_SIDE) ChatLayout.OVERLAY else ChatLayout.SIDE_BY_SIDE)
        }
    }

    /** "Adjust chat" closes: emotes and badges are drawn at their size when a message arrives, so a size change renders the chat anew. */
    fun onEditorClosed() {
        if (!sizesChanged) return
        sizesChanged = false
        current()?.let { movie ->
            vodChat.stop()
            start(movie)
        }
    }

    fun toggleVisible() {
        _visible.value = !_visible.value
    }

    /** Home Assistant or the phone: shown only when the chat is joined at all. */
    fun setVisible(visible: Boolean) {
        _visible.value = visible && prefs.joinChat
    }

    /** "Adjust chat" from the More menu: the chat must be visible to see the changes. */
    fun showForEditor() {
        if (prefs.joinChat) _visible.value = true
    }

    /** The recording whose chat goes along: the video itself, or the one a clip was cut from. */
    private fun chatVideoOf(movie: Movie): Long = if (movie.isClip) movie.clipVideoId else movie.videoId

    /** Second of the recording at which the player position 0 lies (a clip starts somewhere in the middle of it). */
    private fun chatBaseOf(movie: Movie): Double = if (movie.isClip) movie.clipOffsetSeconds.toDouble() else 0.0

    /** Starts the chat that goes with [movie]. */
    fun start(movie: Movie) {
        // play() restarts the chat for the same recording after a quality or handover change: keep it running
        // The same recording but another zero point (a clip, then its recording) needs a fresh start: the offsets differ
        if (isVod(movie) && vodChat.runsFor(chatVideoOf(movie), chatBaseOf(movie))) return
        liveChat.stop()
        vodChat.stop()
        chat.clear()
        _unavailable.value = false
        _clipSourceReady.value = false
        if (movie.isClip) {
            if (movie.clipVideoId > 0L) {
                _clipSourceReady.value = true
                if (!prefs.joinChat || !prefs.vodChat) _unavailable.value = true else startVod(movie)
            } else {
                startClip(movie)
            }
            return
        }
        if (isVod(movie)) {
            startVod(movie)
            return
        }
        if (!prefs.joinChat || movie.userId <= 0) return
        // Typed events from the client; it holds the sink strongly (the old weakly held Handler was once
        // garbage collected and the chat stayed empty)
        // Delay towards the picture: computed on the main thread, where the player
        // may be read; events of a previous channel that are still pending are dropped
        val delay = ChatDelay(prefs.autoDelay, prefs.messageDelayMs.toLong()) {
            ChatDelay.sane(liveOffsetMs())
        }
        // The delay is applied in the sink, for messages and moderation events alike
        liveChat.start(delay) { sink -> connector.live(movie, sink) }
    }

    /** A clip has no chat of its own: look up its recording and play that chat from where the clip starts. */
    private fun startClip(movie: Movie) {
        scope.launch(io) {
            val info = connector.clipSource(movie.clipSlug)
            withContext(main) {
                if (current() !== movie) return@withContext
                // The button to the recording needs the source even when the chat is off
                _clipSourceReady.value = info != null
                if (!prefs.joinChat || !prefs.vodChat || info == null) { _unavailable.value = true }
                if (info == null) return@withContext
                onClipSource(movie, info)
                startVod(movie)
            }
        }
    }

    private fun startVod(movie: Movie) {
        if (!prefs.joinChat || !prefs.vodChat || chatVideoOf(movie) <= 0L || vodChat.isRunning) return
        if (movie.userId <= 0) {
            // Opened by link (shrugtv://vod/<id>, Home Assistant): only the video id is known
            resolveVodOwner(movie)
            return
        }
        // The client holds the sink strongly; events come in on its threads and are moved to the main thread
        val sink = ChatUiSink { event ->
            if (event is ChatUiEvent.Message) post { vodChat.onMessage(event.message) }
        }
        val client = connector.vod(movie, chatVideoOf(movie), sink)
        vodChat.start(chatVideoOf(movie), chatBaseOf(movie), client.requestPage, client.stop)
    }

    private fun resolveVodOwner(movie: Movie) {
        scope.launch(io) {
            try {
                val owner = connector.vodOwner(movie.videoId) ?: return@launch
                withContext(main) {
                    if (current() === movie && movie.userId <= 0) {
                        movie.userId = owner.userId
                        if (movie.login.isNullOrEmpty()) owner.login()?.let { movie.login = it }
                        if (movie.displayName.isNullOrEmpty()) owner.displayName()?.let { movie.displayName = it }
                        startVod(movie)
                    }
                }
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    /** Ends the live chat; events still on their way are dropped. */
    fun stopLive() = liveChat.stop()

    /** Ends the chat of a recording. */
    fun stopVod() = vodChat.stop()

    private fun deliver(event: ChatUiEvent) {
        when (event) {
            is ChatUiEvent.Message -> chat.addMessage(event.message)
            is ChatUiEvent.Raid -> onRaid(event.raid)
            is ChatUiEvent.Delete -> chat.deleteMessage(event.messageId)
            is ChatUiEvent.ClearUser -> chat.deleteUserMessages(event.userLogin)
            ChatUiEvent.ClearAll -> chat.deleteAll()
            is ChatUiEvent.RoomSettings -> chat.setRoomSettings(event.settings)
        }
    }
}
