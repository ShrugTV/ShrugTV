package com.shrug.shrugtv.ui.tv.player

import android.util.Log
import androidx.media3.common.Player
import com.shrug.shrugtv.hub.HubEvent
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.RaidNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A notice sent by Home Assistant or the phone (e.g. doorbell), shown as a card. */
data class ExternalNotice(val title: String, val text: String?, val imageUrl: String?)

/**
 * The cards over the picture, split from PlayerViewModel: the raid offer (docs/ideas.md 2), the
 * "X is live" card, the suggestion with countdown at the end of a live stream or a recording
 * (docs/ideas.md 3) and notices from Home Assistant or the phone. Main thread only.
 *
 * [current] is the item shown, [endOfStreamMode] the setting countdown | suggest | off, [vodNextEnabled] the setting
 * "Next recording at the end", [raidCardEnabled] the setting "Show incoming raid notification", [emit] sends events to
 * the hub, [followedLive] lists the followed live channels (null on failure), [nextRecording] finds the recording after
 * the one that ended, [preloadRecording] loads it into [slot] meanwhile, [playNextClip] starts the next clip of the queue
 * (returning the job of its pause), [exitArchive] leaves the recording of a live stream, [play] starts an item, with the
 * player taken over from the preload or null, [elapsed] is the clock of the live card, [wait] pauses a card's timer.
 */
