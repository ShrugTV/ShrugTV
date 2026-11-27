package com.shrug.shrugtv.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Every setting of the default SharedPreferences with key, stored type and default.
 *
 * The first part mirrors `res/xml/settings.xml` in its order (comments name the screen); a new
 * setting gets its entry in the XML and here, and `PrefsDefaultsTest` fails when the two defaults
 * differ or one side is missing. Keys the settings screen does not show are in [CODE_ONLY].
 *
 * Read with `Prefs.joinChat.get(prefs)` or `Prefs.joinChat.get(context)`; write with
 * `Prefs.joinChat.put(editor, value)` or `Prefs.joinChat.set(context, value)`.
 */
object Prefs {

    @JvmStatic
    fun of(context: Context): SharedPreferences = defaultPrefs(context)

    // pref_general_settings
    val startLastChannel = BoolPref("pref_start_last_channel", false)
    val splashStyle = StringPref("pref_splash_style", "full")
    val splashGreeting = BoolPref("pref_splash_greeting", true)
    val splashMinMs = IntListPref("pref_splash_min_ms", 600)

    // pref_mainscreen_row_visibility ("Rows": one group per home screen row)
    val showFollowed = BoolPref("pref_show_Followed", true)
    val showLiveStreamHistory = BoolPref("pref_show_LiveStreamHistory", true)
    val showVodHistory = BoolPref("pref_show_VodHistory", true)
    val showFollowedChannels = BoolPref("pref_show_FollowedChannels", true)
    val clipsRow = BoolPref("pref_clips_row", true)
    val clipsSurpriseRow = BoolPref("pref_clips_surprise_row", false)
    val showFollowedGames = BoolPref("pref_show_FollowedGames", true)
    val showPastBroadcasts = BoolPref("pref_show_PastBroadcasts", true)
    val showTopGames = BoolPref("pref_show_top_games", true)
    val showTopStreamers = BoolPref("pref_show_top_streamers", true)
    val vodsSeen = StringPref("pref_vods_seen", "dim")

    // pref_clips_screen
    val clipsLayout = StringPref("pref_clips_layout", "fine")
    val clipsSource = StringPref("pref_clips_source", "followed")
    val clipsPeriod = StringPref("pref_clips_period", "week")
    val clipsSort = StringPref("pref_clips_sort", "views")
    val clipsLanguage = StringPref("pref_clips_language", "all")
    val clipsSeen = StringPref("pref_clips_seen", "dim")
    val clipsShowTitle = BoolPref("pref_clips_show_title", true)
    val clipsAutoplay = BoolPref("pref_clips_autoplay", true)
    val clipsQueueSeen = StringPref("pref_clips_queue_seen", "skip")
    val clipsCount = IntListPref("pref_clips_count", 20)
    /** The focused clip plays muted on the page instead of its picture; off until measured on the Fire TV. */
    val clipsLivePreview = BoolPref("pref_clips_live_preview", false)

    // pref_language_screen
    val showLanguageRow = BoolPref("pref_show_language_row", false)
    val showLanguageRowGames = BoolPref("pref_show_language_row_games", false)
    val languageRowAsPrimary = BoolPref("pref_language_row_as_primary", false)
    val languageRowCode = StringPref("prefs_languagerow", "en")

    // pref_filtering_main
    val filteringUseFiltering = BoolPref("pref_filtering_use_filtering", false)
    val filteringChannels = StringPref("pref_filtering_channels", "")
    val filteringCategories = StringPref("pref_filtering_categories", "")
    val filteringReruns = BoolPref("pref_filtering_reruns", false)

    // pref_mainscreen_autorefresh
    val autoRefresh = BoolPref("pref_autorefresh_parent", true)
    val autoRefreshMinutes = IntListPref("prefs_autorefresh_minutes", 20)
    val videoCardSize = StringPref("prefs_video_card_size", "normal")
    val favoritesInFrontOfFollowed = BoolPref("pref_favorites_in_front_of_followed", true)
    val showStreamPreview = BoolPref("pref_show_stream_preview", true)
    val streamPreviewQuality = IntListPref("pref_stream_preview_quality", 0)
    val watchStats = BoolPref("pref_watch_stats", true)
    val watchStatsCard = BoolPref("pref_watch_stats_card", true)

