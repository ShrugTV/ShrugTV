package com.shrug.shrugtv.apihelpers

import android.content.Context
import com.shrug.shrugtv.database.AppDatabase
import com.shrug.shrugtv.database.FavoriteInfo
import com.shrug.shrugtv.session.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Room-backed favorite channel helpers (replaces decompiled FavoriteChannels.java,
 * ). Suspend functions in the caller's scope since ; the writes run
 * NonCancellable, so leaving the page right after the click still stores the favorite.
 */
class FavoriteChannels {

    companion object {

        /** Runs a write to the end even when the caller's scope is cancelled meanwhile; the caller continues after it. */
        internal suspend fun <T> write(block: () -> T): T = withContext(NonCancellable + Dispatchers.IO) { block() }

        suspend fun isFavorited(context: Context, channelId: String): Boolean = withContext(Dispatchers.IO) {
            val ids = AppDatabase.getInstance(context).favoriteChannelDao().allChannelIds
            AppState.favoriteChannelIds = ids
            ids.contains(channelId)
        }

        suspend fun removeFavorite(context: Context, channelId: String) = write {
            val dao = AppDatabase.getInstance(context).favoriteChannelDao()
            dao.deleteByChannelId(channelId)
            AppState.favoriteChannelIds = dao.allChannelIds
        }

        suspend fun setFavorite(context: Context, channelId: String) = write {
            val dao = AppDatabase.getInstance(context).favoriteChannelDao()
            val info = FavoriteInfo().apply { this.channelId = channelId }
            dao.insert(info)
            AppState.favoriteChannelIds = dao.allChannelIds
        }
    }
}
