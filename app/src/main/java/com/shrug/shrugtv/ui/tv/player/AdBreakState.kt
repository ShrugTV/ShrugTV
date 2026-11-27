package com.shrug.shrugtv.ui.tv.player

import com.shrug.shrugtv.R
import com.shrug.shrugtv.hub.HubEvent
import com.shrug.shrugtv.player.hls.TwitchAdBlocker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ad-break sign of the player (B13), split from PlayerViewModel: turns the label of
 * [TwitchAdBlocker] into a string resource and tells Home Assistant when a break starts and ends.
 */
class AdBreakState(
    private val emit: (HubEvent) -> Unit,
    private val channel: () -> String?
) {
    private val _label = MutableStateFlow<Int?>(null)

    /** Ad-break sign as a string resource, null = no break. */
    val label: StateFlow<Int?> = _label.asStateFlow()

    val inBreak: Boolean get() = _label.value != null

    /** Label from [TwitchAdBlocker], on the main thread; null = the break is over. */
    fun onBlockerLabel(label: String?) {
        val wasInBreak = _label.value != null
        _label.value = labelRes(label)
        if (!wasInBreak && label != null) emit(HubEvent(HubEvent.AD_BREAK_START, mapOf("channel" to channel())))
        if (wasInBreak && label == null) emit(HubEvent(HubEvent.AD_BREAK_END, mapOf("channel" to channel())))
    }

    companion object {
        fun labelRes(label: String?): Int? = when (label) {
            null -> null
            TwitchAdBlocker.LABEL_BLOCKING -> R.string.player_ad_blocking
            else -> R.string.player_ad_stripping
        }
    }
}
