package com.shrug.shrugtv.apihelpers

import android.content.Context
import android.os.SystemClock
import com.google.gson.JsonObject
import com.shrug.shrugtv.api.helix.HelixApi
import com.shrug.shrugtv.api.helix.HelixParser
import com.shrug.shrugtv.database.AppDatabase
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.session.AppState
import com.shrug.shrugtv.telemetry.CrashReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads the user's followed channels via Helix pagination, enriches them with user avatars and
 * descriptions, and sorts favorites first (replaces decompiled FollowedChannels.java,
 * ).
 *
 * Since  a suspend function in the caller's scope (leaving the page stops the pagination
 * between two requests), and the channel list is kept for [CACHE_MS]: home, clips and the MQTT bridge each ask for
 * it, and one load is up to 11 Helix pages plus as many user requests. The cache belongs to the access token and
 * user id, so signing in, out or switching accounts is a miss; favorites are applied fresh on every call.
 */
class FollowedChannels {

    /** The list, and whether the request failed (an empty list alone cannot tell "follows nobody" from "no answer"). */
    class Result(val list: List<Movie>, val failed: Boolean)

    companion object {
        const val CACHE_MS = 60_000L
        private const val MAX_PAGES = 10

        internal val cache = TimedCache<List<Movie>>(CACHE_MS) { SystemClock.elapsedRealtime() }

        /** One load at a time: a second caller waits and then takes the cached list instead of asking again. */
        private val loading = Mutex()

        suspend fun getList(context: Context): List<Movie> = getListResult(context).list

        /** Like [getList], and says whether the request failed; the clips page needs that. */
        suspend fun getListResult(context: Context): Result = withContext(Dispatchers.IO) {
            try {
                val favIds = AppDatabase.getInstance(context).favoriteChannelDao().allChannelIds
                AppState.favoriteChannelIds = favIds
                val key = cacheKey()
                val channels = loading.withLock {
                    cache.get(key) ?: loadPages(
                        MAX_PAGES,
                        page = { cursor -> HelixApi.getFollowedChannelsPage(context, cursor) },
                        users = { query -> HelixApi.getUsersByQuery(context, query) },
                    )?.also { cache.put(key, it) }
                } ?: return@withContext Result(emptyList(), true)
                markFavorites(channels, favIds)
                Result(sortFavoritesFirst(channels), false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                Result(emptyList(), true)
            }
        }

        /** Drops the cached list, e.g. after following or unfollowing a channel in the app. */
        fun invalidate() = cache.clear()

        private fun cacheKey(): String = "${AppState.userId}|${AppState.accessToken}"

        /**
         * All pages (at most [maxPages] + 1, as before), enriched with avatars and descriptions; null when an answer
         * is missing or broken. Checks for cancellation before every request.
         */
        internal suspend fun loadPages(
            maxPages: Int,
            page: (cursor: String) -> JsonObject?,
            users: (query: String) -> JsonObject?,
        ): List<Movie>? {
            var cursor = ""
            val allChannels = ArrayList<Movie>()
            var index = 0
            while (index <= maxPages) {
                currentCoroutineContext().ensureActive()
                val response = page(cursor)
                if (response == null || response.get("error") != null || response.get("errors") != null || response.get("data") == null) {
                    return null
                }
                val dataArray = response.getAsJsonArray("data") ?: return null
                val pagination = response.getAsJsonObject("pagination")
                cursor = if (pagination != null && pagination.has("cursor")) {
                    pagination.getAsJsonPrimitive("cursor").asString
                } else {
                    ""
                }
                val pageChannels = HelixParser.parseFollowedChannels(dataArray)
                if (pageChannels.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val query = pageChannels.joinToString(separator = "&id=", prefix = "?id=") { it.userId.toString() }
                    val found = HelixParser.parseUsers(users(query))
                    if (found.isNotEmpty()) {
                        val byUserId = found.associateBy { it.userId }
                        for (movie in pageChannels) {
                            val user = byUserId[movie.userId] ?: continue
                            movie.avatarUrl = user.avatarUrl
                            movie.setThumbnailUrl(user.cardImageUrl)
                            movie.description = user.description
                        }
                    }
                    allChannels.addAll(pageChannels)
                }
                index++
                if (cursor.isEmpty()) break
            }
            return allChannels
        }

        /** Sets the favorite flag both ways: the cached Movie objects are shared between calls. */
        private fun markFavorites(list: List<Movie>, favoriteIds: List<String>) {
            val favSet = favoriteIds.mapNotNull { it.toIntOrNull() }.toHashSet()
            for (movie in list) {
                movie.isFavorite = movie.userId in favSet
            }
        }

        internal fun sortFavoritesFirst(list: List<Movie>): List<Movie> {
            val sortedByName = list.sortedBy { it.login }
            val favorites = sortedByName.filter { it.isFavorite }
            if (favorites.isEmpty()) return sortedByName
            val nonFavorites = sortedByName.filterNot { it.isFavorite }
            return favorites + nonFavorites
        }
    }
}
