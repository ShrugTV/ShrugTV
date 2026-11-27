package com.shrug.shrugtv.apihelpers

import android.content.Context
import com.shrug.shrugtv.api.gql.GqlApi
import com.shrug.shrugtv.api.gql.GqlParser
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.telemetry.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads top categories/games via GraphQL (replaces decompiled TopGames.java). A suspend
 * function in the caller's scope since : leaving the page cancels the request.
 */
class TopGames {

    companion object {

        /** One page of top categories and the cursor of the next one; empty list and "" on errors. */
        suspend fun getList(context: Context, cursor: String): Pair<List<Movie>, String> =
            withContext(Dispatchers.IO) { fetchPage(context, cursor) }

        private fun fetchPage(context: Context, cursorIn: String): Pair<List<Movie>, String> {
            return try {
                val edges = GqlApi.getTopGames(context, cursorIn)
                    ?.getAsJsonObject("data")
                    ?.getAsJsonObject("games")
                    ?.getAsJsonArray("edges")
                if (edges != null && edges.size() > 0) {
                    GqlParser.Companion.parseTopGames(edges)
                } else {
                    emptyList<Movie>() to ""
                }
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                emptyList<Movie>() to ""
            }
        }
    }
}
