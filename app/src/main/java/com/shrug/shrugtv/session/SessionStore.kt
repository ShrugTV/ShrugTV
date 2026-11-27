package com.shrug.shrugtv.session

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive session state with [StateFlow] (stage 0.4).
 * Gradually replaces the static login fields in [AppState]; as long as old Java code still
 * [AppState] liest, haelt [SessionStore] beide synchron.
 */
@Immutable
data class Session(
    val accessToken: String = "",
    val userId: String = "",
    val userLogin: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val profileLoaded: Boolean = false,
    val loggedOut: Boolean = false,
) {
    val isLoggedIn: Boolean get() = accessToken.isNotEmpty() && !loggedOut
}

object SessionStore {
    /** The display name ("ReRedman") of the last profile, with the login it belongs to. */
    const val PREF_DISPLAY_NAME = "USER_DISPLAY_NAME"
    const val PREF_DISPLAY_LOGIN = "USER_DISPLAY_LOGIN"

    /** The kept display name if it belongs to [login]; another account's name never shows. */
    @JvmStatic
    fun keptDisplayName(login: String, keptName: String?, keptLogin: String?): String =
        if (login.isNotEmpty() && keptLogin.equals(login, ignoreCase = true)) keptName.orEmpty() else ""

    private val _state = MutableStateFlow(Session())
    val state: StateFlow<Session> = _state.asStateFlow()

    @JvmStatic
    fun loadFromPrefs(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("User", 0)
        val token = com.shrug.shrugtv.security.SecretStore.get(context, com.shrug.shrugtv.security.SecretStore.USER_FILE, com.shrug.shrugtv.security.SecretStore.TOKEN)
        val uid = prefs.getString("USER_ID", "") ?: AppState.userId ?: ""
        val login = prefs.getString("USER_NAME", "") ?: AppState.userLogin ?: ""
        val loggedOut = prefs.getBoolean("USER_LOGGED_OUT", false)
        AppState.accessToken = token
        if (uid.isNotEmpty()) AppState.userId = uid
        if (login.isNotEmpty()) AppState.userLogin = login
        AppState.userLoggedOut = loggedOut
        // Twitch logins are lower case; the display name with its capitals comes from the profile request seconds
        // later. The one kept from last time shows at once (the greeting of the start screen).
        val display = if (loggedOut) "" else keptDisplayName(login, prefs.getString(PREF_DISPLAY_NAME, null), prefs.getString(PREF_DISPLAY_LOGIN, null))
        if (display.isNotEmpty() && AppState.userDisplayName.isEmpty()) AppState.userDisplayName = display
        _state.value = _state.value.copy(
            displayName = _state.value.displayName.ifEmpty { display },
            accessToken = token,
            userId = uid,
            userLogin = login,
            loggedOut = loggedOut,
        )
    }

    @JvmStatic
    fun updateToken(token: String) {
        AppState.accessToken = token
        AppState.userLoggedOut = token.isEmpty()
        _state.value = _state.value.copy(
            accessToken = token,
            loggedOut = token.isEmpty(),
        )
    }

    @JvmStatic
    fun updateProfile(userId: String, login: String, displayName: String, avatarUrl: String) {
        AppState.userId = userId
        AppState.userLogin = login
        AppState.userDisplayName = displayName
        AppState.userProfileImageUrl = avatarUrl
        AppState.userProfileLoaded = true
        _state.value = _state.value.copy(
            userId = userId,
            userLogin = login,
            displayName = displayName,
            avatarUrl = avatarUrl,
            profileLoaded = true,
        )
    }

    @JvmStatic
    fun clear() {
        AppState.accessToken = ""
        AppState.userId = ""
        AppState.userLogin = ""
        AppState.userDisplayName = ""
        AppState.userProfileImageUrl = ""
        AppState.userProfileLoaded = false
        AppState.userLoggedOut = true
        _state.value = Session(loggedOut = true)
    }
}