class PlayerCardsController<P : Player>(
    private val scope: CoroutineScope,
    private val slot: PreloadSlot<P>,
    private val current: () -> Movie?,
    private val isVod: (Movie) -> Boolean,
    private val endOfStreamMode: String,
    private val vodNextEnabled: Boolean,
    private val raidCardEnabled: () -> Boolean,
    private val emit: (HubEvent) -> Unit,
    private val followedLive: suspend () -> List<Movie>?,
    private val nextRecording: suspend (Movie) -> Movie?,
    private val preloadRecording: suspend (Movie) -> Unit,
    private val playNextClip: (Movie) -> Job?,
    private val exitArchive: () -> Unit,
    private val play: (Movie, P?) -> Unit,
    private val elapsed: () -> Long,
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    /** A raid from the current channel: the player offers to follow it for [RAID_OFFER_SECONDS]. */
    private val _pendingRaid = MutableStateFlow<RaidNotification?>(null)
    val pendingRaid: StateFlow<RaidNotification?> = _pendingRaid.asStateFlow()
    private var raidJob: Job? = null

    /** Next followed live channel (or next recording) offered when a stream ends, with seconds left to switch. */
    private val _endSuggestion = MutableStateFlow<Movie?>(null)
    val endSuggestion: StateFlow<Movie?> = _endSuggestion.asStateFlow()
    private val _endCountdown = MutableStateFlow(0)
    val endCountdown: StateFlow<Int> = _endCountdown.asStateFlow()
    private var endJob: Job? = null

    // "X is live" card
    private val _liveCard = MutableStateFlow<Movie?>(null)
    val liveCard: StateFlow<Movie?> = _liveCard.asStateFlow()
    private var liveCardJob: Job? = null
    private var liveCardShownAt = 0L

    private val _notice = MutableStateFlow<ExternalNotice?>(null)
    val notice: StateFlow<ExternalNotice?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    fun onRaid(raid: RaidNotification) {
        if (raid.targetUserId.isNullOrEmpty()) return
        emit(HubEvent(HubEvent.RAID, mapOf(
            "from" to current()?.login, "to" to raid.targetDisplayName, "to_id" to raid.targetUserId
        )))
        // Setting "Show incoming raid notification" (was never read before)
        if (!raidCardEnabled()) return
        _pendingRaid.value = raid
        raidJob?.cancel()
        raidJob = scope.launch {
            wait(RAID_OFFER_SECONDS * 1000L)
            _pendingRaid.value = null
        }
    }

    /** OK on the raid card: go to the raided channel (login is looked up from the id). */
    fun followRaid() {
        val raid = _pendingRaid.value ?: return
        dismissRaid()
        val target = Movie().apply {
            userId = raid.targetUserId?.toIntOrNull() ?: 0
            displayName = raid.targetDisplayName ?: ""
            itemType = Enums.ContentType.STREAM
        }
        if (target.userId > 0) play(target, null)
    }

    fun dismissRaid() {
        raidJob?.cancel()
        _pendingRaid.value = null
    }

    fun onFollowedLive(movie: Movie) {
        val now = elapsed()
        val busy = _pendingRaid.value != null || _endSuggestion.value != null || _liveCard.value != null
        if (!LiveCardGate.shouldShow(now, liveCardShownAt, movie.userId, current()?.userId ?: 0, busy)) return
        Log.i(TAG, "Live card: ${movie.login} went live")
        liveCardShownAt = now
        _liveCard.value = movie
        liveCardJob?.cancel()
        liveCardJob = scope.launch {
            wait(LiveCardGate.SHOW_SECONDS * 1000L)
            _liveCard.value = null
        }
    }

    /** OK on the live card: switch to that channel. */
    fun switchToLiveCard() {
        val movie = _liveCard.value ?: return
        dismissLiveCard()
        play(movie, null)
    }

    fun dismissLiveCard() {
        liveCardJob?.cancel()
        _liveCard.value = null
    }

    /**
     * A live stream ended or stays offline: offer the next followed live channel (favourites first)
     * and switch after a countdown. A raid offer wins. A clip goes on with the next clip, the recording of a live
     * stream back to live, a recording that played to its end ([ended]) with the next recording of the channel.
     */
    fun offerNext(ended: Boolean = false) {
        current()?.takeIf { it.isClip }?.let { nextClip(it); return }
        if (current()?.archiveOfLive == true) {
            exitArchive()
            return
        }
        val movie = current() ?: return
        // A recording that played to its end (not one that failed): the next recording of the channel
        if (ended && isVod(movie)) {
            offerNextRecording(movie)
            return
        }
        if (!isVod(movie) && endJob?.isActive != true) {
            emit(HubEvent(HubEvent.STREAM_ENDED, mapOf("channel" to movie.login)))
        }
        if (endOfStreamMode == "off") return
        if (isVod(movie) || _pendingRaid.value != null || endJob?.isActive == true) return
        endJob = scope.launch {
            val next = followedLive()
                ?.filter { it.userId != movie.userId }
                ?.sortedByDescending { it.isFavorite }
                ?.firstOrNull() ?: return@launch
            if (current() !== movie) return@launch
            _endSuggestion.value = next
            if (endOfStreamMode == "suggest") return@launch
            countdown()
            if (_endSuggestion.value === next && current() === movie) playEndSuggestion()
        }
    }

    /**
     * A clip ended: the next clip of the list it was opened from starts after a short pause, unless the setting is off. Clips
     * opened before are left out by default; every clip that starts counts as opened.
     */
    private fun nextClip(clip: Movie) {
        if (endJob?.isActive == true) return
        playNextClip(clip)?.let { endJob = it }
    }

    /**
     * A recording played to its end: offer the next recording of the channel ([com.shrug.shrugtv.player.VodNext]) with the
     * same card and countdown as at the end of a live stream, and load its first seconds in a second player meanwhile, so
     * the change is almost instant. Setting "Next recording at the end" (pref_player_vod_next, default on).
     */
    private fun offerNextRecording(movie: Movie) {
        if (!vodNextEnabled || endJob?.isActive == true) return
        endJob = scope.launch {
            val next = nextRecording(movie) ?: return@launch
            if (current() !== movie) return@launch
            Log.i(TAG, "Recording ended, next one: video ${next.videoId}")
            _endSuggestion.value = next
            // Child of the countdown: Back on the card cancels both
            launch { preloadRecording(next) }
            countdown()
            if (_endSuggestion.value === next && current() === movie) playEndSuggestion()
        }
    }

    private suspend fun countdown() {
        for (s in END_COUNTDOWN_SECONDS downTo 1) {
            _endCountdown.value = s
            wait(1_000L)
        }
    }

    /** OK on the "stream ended" card with a suggestion, or the countdown ran out. */
    fun playEndSuggestion() {
        val next = _endSuggestion.value ?: return
        // A recording that was preloaded during the countdown is taken over
        val ready = slot.take { it === next }?.second
        cancelEndSuggestion()
        if (ready == null) {
            play(next, null)
            return
        }
        Log.i(TAG, "Next recording taken over from the preload, ${ready.bufferedPosition} ms already loaded")
        ready.volume = 1.0f
        ready.playWhenReady = true
        play(next, ready)
    }

    /** Back on the card: stay; the card falls back to "try again". */
    fun cancelEndSuggestion() {
        endJob?.cancel()
        endJob = null
        // A recording preloaded for the card is not needed any more; a preloaded clip stays
        val suggested = _endSuggestion.value
        if (suggested != null && slot.movie === suggested) slot.release()
        _endSuggestion.value = null
        _endCountdown.value = 0
    }

    /** A notice from Home Assistant or the phone, shown for [seconds] (2-60). */
    fun showNotice(title: String, text: String?, imageUrl: String?, seconds: Int) {
        _notice.value = ExternalNotice(title.take(80), text?.take(200), imageUrl?.takeIf { it.startsWith("http") })
        noticeJob?.cancel()
        noticeJob = scope.launch {
            wait(seconds.coerceIn(2, 60) * 1000L)
            _notice.value = null
        }
    }

    fun dismissNotice() {
        noticeJob?.cancel()
        _notice.value = null
    }

    companion object {
        private const val TAG = "PlayerViewModel"

        /** How long the raid card waits for OK. */
        const val RAID_OFFER_SECONDS = 15

        /** Countdown before switching to the suggested channel when a stream ended. */
        const val END_COUNTDOWN_SECONDS = 15
    }
}
