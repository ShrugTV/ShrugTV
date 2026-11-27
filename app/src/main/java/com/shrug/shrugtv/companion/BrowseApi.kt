package com.shrug.shrugtv.companion

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.shrug.shrugtv.api.helix.HelixApi
import com.shrug.shrugtv.stats.StatsRange
import com.shrug.shrugtv.stats.WatchStats
import com.shrug.shrugtv.stats.WatchStatsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.coroutines.resume

/**
 * The find tab of the phone page beyond the switcher lists: search with categories, the live
 * channels of a category, the recordings and clips of a channel, and a short view of the watch statistics.
 *
 * Every function answers null when Twitch (or the local store) did not answer or answered with an error; the server
 * sends that as an error with "Try again", never as an empty list (docs/ui_rules.md).
 */
internal object BrowseApi {

    /** The Twitch requests, replaceable in tests. Each one answers null for no answer or an error status. */
    interface Source {
        suspend fun searchChannels(context: Context, q: String): JsonObject?
        suspend fun searchCategories(context: Context, q: String): JsonObject?
        suspend fun streamsOfCategory(context: Context, gameId: Long): JsonObject?
        suspend fun users(context: Context, ids: List<String>): JsonObject?
        suspend fun videos(context: Context, userId: String): JsonObject?
        suspend fun clips(context: Context, userId: String): JsonObject?
    }

    /** The existing Helix helpers; the blocking ones run interruptibly, so a timeout of the server ends them. */
    object Helix : Source {
        private fun enc(q: String) = URLEncoder.encode(q, "UTF-8")
        override suspend fun searchChannels(context: Context, q: String) =
            runInterruptible(Dispatchers.IO) { HelixApi.searchChannels(context, enc(q), false) }
        override suspend fun searchCategories(context: Context, q: String) =
            runInterruptible(Dispatchers.IO) { HelixApi.searchCategories(context, enc(q)) }
        override suspend fun streamsOfCategory(context: Context, gameId: Long) =
            runInterruptible(Dispatchers.IO) { HelixApi.getStreamsByQuery(context, "game_id=$gameId&first=50") }
        override suspend fun users(context: Context, ids: List<String>) =
            runInterruptible(Dispatchers.IO) { HelixApi.getUsersByQuery(context, "?" + ids.take(100).joinToString("&") { "id=$it" }) }
        override suspend fun videos(context: Context, userId: String): JsonObject? = suspendCancellableCoroutine { cont ->
            HelixApi.getVideos(context, userId, "", "archive") { e, json -> if (cont.isActive) cont.resume(if (e == null) json else null) }
        }
        override suspend fun clips(context: Context, userId: String) =
            runInterruptible(Dispatchers.IO) { HelixApi.getClipsOfBroadcasterSync(context, userId, null, 30) }
    }

    @JvmStatic
    @Volatile
    var source: Source = Helix

    /** How many cards a list sends at most. */
    private const val MAX_ITEMS = 50
    private val NUMERIC_ID = Regex("^[0-9]{1,15}$")
    private val CLIP_SLUG = Regex("^[A-Za-z0-9_-]{5,120}$")

    /** The "data" array of a Helix answer; null for no answer or an error object. */
    private fun dataOf(j: JsonObject?): JsonArray? {
        if (j == null || j.has("error")) return null
        val d = j.get("data")
        return if (d == null || !d.isJsonArray) JsonArray() else d.asJsonArray
    }

