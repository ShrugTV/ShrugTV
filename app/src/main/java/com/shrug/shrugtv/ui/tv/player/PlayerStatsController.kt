package com.shrug.shrugtv.ui.tv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The values of the statistics overlay, split from PlayerViewModel. The player reads a [Sample] on
 * the main thread; this keeps the last [HISTORY] values of bitrate and buffer for the small charts.
 */
class PlayerStatsController {

    /** One reading of the player; [latencyMs] and [chatDelayMs] are null when unknown (VOD, no live chat). */
    data class Sample(
        val width: Int?,
        val height: Int?,
        val frameRate: Float,
        val bitrateBps: Int,
        val bufferedMs: Long,
        val droppedFrames: Int,
        val renderedFrames: Int,
        val latencyMs: Long?,
        val chatDelayMs: Long?,
        val memoryMb: Int,
        val emoteFps: Float,
        val emoteCount: Int
    )

    private val _stats = MutableStateFlow(PlayerStatsState())
    val stats: StateFlow<PlayerStatsState> = _stats.asStateFlow()

    fun update(sample: Sample) {
        _stats.value = next(_stats.value, sample)
    }

    companion object {
        /** Values kept for the charts. */
        const val HISTORY = 30

        fun next(old: PlayerStatsState, s: Sample): PlayerStatsState {
            val res = if (s.width != null && s.height != null) "${s.width} × ${s.height} @ ${s.frameRate.toInt()} fps" else "–"
            val bitrate = if (s.width != null && s.bitrateBps > 0) s.bitrateBps / 1000 else 0
            val bufferSec = s.bufferedMs / 1000f
            return old.copy(
                emoteFps = s.emoteFps,
                emoteCount = s.emoteCount,
                resolution = res,
                bitrateKbps = bitrate,
                bufferSeconds = bufferSec,
                droppedFrames = s.droppedFrames,
                totalFrames = s.renderedFrames + s.droppedFrames,
                latencySeconds = s.latencyMs?.let { it / 1000f } ?: -1f,
                chatDelaySeconds = s.chatDelayMs?.let { it / 1000f } ?: -1f,
                memoryMb = s.memoryMb,
                bitrateHistory = (old.bitrateHistory + bitrate.toFloat()).takeLast(HISTORY),
                bufferHistory = (old.bufferHistory + bufferSec).takeLast(HISTORY)
            )
        }
    }
}
