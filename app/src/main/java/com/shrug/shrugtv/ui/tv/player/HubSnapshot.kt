package com.shrug.shrugtv.ui.tv.player

import androidx.media3.common.C
import com.shrug.shrugtv.hub.PlaybackSnapshot
import com.shrug.shrugtv.model.Movie

/**
 * What Home Assistant and the phone see of the player (hub), built from its state; split from PlayerViewModel
 * so the mapping can be tested. The view model decides when to publish (state changes,
 * debounced, and every 10 s for positions) and keeps the commands.
 */
object HubSnapshot {

    /** The state of the player the snapshot is made of; positions in ms as the player reports them. */
    data class State(
        val movie: Movie?,
        val vod: Boolean,
        val status: PlaybackStatus,
        val positionMs: Long,
        val durationMs: Long,
        val liveOffsetMs: Long,
        val avatarUrl: String?,
        val quality: String,
        val qualities: List<String>,
        val chatVisible: Boolean,
        val chatLayout: String,
        val multiview: Boolean,
        val volume: Int,
        val audioOnly: Boolean,
        val followed: Boolean
    )

    fun of(s: State): PlaybackSnapshot {
        val m = s.movie ?: return PlaybackSnapshot.IDLE
        val vod = s.vod
        val offset = if (vod) C.TIME_UNSET else s.liveOffsetMs
        return PlaybackSnapshot(
            status = s.status.name.lowercase(),
            kind = if (vod) "vod" else "live",
            channel = m.displayName.ifEmpty { m.login },
            login = m.login,
            userId = m.userId.takeIf { it > 0 }?.toString(),
            title = m.title,
            category = m.gameName.ifEmpty { null },
            viewers = m.viewerCount,
            startedAtMs = if (vod) null else m.startedAt?.toInstant()?.toEpochMilli(),
            language = StreamInfoText.language(m.languageCode),
            avatarUrl = s.avatarUrl,
            thumbnailUrl = m.cardImageUrl,
            quality = s.quality,
            qualities = s.qualities,
            behindLiveSeconds = if (offset == C.TIME_UNSET) 0 else (offset / 1000).toInt(),
            vodId = if (vod) m.videoId else null,
            vodPositionSeconds = if (vod) (s.positionMs / 1000).toInt() else null,
            vodLengthSeconds = if (vod && s.durationMs > 0) (s.durationMs / 1000).toInt() else null,
            chatVisible = s.chatVisible,
            chatLayout = s.chatLayout,
            multiview = s.multiview,
            volume = s.volume,
            audioOnly = s.audioOnly,
            followed = s.followed,
            favorite = m.isFavorite
        )
    }
}
