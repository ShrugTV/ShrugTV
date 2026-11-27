package com.shrug.shrugtv.ui.tv.player

import android.util.Log
import androidx.media3.common.Player
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.VodChapter
import com.shrug.shrugtv.player.InstantReplay
import com.shrug.shrugtv.player.SeekStepper
import com.shrug.shrugtv.player.VodChapters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Seeking, the seek HUD, instant replay and "Back to live", split from PlayerViewModel
 *. Main thread only.
 *
 * [player] is the main player, [current] the item shown, [rewind] the timeline of a live stream with its recording
 *, [chapters] the categories of a recording, [replayLengthMs] the setting "Replay length",
 * [backToLiveText] and [replayText] the HUD texts, [uptime] the clock of the growing seek steps,
 * [wait] pauses the HUD timer and the replay watch.
 */
class SeekController(
    private val scope: CoroutineScope,
    private val player: () -> Player,
    private val current: () -> Movie?,
    private val isVod: (Movie) -> Boolean,
    private val rewind: LiveRewindController,
    private val chapters: () -> List<VodChapter>,
    private val replayLengthMs: () -> Long,
    private val backToLiveText: () -> String,
    private val replayText: (Int) -> String,
    uptime: () -> Long,
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    private val _hudText = MutableStateFlow<String?>(null)

    /** Short message in the centre of the picture: seek target, replay, "Live again", notes. */
    val hudText: StateFlow<String?> = _hudText.asStateFlow()
    private var hudJob: Job? = null

    private val seekStepper = SeekStepper(uptime)
    private var replayJob: Job? = null

    /** Short message in the centre of the picture (same place as the seek HUD), for 2 s. */
    fun showHud(text: String) = show(text, 2000L)

    private fun showSeekHud(text: String) = show(text, 1200L)

    private fun show(text: String, ms: Long) {
        _hudText.value = text
        hudJob?.cancel()
        hudJob = scope.launch {
            wait(ms)
            _hudText.value = null
        }
    }

    private fun endReplay() {
        replayJob?.cancel()
        replayJob = null
    }

    /** Behind the live edge (pause, stall): back to live. */
    fun jumpToLive() {
        endReplay()
        if (current()?.archiveOfLive == true) {
            rewind.exitArchive()
            return
        }
        val p = player()
        p.seekToDefaultPosition()
        p.play()
    }

    /**
     * Jumps back by the replay length (inside the live window, or into the recording like rewinding)
     * and returns to the live edge once that part has played again. Live, zapping or seeking by hand end it.
     */
    fun replay() {
        val movie = current() ?: return
        if (isVod(movie) || movie.archiveOfLive) return
        val lengthMs = replayLengthMs()
        replayJob?.cancel()
        if (rewind.isActive()) {
            rewind.rewindBy(-lengthMs)
        } else {
            // No recording: as far back as the live window reaches
            val p = player()
            p.seekTo((LiveRewindController.liveWindowPosition(p) - lengthMs).coerceAtLeast(0L))
        }
        Log.i(TAG, "replay: ${lengthMs / 1000} s back (${movie.login})")
        showHud(replayText((lengthMs / 1000).toInt()))
        val login = movie.login
        val r = InstantReplay(lengthMs)
        replayJob = scope.launch {
            while (true) {
                wait(REPLAY_TICK_MS)
                // Zapped to another channel: the replay is over without a jump
                if (current()?.login != login) return@launch
                if (r.tick(REPLAY_TICK_MS, player().isPlaying && !rewind.loading)) break
            }
            replayJob = null
            Log.i(TAG, "replay: done, back to live")
            val inArchive = current()?.archiveOfLive == true
            jumpToLive()
            // exitArchive shows "Live again" itself
            if (!inArchive) showHud(backToLiveText())
        }
    }

    /** Left/right on a recording: the step grows while the key is pressed again and again. */
    fun seekStep(direction: Int) {
        endReplay()
        val step = seekStepper.press(direction)
        if (rewind.isActive()) {
            val target = rewind.rewindBy(step.stepMs) ?: return
            showSeekHud(SeekStepper.hudText(step.totalMs, target))
            return
        }
        val p = player()
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + step.stepMs).coerceIn(0L, duration)
        p.seekTo(target)
        showSeekHud(SeekStepper.hudText(step.totalMs, target))
    }

    /** Next (+1) or previous (-1) category of a recording. */
    fun jumpChapter(direction: Int) {
        val list = chapters()
        val pos = rewind.timelineMs()[0]
        val chapter = (if (direction > 0) VodChapters.next(list, pos) else VodChapters.previous(list, pos)) ?: return
        rewind.seekToTimeline(chapter.positionMs)
        showSeekHud("▶ " + chapter.name)
    }

    fun seekRelative(deltaMs: Long) {
        endReplay()
        val p = player()
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
        p.seekTo(target)
        val sec = deltaMs / 1000
        val sign = if (sec > 0) "+$sec" else "$sec"
        showSeekHud("$sign s")
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val REPLAY_TICK_MS = 250L
    }
}
