package com.shrug.shrugtv.session

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.preference.PreferenceManager
import com.shrug.shrugtv.prefs.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Typed access to the existing SharedPreferences with unchanged `pref_*` keys
 * and default values (stage 0.3).
 */
@Immutable
data class HomeRowSettings(
    val showFollowed: Boolean = true,
    val showLiveHistory: Boolean = true,
    val showVodHistory: Boolean = false,
    val showFollowedChannels: Boolean = true,
    val showFollowedGames: Boolean = true,
    val showPastBroadcasts: Boolean = true,
    val showTopGames: Boolean = true,
    val showTopStreamers: Boolean = true,
    val showLanguageRow: Boolean = false,
    val showLanguageRowGames: Boolean = false,
    val languageRowAsPrimary: Boolean = false,
    val streamLanguageCode: String = "",
    val favoritesInFrontOfFollowed: Boolean = true,
)

object AppSettings {
    private val _homeRows = MutableStateFlow(HomeRowSettings())
    val homeRows: StateFlow<HomeRowSettings> = _homeRows.asStateFlow()

    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @JvmStatic
    fun sync(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        _homeRows.value = readHomeRows(prefs)
        if (listener == null) {
            val l = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
                _homeRows.value = readHomeRows(sp)
            }
            prefs.registerOnSharedPreferenceChangeListener(l)
            listener = l
        }
    }

    private fun readHomeRows(p: SharedPreferences): HomeRowSettings = HomeRowSettings(
        showFollowed = Prefs.showFollowed.get(p),
        showLiveHistory = Prefs.showLiveStreamHistory.get(p),
        showVodHistory = Prefs.showVodHistory.get(p),
        showFollowedChannels = Prefs.showFollowedChannels.get(p),
        showFollowedGames = Prefs.showFollowedGames.get(p),
        showPastBroadcasts = Prefs.showPastBroadcasts.get(p),
        showTopGames = Prefs.showTopGames.get(p),
        showTopStreamers = Prefs.showTopStreamers.get(p),
        showLanguageRow = Prefs.showLanguageRow.get(p),
        showLanguageRowGames = Prefs.showLanguageRowGames.get(p),
        languageRowAsPrimary = Prefs.languageRowAsPrimary.get(p),
        streamLanguageCode = Prefs.languageRowCode.get(p),
        favoritesInFrontOfFollowed = Prefs.favoritesInFrontOfFollowed.get(p),
    )
}
