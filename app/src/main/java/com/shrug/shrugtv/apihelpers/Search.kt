package com.shrug.shrugtv.apihelpers

import android.content.Context
import com.google.gson.JsonArray
import com.shrug.shrugtv.api.helix.HelixApi
import com.shrug.shrugtv.api.helix.HelixParser
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.telemetry.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Helix search for channels, categories, and live streams (replaces decompiled Search.java,
 * ). Suspend functions in the caller's scope since : a new query or leaving
 * the page cancels the old search.
 */
class Search {

    companion object {
        private fun collectUserIds(jsonArray: JsonArray): String {
            val ids = ArrayList<String>(jsonArray.size())
            try {
                for (elem in jsonArray) {
                    val id = elem.asJsonObject.get("id")?.asString
                    if (!id.isNullOrEmpty()) ids.add(id)
                }
            } catch (e: Exception) {
                CrashReporter.recordException(e)
            }
            return ids.joinToString("&user_id=")
        }

        suspend fun getChannels(context: Context, searchText: String): List<Movie> = withContext(Dispatchers.IO) {
            try {
                val response = HelixApi.searchChannels(context, URLEncoder.encode(searchText, "utf-8"), false)
                val data = response?.get("data")?.takeIf { !it.isJsonNull }?.asJsonArray
                if (data == null || data.size() == 0) emptyList()
                else HelixParser.parseChannelSearchResults(data, Enums.RowType.SEARCH_CHANNEL)
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                emptyList()
            }
        }

        suspend fun getGames(context: Context, searchText: String): List<Movie> = withContext(Dispatchers.IO) {
            try {
                val response = HelixApi.searchCategories(context, URLEncoder.encode(searchText, "utf-8"))
                val data = response?.get("data")?.takeIf { !it.isJsonNull }?.asJsonArray
                if (data == null || data.size() == 0) emptyList()
                else HelixParser.parseCategories(data, Enums.RowType.SEARCH_GAME)
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                emptyList()
            }
        }

        suspend fun getLiveStreams(context: Context, searchText: String): List<Movie> = withContext(Dispatchers.IO) {
            try {
                val response = HelixApi.searchChannels(context, URLEncoder.encode(searchText, "utf-8"), true)
                val channelData = response?.get("data")?.takeIf { !it.isJsonNull }?.asJsonArray
                if (channelData == null || channelData.size() == 0) return@withContext emptyList()
                val userIds = collectUserIds(channelData)
                if (userIds.isEmpty()) return@withContext emptyList()
                val streamsResponse = HelixApi.getStreamsByQuery(context, "user_id=$userIds")
                val streamsData = streamsResponse?.get("data")?.takeIf { !it.isJsonNull }?.asJsonArray
                if (streamsData == null || streamsData.size() == 0) emptyList()
                else HelixParser.parseStreams(context, Enums.RowType.SEARCH_STREAM, streamsData)
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                emptyList()
            }
        }
    }
}