    // pref_stats_screen
    val statsStyle = StringPref("pref_stats_style", "tiles")
    val statsDefaultPeriod = StringPref("pref_stats_default_period", "week")
    val statsTimeFormat = StringPref("pref_stats_time_format", "hm")
    val statsCompare = BoolPref("pref_stats_compare", true)
    val statsShowTiles = BoolPref("pref_stats_show_tiles", true)
    val statsShowActivity = BoolPref("pref_stats_show_activity", true)
    val statsShowHours = BoolPref("pref_stats_show_hours", true)
    val statsShowChannels = BoolPref("pref_stats_show_channels", true)
    val statsShowCategories = BoolPref("pref_stats_show_categories", true)
    val statsShowLive = BoolPref("pref_stats_show_live", true)
    val statsShowClips = BoolPref("pref_stats_show_clips", true)
    val statsTopCount = IntListPref("pref_stats_top_count", 5)
    val statsRankBy = StringPref("pref_stats_rank_by", "time")
    val statsActivityStyle = StringPref("pref_stats_activity_style", "bars")
    val statsHighlightBusiest = BoolPref("pref_stats_highlight_busiest", true)
    val statsHoursStyle = StringPref("pref_stats_hours_style", "hours")
    val statsLiveStyle = StringPref("pref_stats_live_style", "ring")
    val statsKeepMonths = IntListPref("pref_stats_keep_months", 25)

    // pref_appearance_screen
    val skin = StringPref("pref_skin", "shrugtv")
    val themeAccent = StringPref("pref_theme_accent", "skin")
    val themeBackground = StringPref("pref_theme_background", "skin")
    val themeSurface = StringPref("pref_theme_surface", "skin")
    val themeText = StringPref("pref_theme_text", "skin")
    val themeTextMuted = StringPref("pref_theme_text_muted", "skin")
    val themeLive = StringPref("pref_theme_live", "skin")
    val themeFocus = StringPref("pref_theme_focus", "skin")
    val themeCorners = StringPref("pref_theme_corners", "skin")
    val themeFocusStyle = StringPref("pref_theme_focus_style", "skin")
    val themeFocusScale = StringPref("pref_theme_focus_scale", "skin")
    val themeBorder = StringPref("pref_theme_border", "skin")
    val themeGlass = StringPref("pref_theme_glass", "skin")
    val themeHeroDim = StringPref("pref_theme_hero_dim", "skin")
    val themeBackgroundStyle = StringPref("pref_theme_background_style", "skin")
    val themeFont = StringPref("pref_theme_font", "skin")
    val themeTextScale = StringPref("pref_theme_text_scale", "skin")
    val themeAnimations = StringPref("pref_theme_animations", "skin")
    val appLanguage = StringPref("pref_app_language", "system")

    // pref_stream_settings
    val videoQualityV3 = IntListPref("prefs_video_quality_v3", 0)
    val vodVideoQualityV3 = IntListPref("prefs_VOD_video_quality_v3", 0)
    val lowLatency = BoolPref("pref_low_latency", true)
    val matchFrameRate = BoolPref("pref_match_frame_rate", false)
    val vodChapters = BoolPref("pref_vod_chapters", true)
    val playerVodNext = BoolPref("pref_player_vod_next", true)
    val playerControlsTimeout = IntListPref("pref_player_controls_timeout", 8)
    val playerInfoCorner = BoolPref("pref_player_info_corner", true)
    val playerBarLabels = StringPref("pref_player_bar_labels", "highlighted")
    /** The "Highlights" button in the player bar of a live stream: its top clips since the start. */
    val playerHighlights = BoolPref("pref_player_highlights", true)
    val streamDoubleTapBack = BoolPref("pref_stream_double_tap_back", false)

    // pref_switcher_screen
    val switcherUpdown = BoolPref("pref_switcher_updown", true)
    val switcherListFollowed = BoolPref("pref_switcher_list_followed", true)
    val switcherListSameGame = BoolPref("pref_switcher_list_same_game", true)
    val switcherListSameGameLanguage = BoolPref("pref_switcher_list_same_game_language", true)
    val switcherListHistory = BoolPref("pref_switcher_list_history", true)
    val switcherListTopStreams = BoolPref("pref_switcher_list_top_streams", true)
    val switcherListTopLanguage = BoolPref("pref_switcher_list_top_language", true)

