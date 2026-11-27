package com.shrug.shrugtv.chat

import android.content.Context
import android.content.SharedPreferences
import android.util.TypedValue
import com.shrug.shrugtv.prefs.Prefs
import java.util.regex.Pattern

/**
 * Chat settings from `res/xml/settings.xml`, read once when a chat starts.
 *
 * Before this, the player built the chat without any of them: emote and badge size were 0 (emotes
 * stayed text, badges invisible), filters were off and "Show chat" was ignored.
 */
/** Where the chat goes (setting "Chat layout", docs/chat_plan.md phase 3). */
enum class ChatLayout(val value: String) {
    /** Video shrinks, chat next to it: nothing of the picture is hidden. Default since 2026-10-31. */
    SIDE_BY_SIDE("side_by_side"),
    /** Chat panel over the video, background opacity from the settings. */
    OVERLAY("overlay"),
    /** No panel, only the latest messages with a shadow; older ones disappear. */
    TRANSPARENT("transparent");

    companion object {
        @JvmStatic
        fun of(value: String?): ChatLayout = values().firstOrNull { it.value == value } ?: SIDE_BY_SIDE
    }
}

/**
 * Chat features taken over from the 7TV browser extension (2026-11-01): which emote providers load,
 * zero-width emotes, badges, 7TV paints/badges/personal emotes, chat history on start.
 */
data class ChatExtras(
    val providers: Set<com.shrug.shrugtv.chat.emote.Emote.Provider> = com.shrug.shrugtv.chat.emote.Emote.Provider.values().toSet(),
    val zeroWidth: Boolean = true,
    val showBadges: Boolean = true,
    val sevenTvPaints: Boolean = true,
    val sevenTvBadges: Boolean = true,
    val sevenTvPersonal: Boolean = true,
    val recentMessages: Boolean = false,
    /** `pref_chat_reply_chars`: characters of the answered message (with its author) above a reply. */
    val replyChars: Int = ChatPrefs.REPLY_CHARS_DEFAULT
) {
    val sevenTvCosmetics: Boolean get() = sevenTvPaints || sevenTvBadges || sevenTvPersonal
}

/** Look of the chat list: alternating rows, separator lines, compact spacing. */
data class ChatLook(
    val alternate: Boolean = false,
    val separators: Boolean = false,
    val compact: Boolean = false,
    /** `pref_show_timestamp`: time in front of every message (wall clock live, position in a recording). */
    val timestamps: Boolean = false,
    /** `pref_chat_deleted_style`: what a deleted message looks like. */
    val deletedStyle: DeletedStyle = DeletedStyle.STRIKE,
    /** `pref_chat_animation`: how new messages come in. */
    val animation: ChatAnimation = ChatAnimation.SLIDE,
    /** `pref_chat_text_shadow`: "auto" (only with the transparent layout), "on" or "off". */
    val textShadow: String = "auto"
)

/**
 * How a new chat message comes in: [addMs] fades the new line in, [moveMs] lets the older
 * lines slide up instead of jumping. OFF is the old hard cut.
 */
enum class ChatAnimation(val value: String, val addMs: Long, val moveMs: Long) {
    OFF("off", 0L, 0L), FADE("fade", 220L, 0L), SLIDE("slide", 180L, 180L), SLOW("slow", 320L, 320L);

    companion object {
        @JvmStatic
        fun of(value: String?): ChatAnimation = values().firstOrNull { it.value == value } ?: SLIDE
    }
}

/** Deleted chat messages: struck through (still readable), grey, content hidden or as sent. */
enum class DeletedStyle(val value: String) {
    STRIKE("strike"), GREY("grey"), HIDE("hide"), NORMAL("normal");

    companion object {
        @JvmStatic
        fun of(value: String?): DeletedStyle = values().firstOrNull { it.value == value } ?: STRIKE
    }
}

