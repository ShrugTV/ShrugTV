package com.shrug.shrugtv.session

import android.content.Context
import androidx.preference.PreferenceManager
import com.shrug.shrugtv.R
import com.shrug.shrugtv.chat.KnownBots
import com.shrug.shrugtv.filter.FilterSettings
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.notification.LiveNotificationTicker
import com.shrug.shrugtv.player.PlaybackUrlResolver
import com.shrug.shrugtv.prefs.Prefs
import com.shrug.shrugtv.telemetry.CrashReporter

object GlobalSettings {

    @JvmStatic
    fun load(ctx: Context) {
        PreferenceManager.setDefaultValues(ctx, R.xml.settings, false)
        AppState.languageFlags = AppState.buildLanguageFlags()
        KnownBots.init()
        val user = ctx.getSharedPreferences("User", 0)
        AppState.accessToken = com.shrug.shrugtv.security.SecretStore.get(ctx, com.shrug.shrugtv.security.SecretStore.USER_FILE, com.shrug.shrugtv.security.SecretStore.TOKEN)
        AppState.loginVersionName = user.getString("VersionName", "") ?: ""
        AppState.loginVersionCode = user.getInt("VersionCode", 0)

        val p = PreferenceManager.getDefaultSharedPreferences(ctx) ?: return
        if ("small" == Prefs.videoCardSize.get(p)) {
            AppState.previewImageWidth = 560
            AppState.previewImageHeight = 315
        } else {
            AppState.previewImageWidth = 800
            AppState.previewImageHeight = 450
        }
        AppState.showLanguageRow = Prefs.showLanguageRow.get(p)
        AppState.showLanguageRowGames = Prefs.showLanguageRowGames.get(p)
        AppState.languageRowAsPrimary = Prefs.languageRowAsPrimary.get(p)
        AppState.showTopGames = Prefs.showTopGames.get(p)
        AppState.showTopStreamers = Prefs.showTopStreamers.get(p)
        AppState.showFollowed = Prefs.showFollowed.get(p)
        AppState.showLiveStreamHistory = Prefs.showLiveStreamHistory.get(p)
        AppState.showFollowedGames = Prefs.showFollowedGames.get(p)
        AppState.showFollowedChannels = Prefs.showFollowedChannels.get(p)
        AppState.showPastBroadcasts = Prefs.showPastBroadcasts.get(p)
        // These three had other defaults in code (false, false, 30) than settings.xml; setDefaultValues above has
        // always stored the XML values, so the XML values are the ones the app ran with
        AppState.showVodHistory = Prefs.showVodHistory.get(p)
        AppState.autoRefreshEnabled = Prefs.autoRefresh.get(p)
        AppState.autoRefreshMinutes = Prefs.autoRefreshMinutes.get(p)

        val proxyIdx = Prefs.proxyServerName.get(p)
        val proxyServer = Enums.ProxyServer.values().getOrElse(proxyIdx) { Enums.ProxyServer.values()[0] }
        PlaybackUrlResolver.setProxyServer(proxyServer)
        PlaybackUrlResolver.setProxyUrl(Prefs.proxyUrl.get(p))

        val language = Prefs.languageRowCode.get(p)
        if (language.isNotEmpty()) {
            val langName = AppState.buildLanguageNames()[language]
            AppState.streamLanguageName = langName ?: ""
            AppState.streamLanguageCode = language
        }
        AppState.favoritesInFrontOfFollowed = Prefs.favoritesInFrontOfFollowed.get(p)
        SessionStore.loadFromPrefs(ctx)
        AppSettings.sync(ctx)
        FilterSettings.load(ctx)
        notificationService(p)
    }

    private fun notificationService(p: android.content.SharedPreferences) {
        try {
            // The live card in the player needs the ticker too
            val on = Prefs.notificationOn.get(p) || Prefs.notificationPlayerCard.get(p)
            val ticker = LiveNotificationTicker.get()
            if (ticker != null) {
                if (on) {
                    ticker.startTicker()
                } else {
                    ticker.stopTicker()
                }
            }
        } catch (e: Exception) {
            CrashReporter.recordException(e)
        }
    }
}