    // pref_ClockScreen
    val clockShowclock = BoolPref("pref_clock_showclock", true)
    val clockTimeFormat = StringPref("pref_clock_time_format", "H:mm")
    val clockDateFormat = StringPref("pref_clock_date_format", "yyyy-MM-dd")
    val clockShowlocal = BoolPref("pref_clock_showlocal", true)
    val clockShowDateV2 = BoolPref("pref_clock_show_date_v2", false)
    val playerBackToLive = BoolPref("pref_player_back_to_live", true)
    val liveRewind = BoolPref("pref_live_rewind", true)
    val playerSelfHeal = BoolPref("pref_player_self_heal", true)
    val playerReplay = BoolPref("pref_player_replay", true)
    val playerReplaySeconds = IntListPref("pref_player_replay_seconds", 30)
    val playerEndOfStream = StringPref("pref_player_end_of_stream", "countdown")
    val streamShowIncomingRaidNotification = BoolPref("pref_stream_show_incoming_raid_notification", true)
    val playerAudioOnlyScreen = BoolPref("pref_player_audio_only_screen", true)
    val playerBurnIn = BoolPref("pref_player_burn_in", false)
    val playerRestAfter = IntListPref("pref_player_rest_after", 5)

    // pref_chat_screen
    val joinChat = BoolPref("pref_join_chat", true)
    val showChat = BoolPref("pref_show_chat", true)
    val chatVod = BoolPref("pref_chat_vod", true)
    val chatDelay = StringPref("prefs_chat_delay", "auto")
    val chatUsegifs = BoolPref("pref_chat_usegifs", true)
    val chatLayout = StringPref("pref_chat_layout", "side_by_side")
    val chatFontSize = IntListPref("prefs_chat_font_size", 2)
    val chatWidth = IntListPref("pref_chat_width", 0)
    val chatHeight = IntListPref("pref_chat_height", 100)
    val chatVertical = StringPref("pref_chat_vertical", "bottom")
    val chatPosition = IntListPref("pref_chat_position", 2)
    val chatEmoteSize = IntListPref("pref_chat_emote_size", 1)
    val chatBackgroundOpacity = IntListPref("pref_chat_background_opacity", 6)
    val showSubMessages = BoolPref("pref_show_sub_messages", false)
    val showPrime = BoolPref("pref_show_prime", false)
    val showGiftedsubs = BoolPref("pref_show_giftedsubs", true)
    val showTimestamp = BoolPref("pref_show_timestamp", false)
    val chatDeletedStyle = StringPref("pref_chat_deleted_style", "strike")
    val chatNameColors = StringPref("pref_chat_name_colors", "readable")
    val chatAlternate = BoolPref("pref_chat_alternate", false)
    val chatSeparators = BoolPref("pref_chat_separators", false)
    val chatCompact = BoolPref("pref_chat_compact", false)
    val chatAnimation = StringPref("pref_chat_animation", "slide")
    val chatTextShadow = StringPref("pref_chat_text_shadow", "auto")
    val chatMergeRepeats = IntListPref("pref_chat_merge_repeats", 0)
    val chatReplyChars = IntPref("pref_chat_reply_chars", 78)
    val chatShowBadges = BoolPref("pref_chat_show_badges", true)
    val chatEmotes7tv = BoolPref("pref_chat_emotes_7tv", true)
    val chatEmotesBttv = BoolPref("pref_chat_emotes_bttv", true)
    val chatEmotesFfz = BoolPref("pref_chat_emotes_ffz", true)
    val chatZeroWidth = BoolPref("pref_chat_zero_width", true)
    val chat7tvPaints = BoolPref("pref_chat_7tv_paints", true)
    val chat7tvBadges = BoolPref("pref_chat_7tv_badges", true)
    val chat7tvPersonal = BoolPref("pref_chat_7tv_personal", true)
    val chatHighlightWords = StringPref("pref_chat_highlight_words", "")
    val chatHighlightUsers = StringPref("pref_chat_highlight_users", "")
    val chatHighlightBroadcaster = BoolPref("pref_chat_highlight_broadcaster", false)
    val chatHighlightMods = BoolPref("pref_chat_highlight_mods", false)
    val chatHighlightVips = BoolPref("pref_chat_highlight_vips", false)
    val chatHighlightColor = StringPref("pref_chat_highlight_color", "purple")
    val chatRecentMessages = BoolPref("pref_chat_recent_messages", false)

    // pref_filter_screen
    val filterUsers = StringPref("pref_filter_users", "")
    val filterWords = StringPref("pref_filter_words", "")
    /** Known bots, every command and (since ) moderators named "…bot"; [PrefsMigration] folds the old second switch in. */
    val filterBots = BoolPref("pref_filter_bots", false)
    val filterCommands = BoolPref("pref_filter_commands", false)

