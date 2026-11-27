package com.shrug.shrugtv.apihelpers

import android.content.Context
import com.shrug.shrugtv.api.gql.GqlApi
import com.shrug.shrugtv.api.gql.GqlParser
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.telemetry.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads followed categories/games via GraphQL (replaces decompiled FollowedGames.java,
 * ). Suspend functions in the caller's scope since .
 */
class FollowedGames {

    companion object {

        private fun fetchFollowedGames(context: Context): List<Movie> {
            return try {
                val json = GqlApi.getFollowedGames(context) ?: return emptyList()
                val nodes = json.getAsJsonObject("data")
                    ?.getAsJsonObject("user")
                    ?.getAsJsonObject("followedGames")
                    ?.getAsJsonArray("nodes")
                if (nodes == null || nodes.size() == 0) return emptyList()
                val parsed = GqlParser.Companion.parseFollowedGames(nodes) ?: return emptyList()
                parsed.sortedByDescending { it.viewerCount }
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                emptyList()
            }
        }

        /** Followed categories, most viewers first; empty on errors. */
        suspend fun getList(context: Context): List<Movie> =
            withContext(Dispatchers.IO) { fetchFollowedGames(context) }

        suspend fun isFollowed(context: Context, gameId: Long): Boolean =
            getList(context).any { it.gameId == gameId }
    }
}
