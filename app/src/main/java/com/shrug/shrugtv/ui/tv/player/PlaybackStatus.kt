package com.shrug.shrugtv.ui.tv.player

enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

/**
 * What the player's own events may do to the status that is shown: after a failure the
 * player goes idle and stops "playing", and neither may wipe the error that is on the screen; only a new
 * attempt (buffering, ready, playing) replaces it.
 */
object PlaybackStatusRules {
    fun afterStateChange(current: PlaybackStatus, shown: PlaybackStatus): PlaybackStatus =
        if (current == PlaybackStatus.ERROR && shown == PlaybackStatus.IDLE) current else shown

    fun afterPlayingChange(current: PlaybackStatus, isPlaying: Boolean): PlaybackStatus =
        if (isPlaying) PlaybackStatus.PLAYING else if (current == PlaybackStatus.ERROR) current else PlaybackStatus.PAUSED
}
