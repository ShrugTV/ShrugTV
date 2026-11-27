package com.shrug.shrugtv.remotesettings

import android.content.Context
import android.provider.Settings
import com.shrug.shrugtv.BuildConfig
import com.shrug.shrugtv.session.AppState

/**
 * Fixed parameters for the GQL query of the playback token.
 */
class RemoteSettings(private val appContext: Context? = null) {

    companion object {
        private const val PLATFORM = "android"
        private const val PLAYER_BACKEND = "mediaplayer"
        private const val PLAYER_TYPE = "mobile"

        /**
         * Debug builds only: `adb shell settings put global shrugtv_adtest 1` asks for the playback token like the
         * website, which gets ads (docs/entwicklung.md, "Forcing ads"). A public settings read instead of the
         * hidden `SystemProperties` by reflection.
         */
        private const val AD_TEST_SETTING = "shrugtv_adtest"

        @JvmStatic
        fun load(context: Context) {
            AppState.remoteSettings = RemoteSettings(context.applicationContext)
        }
    }

    private fun adTestEnabled(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val c = appContext ?: return false
        return try {
            "1" == Settings.Global.getString(c.contentResolver, AD_TEST_SETTING)
        } catch (e: Exception) {
            false
        }
    }

    val platform: String
        get() = if (adTestEnabled()) "web" else PLATFORM

    val playerBackend: String
        get() = PLAYER_BACKEND

    val playerType: String
        get() = if (adTestEnabled()) "site" else PLAYER_TYPE

    /** No foreign token any more; callers ask anonymously. */
    val streamToken: String?
        get() = null

    val isLoadedFromServer: Boolean
        get() = false
}
