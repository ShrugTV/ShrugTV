package com.shrug.shrugtv.ui.tv.home

import android.content.Context
import com.google.gson.JsonObject
import com.shrug.shrugtv.MainApp
import com.shrug.shrugtv.apihelpers.FollowedChannels
import com.shrug.shrugtv.apihelpers.FollowedGames
import com.shrug.shrugtv.apihelpers.LiveStreamHistory
import com.shrug.shrugtv.apihelpers.PastBroadcast
import com.shrug.shrugtv.apihelpers.TopGames
import com.shrug.shrugtv.database.AppDatabase
import com.shrug.shrugtv.api.helix.HelixApi
import com.shrug.shrugtv.api.helix.HelixParser
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.session.AppState
import com.shrug.shrugtv.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Data sources of the new home screen. Thin wrappers around the existing data layer (HelixApi,
 * FollowedChannels, TopGames) so the Compose UI can work with coroutines; none of it is reimplemented
 * (step 3).
 */
sealed class Login {
    object LoggedIn : Login()
    object LoggedOut : Login()
    data class Error(val text: String) : Login()
}

object HomeData {

    /** Loads the profile (AppState.userId etc.); without it the follow queries stay empty. */
    suspend fun loadProfile(context: Context): Login {
        if (AppState.accessToken.isNullOrEmpty()) return Login.LoggedOut
        // Suspending since : leaving home cancels the request itself, not only the wait for it.
        var login: Login = Login.LoggedOut
        UserSession.awaitUserProfile(context, object : UserSession.ProfileCallback {
            override fun onError(str: String) { login = Login.Error(str) }
            override fun onUnauthorized() { login = Login.LoggedOut }
            override fun onEmptyResponse() { login = if (AppState.userProfileLoaded) Login.LoggedIn else Login.LoggedOut }
            override fun onRequestFailed() { login = Login.Error(context.getString(com.shrug.shrugtv.R.string.home_twitch_unreachable)) }
        })
        return login
    }

    /** A Helix query; null on any failure. Cancelling the caller cancels the OkHttp call. */
    private suspend fun helix(query: suspend () -> JsonObject): JsonObject? = try {
        query()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Parse in the background: parseStreams loads the channel avatars; on the main thread that happens
     * deferred since , and Compose does not see avatars set later (circles stayed
     * empty, 1.5.0). Here they load blocking, before the row appears.
     */
    private suspend fun streamsFrom(context: Context, json: JsonObject?, source: Enums.RowType): List<Movie> =
        withContext(Dispatchers.IO) {
            val data = json?.get("data")
            if (data == null || !data.isJsonArray) emptyList()
            else HelixParser.parseStreams(context, source, data.asJsonArray).sortedByDescending { it.viewerCount }
        }

    suspend fun followedLive(context: Context): List<Movie> =
        streamsFrom(context, helix { HelixApi.awaitFollowedStreams(context) }, Enums.RowType.FOLLOWED)

    suspend fun topStreams(context: Context): List<Movie> =
        streamsFrom(context, helix { HelixApi.awaitTopStreams(context, "") }, Enums.RowType.TOP_STREAMS)

    /** Live streams of one category, optionally only in the app language (channel switcher). */
    suspend fun gameStreams(context: Context, gameId: Long, byLanguage: Boolean): List<Movie> =
        if (byLanguage) streamsFrom(context, helix { HelixApi.awaitStreamsByGameAndLanguage(context, gameId, "") }, Enums.RowType.GAME_LANGUAGE)
        else streamsFrom(context, helix { HelixApi.awaitStreamsByGame(context, gameId, "") }, Enums.RowType.GAME)

    /** Category id of a live channel by its login; 0 if it is offline or unknown (a channel opened by link has none yet). */
    suspend fun gameIdOfLogin(context: Context, login: String): Long = withContext(Dispatchers.IO) {
        try {
            val data = HelixApi.getStreamsByQuery(context, "user_login=" + java.net.URLEncoder.encode(login, "UTF-8"))?.get("data")
            if (data == null || !data.isJsonArray) 0L
            else HelixParser.parseStreams(context, Enums.RowType.GAME, data.asJsonArray).firstOrNull()?.gameId?.takeIf { it > 0 } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** Top live streams in the app language. */
    suspend fun languageStreams(context: Context): List<Movie> =
        streamsFrom(context, helix { HelixApi.awaitStreamsByLanguage(context, "") }, Enums.RowType.LANGUAGE)

    // The helpers are suspend functions since : leaving home cancels them with the caller's scope.
    suspend fun followedChannels(context: Context): List<Movie> = FollowedChannels.getList(context)

    suspend fun topGames(context: Context): List<Movie> = TopGames.getList(context, "").first

    suspend fun followedGames(context: Context): List<Movie> = FollowedGames.getList(context)

    suspend fun liveHistory(context: Context): List<Movie> = LiveStreamHistory.getLiveChannels(context)

    suspend fun pastBroadcasts(context: Context): List<Movie> = PastBroadcast.getList(context)

    /** Continue watching: VOD history from Room, newest first (sorted in SQL, re-sorted defensively). */
    suspend fun continueWatching(context: Context): List<Movie> = withContext(Dispatchers.IO) {
        try {
            val history = AppDatabase.getInstance(context)?.vodInfoDao()?.getHistory().orEmpty()
            if (history.isEmpty()) emptyList()
            else UserSession.vodHistoryToItems(history.sortedWith(MainApp::compareByLastAccessDesc))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Playback URL of a live stream for the preview (auto playlist, goes through the ad blocker). */
    suspend fun previewUrl(context: Context, m: Movie): String? {
        // Keep URL and qualities on the Movie: the player uses them if they are
        // younger than 15 min (getPlaybackUrlIfFresh) and saves the token request on opening (Fire TV:
        // about 0.5 s). Preview and player ask for the same token kind (the developer switch for it is gone).
        m.playbackUrlIfFresh?.let { return it }
        return suspendCancellableCoroutine { cont ->
            val r = com.shrug.shrugtv.player.PlaybackUrlResolver(context, m.playbackId, m.playbackType) { qualities, cluster ->
                if (!qualities.isNullOrEmpty()) {
                    m.manifestCluster = cluster
                    m.qualities = qualities
                    m.setPlaybackUrl(qualities[0].url)
                }
                if (cont.isActive) cont.resume(qualities?.firstOrNull()?.url)
            }
            // Leaving the hero preview aborts the token and playlist requests
            cont.invokeOnCancellation { r.cancel() }
            r.setUseStreamToken(false)
            r.start()
        }
    }
}
