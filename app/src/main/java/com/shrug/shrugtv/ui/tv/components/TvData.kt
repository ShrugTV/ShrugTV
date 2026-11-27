package com.shrug.shrugtv.ui.tv.components

import android.content.Context
import com.shrug.shrugtv.apihelpers.FavoriteChannels
import com.shrug.shrugtv.apihelpers.FollowedGames
import com.shrug.shrugtv.apihelpers.LiveStreamHistory
import com.shrug.shrugtv.apihelpers.Search
import com.shrug.shrugtv.model.Movie

/**
 * Data-layer entry points for the Compose screens. Suspend functions since :
 * the screens call them from their own scope, so closing a screen cancels its requests.
 */
object TvData {

    suspend fun searchLive(c: Context, text: String): List<Movie> = Search.getLiveStreams(c, text)

    suspend fun searchChannels(c: Context, text: String): List<Movie> = Search.getChannels(c, text)

    suspend fun searchGames(c: Context, text: String): List<Movie> = Search.getGames(c, text)

    suspend fun isFavorite(c: Context, channelId: String): Boolean = FavoriteChannels.isFavorited(c, channelId)

    /** Stores the favorite; the write finishes even if the calling screen closes meanwhile. */
    suspend fun setFavorite(c: Context, channelId: String, on: Boolean) {
        if (on) FavoriteChannels.setFavorite(c, channelId)
        else FavoriteChannels.removeFavorite(c, channelId)
    }

    suspend fun gameFollowed(c: Context, gameId: Long): Boolean = FollowedGames.isFollowed(c, gameId)

    /** Add the channel to the live history or remove it. */
    fun history(c: Context, channelId: Int, on: Boolean) {
        if (on) LiveStreamHistory.addToHistory(c, channelId)
        else LiveStreamHistory.removeFromHistory(c, channelId)
    }
}