    private fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.num(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble.toLong() }.getOrNull() } ?: 0L

    /** A Twitch image address with a size placeholder or a size in the file name, at the size the phone shows. */
    internal fun sized(url: String?, w: Int, h: Int): String? = url?.takeIf { it.startsWith("https://") }
        ?.replace("%{width}", "$w")?.replace("%{height}", "$h")?.replace("{width}", "$w")?.replace("{height}", "$h")
        ?.replace(Regex("""-\d+x\d+\.(jpg|jpeg|png)$"""), "-${w}x$h.$1")

    private val DURATION = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""")

    /** "1h2m3s" of a Helix video in seconds, 0 if it cannot be read. */
    internal fun durationSeconds(text: String?): Long {
        val m = DURATION.matchEntire(text ?: return 0L) ?: return 0L
        val (h, min, s) = m.destructured
        return (h.toLongOrNull() ?: 0L) * 3600 + (min.toLongOrNull() ?: 0L) * 60 + (s.toLongOrNull() ?: 0L)
    }

    // ------------------------------------------------------------------ search

    /** Raw search answer (channels and categories), kept by the server for a short while. */
    class Search(val channels: JsonArray, val categories: JsonArray)

    /** Channels and categories in parallel, like the TV search; null if either of them failed. */
    suspend fun search(context: Context, q: String): Search? = coroutineScope {
        val ch = async { dataOf(source.searchChannels(context, q)) }
        val cat = async { dataOf(source.searchCategories(context, q)) }
        val channels = ch.await()
        val categories = cat.await()
        if (channels == null || categories == null) null else Search(channels, categories)
    }

    /** The answer for the page: the channel cards as before plus the categories. */
    fun searchJson(s: Search, favs: Set<String>): JsonObject = JsonObject().apply {
        add("items", JsonArray().apply {
            for (o in s.channels.mapNotNull { it.obj() }) add(JsonObject().apply {
                val id = o.str("id")
                if (id != null) { addProperty("id", id); addProperty("favorite", id in favs) }
                addProperty("login", o.str("broadcaster_login"))
                addProperty("name", o.str("display_name"))
                addProperty("category", o.str("game_name"))
                addProperty("title", o.str("title"))
                addProperty("live", o.get("is_live")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
                addProperty("avatar", o.str("thumbnail_url"))
            })
        })
        add("categories", JsonArray().apply {
            for (o in s.categories.mapNotNull { it.obj() }) {
                val id = o.str("id")?.takeIf { NUMERIC_ID.matches(it) } ?: continue
                add(JsonObject().apply {
                    addProperty("id", id)
                    addProperty("name", o.str("name"))
                    addProperty("image", sized(o.str("box_art_url"), 144, 192))
                })
            }
        })
    }

    // ------------------------------------------------------------------ category, recordings, clips

    /** The live channels of a category, most viewers first, with their avatars when Twitch gives them. */
    suspend fun category(context: Context, gameId: Long, favs: Set<String>): JsonObject? {
        val streams = dataOf(source.streamsOfCategory(context, gameId)) ?: return null
        val rows = streams.mapNotNull { it.obj() }
            .filter { !it.str("user_login").isNullOrEmpty() }
            .sortedByDescending { it.num("viewer_count") }.take(MAX_ITEMS)
        // The avatars are a nicety: without them the card shows the preview picture
        val avatars = HashMap<String, String>()
        val ids = rows.mapNotNull { it.str("user_id") }
        if (ids.isNotEmpty()) {
            val users = try { source.users(context, ids) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            dataOf(users)?.mapNotNull { it.obj() }?.forEach { u ->
                val id = u.str("id") ?: return@forEach
                u.str("profile_image_url")?.let { avatars[id] = it }
            }
        }
        return JsonObject().apply {
            addProperty("name", rows.firstNotNullOfOrNull { it.str("game_name") } ?: "")
            add("items", JsonArray().apply {
                for (o in rows) add(JsonObject().apply {
                    val id = o.str("user_id")
                    if (id != null) { addProperty("id", id); addProperty("favorite", id in favs) }
                    addProperty("login", o.str("user_login"))
                    addProperty("name", o.str("user_name")?.ifEmpty { null } ?: o.str("user_login"))
                    addProperty("category", o.str("game_name"))
                    addProperty("title", o.str("title"))
                    addProperty("viewers", o.num("viewer_count"))
                    addProperty("image", sized(o.str("thumbnail_url"), 320, 180))
                    id?.let { avatars[it] }?.let { addProperty("avatar", it) }
                })
            })
        }
    }

    /** The past broadcasts of a channel, newest first; the page starts one with {"vod_id": …} by the "watch" command. */
    suspend fun videos(context: Context, userId: String): JsonObject? {
        val data = dataOf(source.videos(context, userId)) ?: return null
        return JsonObject().apply {
            add("items", JsonArray().apply {
                for (o in data.mapNotNull { it.obj() }) {
                    val id = o.str("id")?.takeIf { NUMERIC_ID.matches(it) } ?: continue
                    add(JsonObject().apply {
                        addProperty("vod_id", id)
                        addProperty("title", o.str("title"))
                        addProperty("name", o.str("user_name"))
                        // The archive of a running broadcast has a placeholder picture; the card then shows none
                        addProperty("image", sized(o.str("thumbnail_url"), 320, 180)?.takeIf { "404" !in it })
                        addProperty("duration_s", durationSeconds(o.str("duration")))
                        addProperty("views", o.num("view_count"))
                        addProperty("date", o.str("published_at") ?: o.str("created_at"))
                    })
                    if (size() >= MAX_ITEMS) break
                }
            })
        }
    }

    /** The most viewed clips of a channel of all time; the page starts one with {"clip": …} by the "watch" command. */
    suspend fun clips(context: Context, userId: String): JsonObject? {
        val data = dataOf(source.clips(context, userId)) ?: return null
        return JsonObject().apply {
            add("items", JsonArray().apply {
                for (o in data.mapNotNull { it.obj() }) {
                    val slug = o.str("id")?.takeIf { CLIP_SLUG.matches(it) } ?: continue
                    add(JsonObject().apply {
                        addProperty("clip", slug)
                        addProperty("title", o.str("title"))
                        addProperty("name", o.str("broadcaster_name"))
                        addProperty("image", sized(o.str("thumbnail_url"), 480, 272))
                        addProperty("duration_s", o.num("duration").coerceAtLeast(1L))
                        addProperty("views", o.num("view_count"))
                        addProperty("date", o.str("created_at"))
                    })
                    if (size() >= MAX_ITEMS) break
                }
            })
        }
    }

    // ------------------------------------------------------------------ statistics

    /** Today and the last 7 days from the local store of the statistics page; null if it cannot be read. */
    suspend fun stats(context: Context, today: LocalDate = LocalDate.now()): JsonObject? = try {
        runInterruptible(Dispatchers.IO) {
            val (rows, oldest) = WatchStatsStore.load(context, StatsRange.WEEK, today)
            fun period(range: StatsRange) = WatchStats.compute(rows, range, today, oldest, top = 1).let { p ->
                JsonObject().apply {
                    addProperty("total_s", p.totalSec)
                    addProperty("sessions", p.sessions)
                    addProperty("live_s", p.liveSec)
                    addProperty("vod_s", p.vodSec)
                    p.topChannels.firstOrNull()?.let { addProperty("channel", it.name) }
                    p.topCategories.firstOrNull()?.let { addProperty("category", it.name) }
                }
            }
            JsonObject().apply {
                add("today", period(StatsRange.Rolling(1)))
                add("week", period(StatsRange.WEEK))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("Companion", "stats", e)
        null
    }
}