    // pref_Notifications
    val notificationPlayerCard = BoolPref("pref_notification_player_card", true)
    val notificationOn = BoolPref("pref_notification_on", false)
    val notificationForegroundFavoriteOnly = BoolPref("pref_notification_foreground_favorite_only", false)
    val notificationType = IntListPref("prefs_notification_type", 0)
    val notificationFontSize = IntListPref("prefs_notification_font_size", 20)
    val notificationPosition = IntListPref("prefs_notification_position", 1)
    val notificationDuration = IntListPref("pref_notification_duration", 3000)
    val notificationAnimation = IntListPref("pref_notification_animation", 1)
    val notificationQuiet = BoolPref("pref_notification_quiet", false)
    val notificationQuietFrom = IntListPref("pref_notification_quiet_from", 22)
    val notificationQuietTo = IntListPref("pref_notification_quiet_to", 7)

    // pref_mqtt_screen
    val mqttEnabled = BoolPref("pref_mqtt_enabled", false)
    val mqttUrl = StringPref("pref_mqtt_url", "")
    val mqttUser = StringPref("pref_mqtt_user", "")
    /** Only the input field or MqttSettings.MASK; the password itself lives in SecretStore. */
    val mqttPassword = StringPref("pref_mqtt_password", "")
    val mqttDeviceName = StringPref("pref_mqtt_device_name", "")
    val mqttInterval = IntListPref("pref_mqtt_interval", 60)

    // pref_mqtt_advanced
    val mqttTopicPrefix = StringPref("pref_mqtt_topic_prefix", "shrugtv")
    val mqttDiscoveryPrefix = StringPref("pref_mqtt_discovery_prefix", "homeassistant")
    val mqttEntitiesInfo = BoolPref("pref_mqtt_entities_info", true)
    val mqttEntitiesAvatar = BoolPref("pref_mqtt_entities_avatar", true)
    val mqttEntitiesPreview = BoolPref("pref_mqtt_entities_preview", true)
    val mqttEntitiesEvents = BoolPref("pref_mqtt_entities_events", true)
    val mqttEntitiesFollowed = BoolPref("pref_mqtt_entities_followed", false)
    val mqttCommands = BoolPref("pref_mqtt_commands", true)

    // pref_companion_screen
    val companionEnabled = BoolPref("pref_companion_enabled", false)
    val companionRequirePin = BoolPref("pref_companion_require_pin", false)
    val companionRemember = BoolPref("pref_companion_remember", true)
    val companionChat = BoolPref("pref_companion_chat", true)
    val companionSettings = BoolPref("pref_companion_settings", true)
    val companionSkin = BoolPref("pref_companion_skin", true)
    val companionLog = BoolPref("pref_companion_log", true)

    // pref_ProxyServer
    val proxyServerName = IntListPref("pref_proxy_server_name", 0)
    val proxyUrl = StringPref("pref_proxy_url", "")

    // pref_developer

    // pref_app_info
    val updateCheck = BoolPref("pref_update_check", false)
    val updateInstall = BoolPref("pref_update_install", false)
    val showAdvanced = BoolPref("pref_show_advanced", false)

    // Not in settings.xml: an action on the settings screen switches it (SettingsActions.developerMode)
    val developerMode = BoolPref("pref_developer_mode", false)

    /** Settings that exist only in code; everything else must match settings.xml. */
    val CODE_ONLY: List<Pref<*>> = listOf(developerMode)