data class ChatPrefs(
    val joinChat: Boolean,
    val showChat: Boolean,
    val textSizeSp: Float,
    val emoteSizePx: Int,
    val badgeSizePx: Int,
    val animatedEmotes: Boolean,
    val messageDelayMs: Int,
    /** `prefs_chat_delay` = "auto": the delay follows the picture's latency. */
    val autoDelay: Boolean = false,
    val showPrimeBadge: Boolean,
    val filterSettings: ChatFilterSettings,
    val highlights: ChatHighlights = ChatHighlights.NONE,
    /** Chat of recordings (VODs) next to the picture. */
    val vodChat: Boolean = true,
    val layout: ChatLayout = ChatLayout.SIDE_BY_SIDE,
    /** Panel width in dp. */
    val widthDp: Float = 225f,
    /** Panel background opacity in overlay mode, 0..1. */
    val backgroundAlpha: Float = 0.6f,
    /** Panel on the left instead of the right. */
    val onLeft: Boolean = false,
    val extras: ChatExtras = ChatExtras(),
    val look: ChatLook = ChatLook()
) {
    companion object {
        @JvmStatic
        fun from(context: Context): ChatPrefs {
            val prefs = Prefs.of(context)
            // px per sp (scaledDensity is deprecated; applyDimension also honours non-linear font scaling)
            val pxPerSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, context.resources.displayMetrics)
            migrateDelay(prefs)
            com.shrug.shrugtv.prefs.PrefsMigration.run(prefs)
            return from(prefs, pxPerSp)
        }

        const val AUTO_DELAY = "auto"

        const val REPLY_CHARS_DEFAULT = 78
        const val REPLY_CHARS_MIN = 20
        const val REPLY_CHARS_MAX = 300

        /** The setting `pref_chat_reply_chars` as a number; anything else is the default, limits are kept. */
        @JvmStatic
        fun replyCharsOf(value: String?): Int =
            (value?.trim()?.toIntOrNull() ?: REPLY_CHARS_DEFAULT).coerceIn(REPLY_CHARS_MIN, REPLY_CHARS_MAX)

        /**
         * Once: the old default "no delay" becomes "automatic". Whoever turns it
         * off again afterwards keeps that.
         */
        private fun migrateDelay(prefs: SharedPreferences) {
            if (prefs.getBoolean("prefs_chat_delay_auto_migrated", false)) return
            val e = prefs.edit()
            // Unset counts as the old default "0" here, on purpose: that is what the migration is about
            if (prefs.getString(Prefs.chatDelay.key, "0") == "0") Prefs.chatDelay.put(e, AUTO_DELAY)
            e.putBoolean("prefs_chat_delay_auto_migrated", true).apply()
        }

        @JvmStatic
        fun from(prefs: SharedPreferences, scaledDensity: Float): ChatPrefs {
            val textSp = textSizeSp(Prefs.chatFontSize.text(prefs))
            val textPx = textSp * scaledDensity
            return ChatPrefs(
                joinChat = Prefs.joinChat.get(prefs),
                showChat = Prefs.showChat.get(prefs),
                vodChat = Prefs.chatVod.get(prefs),
                textSizeSp = textSp,
                emoteSizePx = emoteSizePx(Prefs.chatEmoteSize.text(prefs), textPx, scaledDensity),
                badgeSizePx = (textPx * 1.15f).toInt().coerceAtLeast(1),
                animatedEmotes = Prefs.chatUsegifs.get(prefs),
                messageDelayMs = Prefs.chatDelay.get(prefs).toIntOrNull()?.coerceAtLeast(0) ?: 0,
                autoDelay = Prefs.chatDelay.get(prefs) == AUTO_DELAY,
                showPrimeBadge = Prefs.showPrime.get(prefs),
                filterSettings = ChatFilterSettings.Builder()
                    .blockedUsers(splitList(Prefs.filterUsers.get(prefs)).toTypedArray().takeIf { it.isNotEmpty() })
                    .blockedWordsRegex(wordsRegex(splitList(Prefs.filterWords.get(prefs))))
                    .showSubMessages(Prefs.showSubMessages.get(prefs))
                    .showGiftedSubs(Prefs.showGiftedsubs.get(prefs))
                    .filterBots(Prefs.filterBots.get(prefs))
                    // One switch for both bot filters since  (PrefsMigration)
                    .filterModBots(Prefs.filterBots.get(prefs))
                    .filterCommands(Prefs.filterCommands.get(prefs))
                    .build(),
                highlights = ChatHighlights(
                    words = splitList(Prefs.chatHighlightWords.get(prefs)),
                    users = splitList(Prefs.chatHighlightUsers.get(prefs)),
                    roles = buildSet {
                        if (Prefs.chatHighlightBroadcaster.get(prefs)) add(ChatHighlights.Role.BROADCASTER)
                        if (Prefs.chatHighlightMods.get(prefs)) add(ChatHighlights.Role.MOD)
                        if (Prefs.chatHighlightVips.get(prefs)) add(ChatHighlights.Role.VIP)
                    },
                    color = ChatHighlights.colorOf(Prefs.chatHighlightColor.get(prefs))
                ),
                extras = ChatExtras(
                    providers = buildSet {
                        add(com.shrug.shrugtv.chat.emote.Emote.Provider.TWITCH)
                        if (Prefs.chatEmotes7tv.get(prefs)) add(com.shrug.shrugtv.chat.emote.Emote.Provider.SEVEN_TV)
                        if (Prefs.chatEmotesBttv.get(prefs)) add(com.shrug.shrugtv.chat.emote.Emote.Provider.BTTV)
                        if (Prefs.chatEmotesFfz.get(prefs)) add(com.shrug.shrugtv.chat.emote.Emote.Provider.FFZ)
                    },
                    zeroWidth = Prefs.chatZeroWidth.get(prefs),
                    showBadges = Prefs.chatShowBadges.get(prefs),
                    sevenTvPaints = Prefs.chat7tvPaints.get(prefs),
                    sevenTvBadges = Prefs.chat7tvBadges.get(prefs),
                    sevenTvPersonal = Prefs.chat7tvPersonal.get(prefs),
                    recentMessages = Prefs.chatRecentMessages.get(prefs),
                    replyChars = // The TV slider stores a number, the browser a text: read either (getString crashed the player on the number)
                    replyCharsOf(Prefs.chatReplyChars.raw(prefs))
                ),
                look = ChatLook(
                    alternate = Prefs.chatAlternate.get(prefs),
                    separators = Prefs.chatSeparators.get(prefs),
                    compact = Prefs.chatCompact.get(prefs),
                    timestamps = Prefs.showTimestamp.get(prefs),
                    deletedStyle = DeletedStyle.of(Prefs.chatDeletedStyle.get(prefs)),
                    animation = ChatAnimation.of(Prefs.chatAnimation.get(prefs)),
                    textShadow = Prefs.chatTextShadow.get(prefs)
                ),
                layout = ChatLayout.of(Prefs.chatLayout.get(prefs)),
                widthDp = widthDp(Prefs.chatWidth.text(prefs), textSp),
                backgroundAlpha = Prefs.chatBackgroundOpacity.get(prefs).coerceIn(0, 9) / 10f,
                // value_chat_position: 4-6 are the left side
                onLeft = Prefs.chatPosition.get(prefs) in 4..6
            )
        }

        /** `value_chat_font_size` (6 = 10 sp … 0 = 16 sp, 7 = 17 sp); default "2" = 14 sp. */
        @JvmStatic
        fun textSizeSp(value: String?): Float {
            val v = value?.toIntOrNull() ?: return 14f
            return when {
                // value_chat_font_size: "6" … "0" = 10 … 16 sp, "7" … "12" = 17 … 22 sp
                v in 7..12 -> 10f + v
                v in 0..6 -> 16f - v
                else -> 14f
            }
        }

        /**
         * `value_emote_size`: "0" normal, "1" bigger (default), otherwise a pixel height meant for a
         * 1080p screen at density 2 ("40" next to the default 14 sp font), scaled to this screen.
         * Never 0: Glide cannot load at height 0 and the renderer would draw 0-pixel spans.
         */
        @JvmStatic
        fun emoteSizePx(value: String?, textPx: Float, density: Float): Int {
            val v = value?.toIntOrNull() ?: 1
            val px = when (v) {
                1 -> textPx * 1.8f
                0 -> textPx * 1.4f
                else -> v * density / 2f
            }
            return px.toInt().coerceAtLeast(8)
        }

        /**
         * `value_chat_width`: "0" = scales with the font (225 dp at the default 14 sp, as the setting
         * says), otherwise the width in dp.
         */
        @JvmStatic
        fun widthDp(value: String?, textSp: Float): Float {
            val v = value?.toIntOrNull() ?: 0
            return if (v <= 0) textSp * 16f else v.toFloat().coerceIn(100f, 600f)
        }

        /** Comma-separated list from an EditTextPreference, trimmed, empty entries dropped. */
        @JvmStatic
        fun splitList(value: String?): List<String> =
            value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        /**
         * Whole-word, case-insensitive pattern for the blocked words (compiled with CASE_INSENSITIVE
         * in [ChatFilterSettings]); words are quoted, so "c++" or "?" are no regex errors.
         */
        @JvmStatic
        fun wordsRegex(words: List<String>): String? {
            if (words.isEmpty()) return null
            return words.joinToString("|", prefix = "(?<![\\p{L}\\p{N}_])(?:", postfix = ")(?![\\p{L}\\p{N}_])") {
                Pattern.quote(it)
            }
        }
    }
}
