package com.shrug.shrugtv.ui.tv.player

import androidx.media3.common.Player
import com.shrug.shrugtv.database.VodInfo
import com.shrug.shrugtv.database.VodInfoDao
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The resume position of recordings in Room (vod_info), split from PlayerViewModel.
 *
 * [dao] opens the table, [player] is the main player, [saveScope] outlives the ViewModel (a save launched from
 * `onCleared` must still run, docs/bug_review.md B3), [io] is where the position is read, [now] the ISO time of the last
 * access, [onError] records a failure.
 */
class VodProgressStore(
    private val dao: () -> VodInfoDao,
    private val player: () -> Player,
    private val saveScope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> String,
    private val onError: (Exception) -> Unit
) {
    /** Saved resume position of a VOD in ms, 0 if none. */
    suspend fun positionMs(movie: Movie): Long {
        val videoId = movie.videoId
        if (videoId <= 0L) return 0L
        return withContext(io) {
            try {
                (dao().findByBroadcastId(videoId)?.position ?: 0) * 1000L
            } catch (e: Exception) {
                onError(e)
                0L
            }
        }
    }

    /**
     * Saves the position of [movie], the VOD that is actually loaded in the player (not the one shown: while a new
     * stream resolves, the old one still plays; B3). Reads the player on the calling (main) thread, writes on
     * [saveScope]. The recording of a live stream is not saved.
     */
    fun save(movie: Movie) {
        if (movie.archiveOfLive) return
        val isVod = movie.playbackType == Enums.PlaybackKind.VOD || movie.videoId > 0L
        if (!isVod) return

        val videoId = movie.videoId
        if (videoId <= 0L) return

        val p = player()
        val currentSec = (p.currentPosition / 1000).toInt()
        // An unknown duration is C.TIME_UNSET (a huge negative number): divided and cut to Int it turned into
        // a nonsense positive length. Unknown is 0 now, an existing length stays.
        val durationSec = p.duration.takeIf { it > 0 }?.let { (it / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: 0
        if (currentSec <= 0) return

        saveScope.launch {
            try {
                val table = dao()
                var info = table.findByBroadcastId(videoId)
                if (info == null) {
                    info = VodInfo().apply {
                        broadcastId = videoId
                        position = currentSec
                        vodLength = durationSec
                        lastAccess = now()
                        channelName = movie.displayName
                        channelNameEnglish = movie.login
                        title = movie.title
                        categoryName = movie.gameName
                        videoThumbnail = movie.cardImageUrl
                        logoImage = movie.avatarUrl
                        channelId = movie.userId
                    }
                    table.insert(info)
                } else {
                    info.position = currentSec
                    if (durationSec > 0) info.vodLength = durationSec
                    info.lastAccess = now()
                    table.update(info)
                }
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
