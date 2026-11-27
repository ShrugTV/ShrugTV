package com.shrug.shrugtv.apihelpers

import android.content.Context
import com.shrug.shrugtv.api.helix.HelixApi
import com.shrug.shrugtv.api.helix.HelixParser
import com.shrug.shrugtv.database.AppDatabase
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.session.AppState
import com.shrug.shrugtv.telemetry.CrashReporter
import com.shrug.shrugtv.util.UiUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Loads recent archived broadcasts from the user's favorite channels in parallel
 * (replaces decompiled PastBroadcast.java). A suspend function in the caller's scope since
 * : leaving the page cancels the channel requests that have not started yet.
 */
class PastBroadcast {

    companion object {
        /** Recent archived broadcasts of the favorite channels, newest first; empty on errors. */
        suspend fun getList(context: Context): List<Movie> = withContext(Dispatchers.IO) {
            try {
                val favIds = AppDatabase.getInstance(context).favoriteChannelDao().allChannelIds
                AppState.favoriteChannelIds = favIds
                if (favIds.isEmpty()) return@withContext emptyList()
                val query = favIds.joinToString(separator = "&id=", prefix = "?id=")
                val usersJson = HelixApi.getUsersByQuery(context, query) ?: return@withContext emptyList()
                val channels = HelixParser.parseUsers(usersJson)
                if (channels.isEmpty()) return@withContext emptyList()
                val archives = fetchArchivesParallel(context, channels)
                val sorted = ArrayList(archives.sortedWith { a, b -> compareValues(b.publishedAt, a.publishedAt) })
                UiUtils.applySavedVodPositions(context, sorted)
                sorted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                emptyList()
            }
        }

        private suspend fun fetchArchivesParallel(context: Context, channels: List<Movie>): List<Movie> = coroutineScope {
            val gate = Semaphore(6)
            channels.map { channel ->
                async {
                    gate.withPermit {
                        fetchChannelArchives(context, channel)
                    }
                }
            }.awaitAll().flatten()
        }

        private fun fetchChannelArchives(context: Context, channel: Movie): List<Movie> {
            return try {
                val json = HelixApi.getRecentArchives(context, channel.userId.toString()) ?: return emptyList()
                val dataElem = json.get("data")
                if (dataElem == null || dataElem.isJsonNull) return emptyList()
                val videos = HelixParser.parseVideos(dataElem.asJsonArray, Enums.RowType.VOD_CHANNEL_ARCHIVED, channel.avatarUrl ?: "")
                videos.filter { !it.cardImageUrl.isNullOrEmpty() }
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                emptyList()
            }
        }
    }
}
