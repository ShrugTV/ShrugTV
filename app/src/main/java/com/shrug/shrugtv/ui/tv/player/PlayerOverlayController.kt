package com.shrug.shrugtv.ui.tv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayerOverlayType {
    NONE,
    CONTROLS,
    SWITCHER,
    QUALITY,
    LAYOUT_EDITOR,
    SEARCH,
    SEND_MESSAGE,
    STATS,
    SPEED,
    CHAPTERS,
    /** Most viewed clips of the running live stream. */
    HIGHLIGHTS
}

/**
 * Which overlay of the player is open, split from PlayerViewModel. [beforeBack] sees the overlay
 * that Back is about to leave (the chat editor restarts the chat when its sizes changed).
 */
class PlayerOverlayController(private val beforeBack: (PlayerOverlayType) -> Unit = {}) {
    private val _active = MutableStateFlow(PlayerOverlayType.NONE)
    val active: StateFlow<PlayerOverlayType> = _active.asStateFlow()

    /** Opens [overlay], or closes it when it is open already. */
    fun toggle(overlay: PlayerOverlayType) {
        _active.value = if (_active.value == overlay) PlayerOverlayType.NONE else overlay
    }

    fun dismiss() {
        _active.value = PlayerOverlayType.NONE
    }

    /** Shows [overlay] without the toggle behaviour of [toggle]. */
    fun show(overlay: PlayerOverlayType) {
        _active.value = overlay
    }

    /**
     * Back from an overlay: menus opened from the control bar return to the bar (B34), the bar and
     * the channel switcher return to the video.
     */
    fun back() {
        beforeBack(_active.value)
        _active.value = backTarget(_active.value)
    }

    companion object {
        /** Where Back leads from [overlay]. */
        fun backTarget(overlay: PlayerOverlayType): PlayerOverlayType = when (overlay) {
            PlayerOverlayType.QUALITY, PlayerOverlayType.SPEED, PlayerOverlayType.CHAPTERS, PlayerOverlayType.HIGHLIGHTS,
            PlayerOverlayType.STATS, PlayerOverlayType.SEARCH, PlayerOverlayType.SEND_MESSAGE,
            PlayerOverlayType.LAYOUT_EDITOR -> PlayerOverlayType.CONTROLS
            else -> PlayerOverlayType.NONE
        }
    }
}
