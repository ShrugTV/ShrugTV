package com.shrug.shrugtv.ui.tv.player

import android.content.Context
import android.util.Log
import com.shrug.shrugtv.api.gql.GqlApi
import com.shrug.shrugtv.api.gql.GqlParser
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.StreamQuality
import com.shrug.shrugtv.model.VodChapter
import com.shrug.shrugtv.player.PlaybackUrlResolver
import com.shrug.shrugtv.telemetry.CrashReporter

/**
 * The lookups of the player against Helix, GQL and the playlist resolver; split from PlayerViewModel
 *. None of them touches the player, so the controllers get them as plain functions.
 */
object PlayerLookups {
    private const val TAG = "PlayerViewModel"

    /** The playlists of a recording or clip, looked up on the main thread's resolver; null when there are none. */
    suspend fun vodQualities(context: Context, playbackId: String?): List<StreamQuality>? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val resolver = PlaybackUrlResolver(context, playbackId, Enums.PlaybackKind.VOD) { q, _ ->
                if (cont.isActive) cont.resume(q) {}
            }
            resolver.useStreamToken = false
            resolver.start()
        }

    /** Blocking: the categories of a recording, empty when there are none or the lookup failed. */
    fun chapters(context: Context, videoId: Long): List<VodChapter> = try {
        val json = GqlApi.getVideoChapters(context, videoId)
        if (json != null) GqlParser.parseVideoChapters(json) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Blocking: the channel's past broadcasts, with what has been watched of them, and the one that follows [current]. */
    suspend fun nextRecording(context: Context, current: Movie): Movie? = try {
        var userId = current.userId
        var publishedMs = current.publishedAt?.toInstant()?.toEpochMilli()
        if (userId <= 0 || publishedMs == null) {
            // Opened by link: only the video id is known
            val obj = com.shrug.shrugtv.api.helix.HelixApi.getVideoById(context, current.videoId)
                ?.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
            if (userId <= 0) userId = obj?.get("user_id")?.asString?.toIntOrNull() ?: 0
            if (publishedMs == null) publishedMs = obj?.get("published_at")?.asString?.let { java.time.Instant.parse(it).toEpochMilli() }
        }
        if (userId <= 0) null else {
            val json = kotlinx.coroutines.suspendCancellableCoroutine<com.google.gson.JsonObject?> { cont ->
                com.shrug.shrugtv.api.helix.HelixApi.getVideos(context, userId.toString(), "", "archive") { _, result ->
                    if (cont.isActive) cont.resume(result) {}
                }
            }
            val videos = json?.getAsJsonArray("data")?.let {
                com.shrug.shrugtv.api.helix.HelixParser.parseVideos(it, Enums.RowType.VOD_CHANNEL_ARCHIVED, current.avatarUrl ?: "")
            } ?: arrayListOf()
            com.shrug.shrugtv.util.UiUtils.applySavedVodPositions(context, videos)
            val pick = com.shrug.shrugtv.player.VodNext.pick(
                current.videoId, publishedMs,
                videos.map { com.shrug.shrugtv.player.VodNext.Candidate(it.videoId, it.publishedAt?.toInstant()?.toEpochMilli(), it.resumeProgressPercent) }
            )
            videos.firstOrNull { it.videoId == pick?.videoId }
        }
    } catch (e: Exception) {
        CrashReporter.recordException(e)
        null
    }

    /** Blocking: the recording of the live stream [movie] that is running now, or null. */
    fun liveArchive(context: Context, movie: Movie): com.shrug.shrugtv.player.LiveArchiveInfo? = try {
        val query = if (movie.userId > 0) "user_id=${movie.userId}" else "user_login=${movie.login}"
        val stream = com.shrug.shrugtv.api.helix.HelixApi.getStreamsByQuery(context, query)
            ?.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
        val userId = stream?.get("user_id")?.asString?.toIntOrNull()
        if (stream == null || userId == null) null else {
            val streamId = stream.get("id")?.asString ?: ""
            val startMs = stream.get("started_at")?.asString?.let { java.time.Instant.parse(it).toEpochMilli() }
            val videos = com.shrug.shrugtv.api.helix.HelixApi.getRecentArchives(context, userId.toString())
                ?.getAsJsonArray("data")?.mapNotNull { e ->
                    val o = e.asJsonObject
                    val id = o.get("id")?.asString?.toLongOrNull() ?: return@mapNotNull null
                    com.shrug.shrugtv.player.LiveRewind.Candidate(
                        id, o.get("stream_id")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        o.get("created_at")?.asString?.let { java.time.Instant.parse(it).toEpochMilli() }
                    )
                } ?: emptyList()
            val match = com.shrug.shrugtv.player.LiveRewind.match(streamId, startMs, videos)
            val start = startMs ?: match?.createdAtMs
            if (match == null || start == null) null else com.shrug.shrugtv.player.LiveArchiveInfo(userId, streamId, match.videoId, start)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Looking for the recording of the stream failed: ${e.message}")
        null
    }
}