    /** Every descriptor that mirrors settings.xml, in its order. */
    val FROM_XML: List<Pref<*>> = listOf(
        startLastChannel,
        splashStyle,
        splashGreeting,
        splashMinMs,
        showFollowed,
        showLiveStreamHistory,
        showVodHistory,
        showFollowedChannels,
        clipsRow,
        clipsSurpriseRow,
        showFollowedGames,
        showPastBroadcasts,
        showTopGames,
        showTopStreamers,
        vodsSeen,
        clipsLayout,
        clipsSource,
        clipsPeriod,
        clipsSort,
        clipsLanguage,
        clipsSeen,
        clipsShowTitle,
        clipsAutoplay,
        clipsQueueSeen,
        clipsCount,
        clipsLivePreview,
        showLanguageRow,
        showLanguageRowGames,
        languageRowAsPrimary,
        languageRowCode,
        filteringUseFiltering,
        filteringChannels,
        filteringCategories,
        filteringReruns,
        autoRefresh,
        autoRefreshMinutes,
        videoCardSize,
        favoritesInFrontOfFollowed,
        showStreamPreview,
        streamPreviewQuality,
        watchStats,
        watchStatsCard,
        statsStyle,
        statsDefaultPeriod,
        statsTimeFormat,
        statsCompare,
        statsShowTiles,
        statsShowActivity,
        statsShowHours,
        statsShowChannels,
        statsShowCategories,
        statsShowLive,
        statsShowClips,
        statsTopCount,
        statsRankBy,
        statsActivityStyle,
        statsHighlightBusiest,
        statsHoursStyle,
        statsLiveStyle,
        statsKeepMonths,
        skin,
        themeAccent,
        themeBackground,
        themeSurface,
        themeText,
        themeTextMuted,
        themeLive,
        themeFocus,
        themeCorners,
        themeFocusStyle,
        themeFocusScale,
        themeBorder,
        themeGlass,
        themeHeroDim,
        themeBackgroundStyle,
        themeFont,
        themeTextScale,
        themeAnimations,
        appLanguage,
        videoQualityV3,
        vodVideoQualityV3,
        lowLatency,
        matchFrameRate,
        vodChapters,
        playerVodNext,
        playerControlsTimeout,
        playerInfoCorner,
        playerBarLabels,
        playerHighlights,
        streamDoubleTapBack,
        switcherUpdown,
        switcherListFollowed,
        switcherListSameGame,
        switcherListSameGameLanguage,
        switcherListHistory,
        switcherListTopStreams,
        switcherListTopLanguage,
        clockShowclock,
        clockTimeFormat,
        clockDateFormat,
        clockShowlocal,
        clockShowDateV2,
        playerBackToLive,
        liveRewind,
        playerSelfHeal,
        playerReplay,
        playerReplaySeconds,
        playerEndOfStream,
        streamShowIncomingRaidNotification,
        playerAudioOnlyScreen,
        playerBurnIn,
        playerRestAfter,
        joinChat,
        showChat,
        chatVod,
        chatDelay,
        chatUsegifs,
        chatLayout,
        chatFontSize,
        chatWidth,
        chatHeight,
        chatVertical,
        chatPosition,
        chatEmoteSize,
        chatBackgroundOpacity,
        showSubMessages,
        showPrime,
        showGiftedsubs,
        showTimestamp,
        chatDeletedStyle,
        chatNameColors,
        chatAlternate,
        chatSeparators,
        chatCompact,
        chatAnimation,
        chatTextShadow,
        chatMergeRepeats,
        chatReplyChars,
        chatShowBadges,
        chatEmotes7tv,
        chatEmotesBttv,
        chatEmotesFfz,
        chatZeroWidth,
        chat7tvPaints,
        chat7tvBadges,
        chat7tvPersonal,
        chatHighlightWords,
        chatHighlightUsers,
        chatHighlightBroadcaster,
        chatHighlightMods,
        chatHighlightVips,
        chatHighlightColor,
        chatRecentMessages,
        filterUsers,
        filterWords,
        filterBots,
        filterCommands,
        notificationPlayerCard,
        notificationOn,
        notificationForegroundFavoriteOnly,
        notificationType,
        notificationFontSize,
        notificationPosition,
        notificationDuration,
        notificationAnimation,
        notificationQuiet,
        notificationQuietFrom,
        notificationQuietTo,
        mqttEnabled,
        mqttUrl,
        mqttUser,
        mqttPassword,
        mqttDeviceName,
        mqttInterval,
        mqttTopicPrefix,
        mqttDiscoveryPrefix,
        mqttEntitiesInfo,
        mqttEntitiesAvatar,
        mqttEntitiesPreview,
        mqttEntitiesEvents,
        mqttEntitiesFollowed,
        mqttCommands,
        companionEnabled,
        companionRequirePin,
        companionRemember,
        companionChat,
        companionSettings,
        companionSkin,
        companionLog,
        proxyServerName,
        proxyUrl,
        updateCheck,
        updateInstall,
        showAdvanced,
    )

    val ALL: List<Pref<*>> get() = FROM_XML + CODE_ONLY

    /** Descriptor by key, null for keys that are not settings (state, migration flags). */
    @JvmStatic
    fun byKey(key: String): Pref<*>? = ALL.firstOrNull { it.key == key }
}
