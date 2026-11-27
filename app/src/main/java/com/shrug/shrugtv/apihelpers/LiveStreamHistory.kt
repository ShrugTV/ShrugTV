package com.shrug.shrugtv.apihelpers

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.JsonArray
import com.shrug.shrugtv.api.helix.HelixApi
import com.shrug.shrugtv.api.helix.HelixParser
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.telemetry.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live stream history channel ID list and live status lookup (replaces decompiled
 * LiveStreamHistory.java). Since  without an own scope: the lookup is a
 * suspend function in the caller's scope, the writes are plain calls (SharedPreferences.apply already writes in the
 * background).
 */
class LiveStreamHistory {

    companion object {
        const val PREF_KEY = "pref_live_stream_history_channel_ids"
        const val MAX_ENTRIES = 100

        private fun splitIds(raw: String): List<String> =
            if (raw.isEmpty()) emptyList() else raw.split(",").filter { it.isNotEmpty() }

        private fun updateIdList(raw: String, channelId: String, add: Boolean): String {
            if (raw.isEmpty() && add) return channelId
            if (raw.isEmpty() && !add) return ""
            val list = splitIds(raw).toMutableList()
            list.remove(channelId)
            if (add) {
                list.add(0, channelId)
            }
            while (list.size > MAX_ENTRIES) {
                list.removeAt(list.size - 1)
            }
            return list.joinToString(",")
        }

        private fun loadIds(context: Context): String =
            PreferenceManager.getDefaultSharedPreferences(context)?.getString(PREF_KEY, "") ?: ""

        private fun saveIds(context: Context, ids: String) {
            PreferenceManager.getDefaultSharedPreferences(context)
                ?.edit()
                ?.putString(PREF_KEY, ids)
                ?.apply()
        }

        fun addToHistory(context: Context, userId: Int) {
            try {
                saveIds(context, updateIdList(loadIds(context), userId.toString(), add = true))
            } catch (e: Exception) {
                CrashReporter.recordException(e)
            }
        }

        fun removeFromHistory(context: Context, userId: Int) {
            try {
                saveIds(context, updateIdList(loadIds(context), userId.toString(), add = false))
            } catch (e: Exception) {
                CrashReporter.recordException(e)
            }
        }

        fun clearLiveStreamHistory(context: Context) {
            try {
                saveIds(context, "")
            } catch (e: Exception) {
                CrashReporter.recordException(e)
            }
        }

        fun toUserIdQuery(input: String): String =
            splitIds(input).joinToString(separator = "&user_id=", prefix = "user_id=")

        /** Channels of the history that are live now, in history order; empty on errors. */
        suspend fun getLiveChannels(context: Context): ArrayList<Movie> = withContext(Dispatchers.IO) {
            try {
                val rawIds = loadIds(context)
                if (rawIds.isEmpty()) return@withContext ArrayList()
                val response = HelixApi.getStreamsByQueryNoAccept(context, toUserIdQuery(rawIds))
                if (response == null || response.get("error") != null) return@withContext ArrayList()
                val data = response.get("data") as? JsonArray ?: return@withContext ArrayList()
                val streams = HelixParser.parseStreams(context, Enums.RowType.LIVE_CHANNEL_HISTORY, data)
                ChannelAvatarLoader.loadAvatars(context, streams)
                if (streams.isNotEmpty()) sortByHistoryOrder(rawIds, streams) else ArrayList()
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                ArrayList()
            }
        }

        private fun sortByHistoryOrder(rawIds: String, streams: ArrayList<Movie>): ArrayList<Movie> {
            return try {
                val byUserId = streams.associateBy { it.userId }
                val ordered = ArrayList<Movie>(streams.size)
                for (idStr in splitIds(rawIds)) {
                    val id = idStr.toIntOrNull() ?: continue
                    val movie = byUserId[id] ?: continue
                    ordered.add(movie)
                }
                ordered
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                streams
            }
        }
    }
}
