package com.shrug.shrugtv.companion

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.shrug.shrugtv.BuildConfig
import com.shrug.shrugtv.hub.HubCommandRouter
import com.shrug.shrugtv.hub.PlaybackStateHub
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.prefs.Prefs
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.discard
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import java.io.IOException

/**
 * HTTP + WebSocket server of the phone companion (docs/companion_plan.md). Serves the web app from
 * `assets/companion/`, the pairing, a small JSON API and `/ws` (state, chat and events to the phone,
 * keys and commands from it). Only private network addresses, only paired phones.
 *
 * Runs on Ktor with the CIO engine and Ktor WebSockets since  (before: NanoHTTPD 2.3.1, last
 * released 2016). Every request is a coroutine, so waiting for Twitch no longer blocks a thread per request.
 */
class CompanionServer(
    private val context: Context,
    private val port: Int,
    private val pairing: Pairing,
    /** Remembered devices (setting "Remember connected devices"); null in tests without storage. */
    private val devices: TrustedDevices? = null,
    /** A device was removed from the list: its open connection has to end. */
    private val onDevicesChanged: () -> Unit = {},
    private val onPhonePaired: (Pairing.Phone) -> Unit
) {

    companion object {
        private const val TAG = "Companion"
        private const val COOKIE = "shrugtv_sid"
        private const val MAX_SETTINGS_BODY = 64_000
        private const val MAX_TEXT = 200
        /** Largest WebSocket frame taken from a phone; its messages are cut to 2 000 characters anyway. */
        private const val MAX_FRAME = 64_000L
        /** Unread request bytes thrown away after an answer, so the next request on a kept-alive connection starts clean. */
        private const val MAX_DRAIN = 1_000_000L
        /** "No read timeout" ([start] with 0), as Ktor counts in seconds: a year. */
        private const val NO_TIMEOUT_S = 365 * 24 * 3600
        /** Inter for the page, cut down by tools/companion_assets.py. */
        internal val FONTS = setOf("inter-400.woff2", "inter-600.woff2", "inter-700.woff2")
        /** Searches that may wait for Twitch at the same time. */
        const val SEARCHES_AT_ONCE = 2
        /** Languages of the phone page (assets/companion/i18n); the server picks the browser's one, English is always loaded. */
        private val LANGS = listOf("en", "de", "es", "fr", "pt", "it", "pl", "tr", "ru", "ja", "ko", "zh")

        /** Only own files, no inline script, nobody may frame the page. Images come from Twitch's CDN. */
        private const val CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data: blob:; " +
            "connect-src 'self' ws:; manifest-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
        private const val LIVE_CACHE_MS = 20_000L
        private const val SEARCH_CACHE_MS = 30_000L
        private const val MIME_HTML = "text/html; charset=utf-8"

        /** The first language of an Accept-Language header the page has, "en" otherwise. */
        @JvmStatic
        fun pickLanguage(header: String?): String {
            for (part in (header ?: "").split(',')) {
                val tag = part.substringBefore(';').trim().lowercase()
                LANGS.firstOrNull { tag == it || tag.startsWith("$it-") }?.let { return it }
            }
            return "en"
        }

        private val IPV4_HOST = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /**
         * Host header against DNS rebinding: a page on an attacker's domain whose name
         * suddenly points to the TV sends that domain as Host. Only a private address as an IP literal
         * (a name cannot be rebound) and localhost are served, everything else is refused.
         */
        @JvmStatic
        fun isAllowedHost(host: String?): Boolean {
            if (host.isNullOrBlank()) return false
            val name = if (host.startsWith("[")) host.substringBefore("]").removePrefix("[")
            else host.substringBeforeLast(":", host)
            if (name.equals("localhost", ignoreCase = true)) return true
            return (name.matches(IPV4_HOST) || name.contains(':')) && isPrivateAddress(name)
        }

        /**
         * Read timeout of every connection. A silent connection from the LAN is closed after it
         * (Ktor CIO counts whole seconds, so it is rounded up); an open WebSocket is no longer an HTTP connection and
         * stays open because the server pings it every 20 s and the browser answers each ping with a pong within this
         * time. Tests set it short.
         */
        @JvmStatic
        var readTimeoutMs = 60_000

        /** How often the server pings each phone WebSocket; must stay well below [readTimeoutMs]. Tests set it short. */
        @JvmStatic
        var pingIntervalMs = 20_000L

        /** How long a channel list of the switcher may take for the phone page; tests set it short. */
        @JvmStatic
        var listTimeoutMs = 8_000L

        /** LAN, loopback and link-local only (IPv4 and IPv6). */
        @JvmStatic
        fun isPrivateAddress(ip: String?): Boolean {
            if (ip.isNullOrEmpty()) return false
            val a = ip.removePrefix("::ffff:")
            if (a.startsWith("10.") || a.startsWith("192.168.") || a.startsWith("127.") || a.startsWith("169.254.")) return true
            if (a.startsWith("172.")) return a.split(".").getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true
            val l = a.lowercase()
            return l == "::1" || l.startsWith("fe80:") || l.startsWith("fc") || l.startsWith("fd")
        }

        /** Java writes the IPv6 loopback in full; it counts as 127.0.0.1 like before (the old server did the same). */
        private fun normaliseIp(ip: String): String = if (ip == "0:0:0:0:0:0:0:1" || ip == "::1") "127.0.0.1" else ip
    }

    @Volatile private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * Starts listening on all addresses of the device (IPv4 and IPv6 like the old server; only private ones are
     * answered). [timeoutMs] is the read timeout of an HTTP connection, 0 for none. Throws when the port is taken.
     */
    fun start(timeoutMs: Int = readTimeoutMs) {
        val idleSeconds = if (timeoutMs <= 0) NO_TIMEOUT_S else (timeoutMs + 999) / 1000
        val config = serverConfig(applicationEnvironment()) {
            watchPaths = emptyList()
            developmentMode = false
            module {
                install(WebSockets) {
                    pingPeriodMillis = pingIntervalMs
                    // The pong has to come within the read timeout, as the old server read it as traffic
                    timeoutMillis = if (timeoutMs > 0) timeoutMs.toLong() else 60_000L
                    maxFrameSize = MAX_FRAME
                }
                routing {
                    webSocket("/ws") { PhoneSocket(this).run() }
                    route("{...}") { handle { serve(call) } }
                }
            }
        }
        server = embeddedServer(CIO, config) {
            connector {
                // The wildcard address of the platform ("::" with IPv6, else 0.0.0.0), as NanoHTTPD bound it
                host = java.net.InetSocketAddress(0).address.hostAddress ?: "0.0.0.0"
                port = this@CompanionServer.port
            }
            connectionIdleTimeoutSeconds = idleSeconds
            reuseAddress = true
        }.start(wait = false)
    }

    /** Stops listening and ends every open connection (WebSockets too). */
    fun stop() {
        server?.stop(gracePeriodMillis = 0L, timeoutMillis = 500L)
        server = null
    }

    private fun remember() = devices != null &&
        Prefs.companionRemember.get(context)

    /**
     * PIN asked for (default off): without it every device in the LAN may use the companion;
     * outside the LAN nothing changes, serveHttp and the WebSocket only accept private addresses.
     */
    private fun pinRequired() = Prefs.companionRequirePin.get(context)

    /** No PIN asked for, the cookie of a running session, or the key of a remembered device. */
    private fun authorised(cookieValue: String?, ip: String?): Boolean =
        !pinRequired() || pairing.isValid(cookieValue) || (remember() && devices?.find(cookieValue, ip) != null)

    /** After pairing: a remembered device gets a long-lived key, otherwise the session id (until the app stops). */
    private fun pairedCookie(phone: Pairing.Phone, ua: String, ip: String?): String =
        if (remember()) cookie(devices!!.add(ua, ip).second, persistent = true) else cookie(phone.sessionId)

    private fun chatAllowed() = Prefs.companionChat.get(context)

    // ------------------------------------------------------------------ requests and answers

    private fun ApplicationCall.header(name: String): String? = request.headers[name]
    private fun ApplicationCall.param(name: String): String? = request.queryParameters[name]
    private val ApplicationCall.ip: String get() = normaliseIp(request.local.remoteAddress)
    /** The cookie as sent: the session ids and device keys are URL-safe, nothing to decode. */
    private val ApplicationCall.sid: String? get() = request.cookies.rawCookies[COOKIE]

    /** An answer, built before it is sent: status, type, bytes and extra headers. */
    private class Reply(val status: HttpStatusCode, val type: String, val body: ByteArray) {
        val headers = ArrayList<Pair<String, String>>()
        fun addHeader(name: String, value: String) = apply { headers += name to value }
    }

    private suspend fun serve(call: ApplicationCall) {
        val reply = serveHttp(call)
        for ((name, value) in reply.headers) call.response.headers.append(name, value)
        call.respondBytes(reply.body, ContentType.parse(reply.type), reply.status)
        // A body that was not read (refused before) would otherwise be taken for the next request on the connection
        try { call.request.receiveChannel().discard(MAX_DRAIN) } catch (e: IOException) { Log.d(TAG, "drain: ${e.message}") }
    }

    private suspend fun serveHttp(call: ApplicationCall): Reply {
        if (!isPrivateAddress(call.ip)) return text(HttpStatusCode.Forbidden, "LAN only")
        if (!isAllowedHost(call.header("Host"))) return text(HttpStatusCode.Forbidden, "host")
        val method = call.request.httpMethod
        // Anything that changes something may change the state the page shows: build it anew
        if (method != HttpMethod.Get) stateCache = null
        val uri = try { call.request.path().decodeURLPart() } catch (e: Exception) { call.request.path() }.ifEmpty { "/" }
        val ua = call.header("User-Agent") ?: ""
        return try {
            when {
                uri.startsWith("/p/") -> {
                    val phone = pairing.redeemToken(uri.removePrefix("/p/"), ua)
                    if (phone != null) {
                        onPhonePaired(phone)
                        Reply(HttpStatusCode.SeeOther, MIME_HTML, ByteArray(0)).apply {
                            addHeader("Location", "/")
                            addHeader("Set-Cookie", pairedCookie(phone, ua, call.ip))
                        }
                    } else {
                        Reply(HttpStatusCode.SeeOther, MIME_HTML, ByteArray(0)).apply { addHeader("Location", "/?expired=1") }
                    }
                }
                uri == "/api/pin" && method == HttpMethod.Post -> {
                    val body = readBody(call)
                    val pin = try { JsonParser.parseString(body).asJsonObject.get("pin")?.asString } catch (e: Exception) { null }
                    val phone = pin?.let { pairing.redeemPin(it, ua, call.ip) }
                    if (phone != null) {
                        onPhonePaired(phone)
                        json(JsonObject().apply { addProperty("ok", true) }).apply { addHeader("Set-Cookie", pairedCookie(phone, ua, call.ip)) }
                    } else {
                        json(JsonObject().apply { addProperty("ok", false) }, HttpStatusCode.Unauthorized)
                    }
                }
                uri.startsWith("/api/") -> {
                    if (!authorised(call.sid, call.ip)) return json(JsonObject().apply { addProperty("error", "not paired") }, HttpStatusCode.Unauthorized)
                    api(uri, call).also { renewCookie(it, uri, call) }
                }
                else -> asset(uri, call)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "serve $uri", e)
            text(HttpStatusCode.InternalServerError, "error")
        }
    }

    /** A remembered device that opens the page gets its key renewed: it expires after 90 days without use, not after a year in total. */
    private fun renewCookie(response: Reply, uri: String, call: ApplicationCall) {
        if (uri != "/api/state" || !remember()) return
        val c = call.sid
        if (devices?.find(c, call.ip) != null) response.addHeader("Set-Cookie", cookie(c!!, persistent = true))
    }

    private suspend fun api(uri: String, call: ApplicationCall): Reply = when (uri) {
        "/api/state" -> json(stateJson())
        "/api/settings", "/api/settings/action", "/api/mqtt", "/api/design" -> settings(uri, call)
        "/api/devices", "/api/devices/remove" -> devicesApi(uri, call)
        "/api/log" -> log(call)
        "/api/update", "/api/update/check", "/api/update/install" -> update(uri, call)
        "/api/theme" -> json(SkinApi.theme(context))
        "/api/live" -> json(JsonObject().apply { add("items", liveCards()) })
        "/api/lists" -> lists(call)
        "/api/favorites", "/api/favorite" -> favorites(uri, call)
        "/api/search" -> search(call)
        "/api/category" -> browse(call) { id -> BrowseApi.category(context, id.toLong(), favoriteIds()) }
        "/api/channel/videos" -> browse(call) { id -> BrowseApi.videos(context, id) }
        "/api/channel/clips" -> browse(call) { id -> BrowseApi.clips(context, id) }
        "/api/stats" -> withTimeoutOrNull(listTimeoutMs) { BrowseApi.stats(context) }?.let { json(it) } ?: failed()
        else -> json(JsonObject().apply { addProperty("error", "unknown") }, HttpStatusCode.NotFound)
    }

    /**
     * Twitch did not answer in time, failed or answered with an error: the page shows its error with
     * "Try again" instead of "nothing found". [extra] adds what the page needs anyway (the lists switched on).
     */
    private fun failed(extra: JsonObject.() -> Unit = {}) =
        json(JsonObject().apply { addProperty("error", "twitch"); extra() }, HttpStatusCode.BadGateway)

    /** `?id=<numeric id>` of a category or channel, answered by [load] within the list timeout. */
    private suspend fun browse(call: ApplicationCall, load: suspend (String) -> JsonObject?): Reply {
        val id = call.param("id")?.takeIf { Regex("^[0-9]{1,12}$").matches(it) }
            ?: return json(JsonObject().apply { addProperty("error", "bad id") }, HttpStatusCode.BadRequest)
        val answer = try {
            withTimeoutOrNull(listTimeoutMs) { load(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "browse ${call.request.path()}", e)
            null
        }
        return answer?.let { json(it) } ?: failed()
    }

    /** All app settings from the browser (SettingsApi); switchable with "Allow settings from the browser". */
    private suspend fun settings(uri: String, call: ApplicationCall): Reply {
        if (!SettingsApi.allowed(context)) return json(JsonObject().apply { addProperty("error", "disabled") }, HttpStatusCode.Forbidden)
        val method = call.request.httpMethod
        if (method == HttpMethod.Get && uri == "/api/settings") {
            return json(SettingsApi.tree(context, call.param("lang")))
        }
        if (method == HttpMethod.Get && uri == "/api/mqtt") return json(com.shrug.shrugtv.ha.MqttBridge.info(context))
        if (method != HttpMethod.Post) return json(JsonObject().apply { addProperty("error", "method") }, HttpStatusCode.MethodNotAllowed)
        if (!sameOrigin(call)) return json(JsonObject().apply { addProperty("error", "origin") }, HttpStatusCode.Forbidden)
        if (call.header("Transfer-Encoding")?.contains("chunked", true) == true && call.header("Content-Length") == null) {
            return Reply(HttpStatusCode.LengthRequired, "application/json; charset=utf-8", """{"error":"length required"}""".toByteArray())
        }
        val length = call.header("Content-Length")?.toLongOrNull() ?: 0L
        if (length > MAX_SETTINGS_BODY) return json(JsonObject().apply { addProperty("error", "too large") }, HttpStatusCode.BadRequest)
        val body = try { JsonParser.parseString(readBody(call, MAX_SETTINGS_BODY)).asJsonObject } catch (e: Exception) { null }
            ?: return json(JsonObject().apply { addProperty("error", "bad json") }, HttpStatusCode.BadRequest)
        if (uri == "/api/design") return json(SettingsApi.design(context, body))
        return if (uri == "/api/settings") {
            val values = body.get("values")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return json(JsonObject().apply { addProperty("error", "no values") }, HttpStatusCode.BadRequest)
            json(SettingsApi.apply(context, values, fromBackup = body.get("backup")?.takeIf { it.isJsonPrimitive }?.asBoolean == true))
        } else {
            val key = body.get("key")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            json(SettingsApi.action(context, key))
        }
    }

    /** The channel ids marked with the star in ShrugTV (same list as on the TV). */
    private fun favoriteIds(): Set<String> = try {
        com.shrug.shrugtv.database.AppDatabase.getInstance(context).favoriteChannelDao().allChannelIds.toSet()
    } catch (e: Exception) {
        com.shrug.shrugtv.session.AppState.favoriteChannelIds.toSet()
    }

    /**
     * Stars from the phone: `GET /api/favorites` lists the ids, `POST /api/favorite {"id":"<user id>",
     * "on":true|false}` sets or removes one. The star decides which channels a Home Assistant dashboard shows first
     * (field `favorite` of the MQTT list). Needs "Allow settings from the browser" like the other changes.
     */
    private suspend fun favorites(uri: String, call: ApplicationCall): Reply {
        if (uri == "/api/favorites") return json(JsonObject().apply { add("ids", JsonArray().apply { favoriteIds().sorted().forEach { add(it) } }) })
        if (call.request.httpMethod != HttpMethod.Post) return json(JsonObject().apply { addProperty("error", "method") }, HttpStatusCode.BadRequest)
        val body = try { JsonParser.parseString(readBody(call)).asJsonObject } catch (e: Exception) { null }
        if (!sameOrigin(call)) return json(JsonObject().apply { addProperty("error", "origin") }, HttpStatusCode.Forbidden)
        if (!SettingsApi.allowed(context)) return json(JsonObject().apply { addProperty("error", "disabled") }, HttpStatusCode.Forbidden)
        val id = body?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        val on = body?.get("on")?.takeIf { it.isJsonPrimitive }?.asBoolean
        if (id == null || !Regex("^[0-9]{1,12}$").matches(id) || on == null) {
            return json(JsonObject().apply { addProperty("error", "bad request") }, HttpStatusCode.BadRequest)
        }
        // A Room write of milliseconds; it runs NonCancellable, so a timeout around it would not shorten anything
        if (on) com.shrug.shrugtv.apihelpers.FavoriteChannels.setFavorite(context, id)
        else com.shrug.shrugtv.apihelpers.FavoriteChannels.removeFavorite(context, id)
        return json(JsonObject().apply { addProperty("ok", true); addProperty("id", id); addProperty("favorite", on) })
    }

    /** Version and update menu (UpdateApi); checking and installing need a POST from this page and "Allow settings from the browser". */
    private fun update(uri: String, call: ApplicationCall): Reply {
        if (uri == "/api/update") return json(UpdateApi.info(context))
        if (call.request.httpMethod != HttpMethod.Post) return json(JsonObject().apply { addProperty("error", "method") }, HttpStatusCode.BadRequest)
        if (!sameOrigin(call)) return json(JsonObject().apply { addProperty("error", "origin") }, HttpStatusCode.Forbidden)
        if (!SettingsApi.allowed(context)) return json(JsonObject().apply { addProperty("error", "disabled") }, HttpStatusCode.Forbidden)
        return json(if (uri == "/api/update/check") UpdateApi.check(context) else UpdateApi.install(context))
    }

    /** Remembered devices: list (with the asking device marked) and removing one. */
    private suspend fun devicesApi(uri: String, call: ApplicationCall): Reply {
        val store = devices ?: return json(JsonObject().apply { add("devices", JsonArray()) })
        if (!SettingsApi.allowed(context)) return json(JsonObject().apply { addProperty("error", "disabled") }, HttpStatusCode.Forbidden)
        val mine = call.sid?.let { c -> store.find(c, call.ip)?.id }
        if (uri == "/api/devices/remove") {
            if (call.request.httpMethod != HttpMethod.Post || !sameOrigin(call)) return json(JsonObject().apply { addProperty("error", "method") }, HttpStatusCode.BadRequest)
            val id = try { JsonParser.parseString(readBody(call)).asJsonObject.get("id")?.asString } catch (e: Exception) { null }
            val ok = id != null && store.remove(id)
            if (ok) onDevicesChanged()
            return json(JsonObject().apply { addProperty("ok", ok) })
        }
        return json(JsonObject().apply {
            addProperty("remember", remember())
            mine?.let { addProperty("current", it) }
            add("devices", JsonArray().apply {
                store.list().forEach { d ->
                    add(JsonObject().apply {
                        addProperty("id", d.id)
                        addProperty("name", d.name)
                        addProperty("paired", d.pairedAt)
                        addProperty("seen", d.lastSeen)
                    })
                }
            })
        })
    }

    /** App log for the browser (AppLog); switch "Log in the browser". */
    private fun log(call: ApplicationCall): Reply {
        // The log may hold more than its redaction knows: also without a PIN only a paired phone reads it
        val c = call.sid
        if (!(pairing.isValid(c) || (remember() && devices?.find(c, call.ip) != null))) {
            return json(JsonObject().apply { addProperty("error", "not paired") }, HttpStatusCode.Unauthorized)
        }
        if (!Prefs.companionLog.get(context)) {
            return json(JsonObject().apply { addProperty("error", "disabled") }, HttpStatusCode.Forbidden)
        }
        val after = call.param("after")?.toLongOrNull() ?: 0L
        val limit = call.param("limit")?.toIntOrNull() ?: AppLog.MAX_LINES
        val (lines, first) = AppLog.since(after, limit)
        return json(JsonObject().apply {
            addProperty("first", first)
            addProperty("running", AppLog.isRunning)
            addProperty("version", BuildConfig.VERSION_NAME)
            addProperty("device", android.os.Build.MODEL)
            // Compact rows: [seq, time ms, level, tag, message, pid, tid]
            add("lines", JsonArray().apply {
                for (l in lines) add(JsonArray().apply {
                    add(l.seq); add(l.timeMs); add(l.level.toString()); add(l.tag); add(l.msg); add(l.pid); add(l.tid)
                })
            })
        })
    }

    /** Browsers send Origin with POSTs; it must be this server (on top of the SameSite cookie). */
    private fun sameOrigin(call: ApplicationCall): Boolean {
        val host = call.header("Host")
        if (!isAllowedHost(host)) return false
        val origin = call.header("Origin") ?: return true
        return origin == "http://$host"
    }

    private class StateCache(val source: Any, val at: Long, val json: JsonObject)
    @Volatile private var stateCache: StateCache? = null

    /** The state for the page, built once for all sockets (and a second at most): not once per phone and push. */
    private fun stateJson(): JsonObject {
        val current = PlaybackStateHub.state.value
        val now = android.os.SystemClock.elapsedRealtime()
        stateCache?.let { if (it.source === current && now - it.at < 1_000L) return it.json }
        return buildState().also { stateCache = StateCache(current, now, it) }
    }

    private fun buildState(): JsonObject = PlaybackStateHub.state.value.toJson(System.currentTimeMillis(), BuildConfig.VERSION_NAME).apply {
        addProperty("chat_allowed", chatAllowed())
        PlaybackStateHub.state.value.userId?.let { addProperty("favorite", it in favoriteIds()) }
        addProperty("player_open", PlaybackStateHub.commands != null)
        addProperty("settings_allowed", SettingsApi.allowed(context))
        addProperty("log_allowed", Prefs.companionLog.get(context))
    }

    private fun card(m: Movie, favs: Set<String> = favoriteIds()): JsonObject = JsonObject().apply {
        if (m.userId > 0) addProperty("id", m.userId.toString())
        addProperty("login", m.login)
        addProperty("name", m.displayName.ifEmpty { m.login ?: "" })
        addProperty("category", m.gameName)
        addProperty("title", m.title)
        addProperty("viewers", m.viewerCount)
        addProperty("image", m.cardImageUrl)
        addProperty("avatar", m.avatarUrl)
        addProperty("favorite", m.isFavorite || (m.userId > 0 && m.userId.toString() in favs))
    }

    private class ListCache(val at: Long, val list: List<Movie>)
    @Volatile private var liveCache: ListCache? = null

    private suspend fun liveCards(): JsonArray {
        val now = android.os.SystemClock.elapsedRealtime()
        val list = liveCache?.takeIf { now - it.at < LIVE_CACHE_MS }?.list ?: try {
            (withTimeoutOrNull(listTimeoutMs) { com.shrug.shrugtv.ui.tv.home.HomeData.followedLive(context) } ?: emptyList())
                .also { if (it.isNotEmpty()) liveCache = ListCache(now, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val favs = favoriteIds()
        return JsonArray().apply {
            list.sortedByDescending { it.isFavorite || it.userId.toString() in favs }.take(50).forEach { add(card(it, favs)) }
        }
    }

    /**
     * One list of the channel switcher on the TV for the phone: `?name=followed|same_game|
     * same_game_language|history|top_streams|top_language`; answers with the lists switched on in the TV settings and the
     * channels of the asked one, without the one that is playing.
     */
    private suspend fun lists(call: ApplicationCall): Reply {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = com.shrug.shrugtv.ui.tv.player.SwitcherNav.enabled { it.pref.get(prefs) }
        val name = call.param("name") ?: "followed"
        val tab = com.shrug.shrugtv.ui.tv.player.ChannelSwitcherTab.values().firstOrNull { it.name.lowercase() == name }
            ?: return json(JsonObject().apply { addProperty("error", "unknown list") }, HttpStatusCode.NotFound)
        val playing = PlaybackStateHub.state.value.login.orEmpty()
        val head: JsonObject.() -> Unit = {
            addProperty("name", tab.name.lowercase())
            add("enabled", JsonArray().apply { enabled.forEach { add(it.name.lowercase()) } })
            addProperty("playing", playing.isNotEmpty())
        }
        val movies = try {
            // Without a network or a login the lists never answer: after the timeout the page shows an error, not
            // "nothing in this list"
            withTimeoutOrNull(listTimeoutMs) {
                com.shrug.shrugtv.ui.tv.player.SwitcherLists.load(context, tab) {
                    if (playing.isNotEmpty()) com.shrug.shrugtv.ui.tv.home.HomeData.gameIdOfLogin(context, playing) else 0L
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "list $name", e)
            null
        } ?: return failed(head)
        return json(JsonObject().apply {
            head()
            add("items", JsonArray().apply {
                val favs = favoriteIds()
                movies.filter { it.login != null && !it.login.equals(playing, ignoreCase = true) }.take(50).forEach { add(card(it, favs)) }
            })
        })
    }

    private val searchGate = java.util.concurrent.Semaphore(SEARCHES_AT_ONCE)

    private val searchCache = object : LinkedHashMap<String, Pair<Long, BrowseApi.Search>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, BrowseApi.Search>>?) = size > 40
    }

    /**
     * Channels and categories for [q], kept for 30 s: typing the same words again does not ask
     * Twitch again. Null when Twitch failed or the search had to give up; only answers are kept.
     */
    private suspend fun searchAnswer(q: String): BrowseApi.Search? {
        val key = q.lowercase()
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(searchCache) { searchCache[key] }?.let { if (now - it.first < SEARCH_CACHE_MS) return it.second }
        // Typing fast sends a search per pause; at most two go to Twitch at a time, the others wait a little or give up.
        // Blocking is fine here: Ktor runs the requests on its IO dispatcher.
        if (!searchGate.tryAcquire(3, java.util.concurrent.TimeUnit.SECONDS)) return null
        val answer = try {
            withTimeoutOrNull(listTimeoutMs) { BrowseApi.search(context, q) }
        } finally {
            searchGate.release()
        } ?: return null
        synchronized(searchCache) { searchCache[key] = now to answer }
        return answer
    }

    /** `?q=`: an empty query is an empty answer; a failed one an error, never "no results". */
    private suspend fun search(call: ApplicationCall): Reply {
        val q = call.param("q")?.trim()?.take(100) ?: ""
        if (q.isEmpty()) return json(JsonObject().apply { add("items", JsonArray()); add("categories", JsonArray()) })
        val answer = try {
            searchAnswer(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "search", e)
            null
        }
        return answer?.let { json(BrowseApi.searchJson(it, favoriteIds())) } ?: failed()
    }

    // ------------------------------------------------------------------ static files

    private class Asset(val bytes: ByteArray, val gzip: ByteArray?, val etag: String, val mime: String)
    private val assetCache = java.util.concurrent.ConcurrentHashMap<String, Asset>()
    private val crcCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun rawAsset(name: String): ByteArray = context.assets.open("companion/$name").use { it.readBytes() }

    private fun crcOf(bytes: ByteArray) = java.util.zip.CRC32().apply { update(bytes) }.value.toString(16)

    private fun versionOf(name: String): String = crcCache.getOrPut(name) { crcOf(rawAsset(name)) }

    private fun mimeOf(name: String) = when {
        name.endsWith(".html") -> MIME_HTML
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".woff2") -> "font/woff2"
        else -> "application/json; charset=utf-8"
    }

    /**
     * A file of the page, built once and kept: compressed, with its checksum as ETag. The page is
     * built per language: it names the text files, and the files and the scripts carry a checksum in the address, so a changed
     * file is always fetched anew and an unchanged one never again.
     */
    private fun builtAsset(name: String, lang: String): Asset? {
        val key = if (name == "index.html") "$name|$lang" else name
        assetCache[key]?.let { return it }
        val bytes = try { rawAsset(name) } catch (e: IOException) { return null }
        var out = bytes
        val q = '"'
        if (name == "index.html") {
            var html = String(bytes, Charsets.UTF_8)
            val tags = listOf("en", lang).distinct().joinToString("\n") { "<script src=$q/i18n/$it.js?v=${versionOf("i18n/$it.js")}$q></script>" }
            html = html.replace("<!--i18n-->", tags)
            for (file in listOf("app.js", "theme.js")) html = html.replace("$q/$file$q", "$q/$file?v=${versionOf(file)}$q")
            val css = builtAsset("app.css", lang)?.let { crcOf(it.bytes) } ?: versionOf("app.css")
            html = html.replace("$q/app.css$q", "$q/app.css?v=$css$q")
            out = html.toByteArray(Charsets.UTF_8)
        }
        if (name == "app.css") {
            var css = String(bytes, Charsets.UTF_8)
            for (font in FONTS) css = css.replace("/fonts/$font)", "/fonts/$font?v=${versionOf("fonts/$font")})")
            out = css.toByteArray(Charsets.UTF_8)
        }
        val mime = mimeOf(name)
        val gzip = if (name.endsWith(".png") || name.endsWith(".woff2")) null else java.io.ByteArrayOutputStream().also { o ->
            java.util.zip.GZIPOutputStream(o).use { it.write(out) }
        }.toByteArray()
        return Asset(out, gzip, "$q${crcOf(out)}$q", mime).also { assetCache[key] = it }
    }

    private fun asset(uri: String, call: ApplicationCall): Reply {
        val name = when {
            uri == "/" || uri == "/index.html" -> "index.html"
            uri == "/app.js" -> "app.js"
            uri == "/app.css" -> "app.css"
            uri == "/theme.js" -> "theme.js"
            uri == "/manifest.json" -> return manifest()
            // Browsers ask for /favicon.ico by themselves; the PNG is the app icon (shrug figure)
            uri == "/favicon.png" || uri == "/favicon.ico" -> "favicon.png"
            uri == "/icon-512.png" || uri == "/icon-maskable-512.png" -> uri.removePrefix("/")
            uri.startsWith("/fonts/") && uri.removePrefix("/fonts/") in FONTS -> uri.removePrefix("/")
            uri.startsWith("/i18n/") && uri.endsWith(".js") && uri.removePrefix("/i18n/").removeSuffix(".js") in LANGS -> uri.removePrefix("/")
            else -> return text(HttpStatusCode.NotFound, "not found")
        }
        val isPage = name == "index.html"
        val a = builtAsset(name, pickLanguage(call.header("Accept-Language"))) ?: return text(HttpStatusCode.NotFound, "not found")
        // Files with a checksum in the address never change under it: the browser keeps them for good
        val versioned = !isPage && !call.request.queryParameters.getAll("v").isNullOrEmpty()
        val cache = if (versioned) "public, max-age=31536000, immutable" else "no-cache"
        val response = if (call.header("If-None-Match") == a.etag) {
            Reply(HttpStatusCode.NotModified, a.mime, ByteArray(0))
        } else if (a.gzip != null && call.header("Accept-Encoding")?.contains("gzip", ignoreCase = true) == true) {
            Reply(HttpStatusCode.OK, a.mime, a.gzip).apply { addHeader("Content-Encoding", "gzip") }
        } else {
            Reply(HttpStatusCode.OK, a.mime, a.bytes)
        }
        return response.apply {
            addHeader("Cache-Control", cache)
            addHeader("ETag", a.etag)
            addHeader("Vary", if (isPage) "Accept-Encoding, Accept-Language" else "Accept-Encoding")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
            if (isPage) {
                addHeader("Content-Security-Policy", CSP)
                addHeader("X-Frame-Options", "DENY")
            }
        }
    }

    /** Web app manifest: the colours follow the skin of the TV when "TV skin on the phone page" is on, so the installed icon matches. */
    private fun manifest(): Reply =
        Reply(HttpStatusCode.OK, "application/manifest+json; charset=utf-8", SkinApi.manifest(context).toByteArray(Charsets.UTF_8)).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    /** Session cookie; remembered devices keep theirs for a year (renewed while in use is not needed: the key stays). */
    private fun cookie(value: String, persistent: Boolean = false) =
        "$COOKIE=$value; Path=/; HttpOnly; SameSite=Strict" + if (persistent) "; Max-Age=31536000" else ""

    /**
     * The request body as UTF-8, whatever charset the Content-Type names (: the old server fell back
     * to US-ASCII and broke every umlaut). At most four bytes per allowed character are read.
     */
    private suspend fun readBody(call: ApplicationCall, limit: Int = 2_000): String {
        val bytes = call.request.receiveChannel().readRemaining(limit * 4L).readByteArray()
        return String(bytes, Charsets.UTF_8).take(limit)
    }

    private fun text(status: HttpStatusCode, body: String) = Reply(status, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun json(o: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
        Reply(status, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8)).apply { addHeader("Cache-Control", "no-store") }

    // ------------------------------------------------------------------ WebSocket

    private inner class PhoneSocket(private val ws: DefaultWebSocketServerSession) {
        private val call = ws.call
        private val cookieValue = call.sid
        private val ip = call.ip
        /**
         * Browsers send Origin with the handshake and a page on another site cannot forge it: it has to be this server
         *. Without Origin it is no browser page. Refused after the handshake with 1008, as before.
         */
        private val basics = isPrivateAddress(ip) && isAllowedHost(call.header("Host")) &&
            call.header("Origin").let { it == null || it == "http://${call.header("Host")}" }
        /** Asked again for every message: a PIN switched on later or a phone that was pushed out ends its connection. */
        private fun authorisedNow() = basics && authorised(cookieValue, ip)
        @Volatile private var chatSubscribed = false
        @Volatile private var lastSentLine = -1L
        private val sentDeleted = java.util.Collections.synchronizedSet(HashSet<Long>())
        private var windowStart = 0L
        private var windowCount = 0
        /** Held here: SharedPreferences keeps listeners only weakly. */
        private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // Only the settings of the page; internal values (positions, caches) change often and are no news for it
            if (key != null && key.startsWith("pref")) ws.launch { sendJson("settings", JsonObject().apply { addProperty("key", key) }) }
        }

        suspend fun run() {
            if (!authorisedNow()) {
                ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not paired"))
                return
            }
            sendJson("state", stateJson())
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
            try {
                coroutineScope {
                    @OptIn(FlowPreview::class)
                    launch { PlaybackStateHub.state.debounce(300L).collect { sendJson("state", stateJson()) } }
                    launch { PlaybackStateHub.events.collect { sendJson("event", it.toJson()) } }
                    launch { watch() }
                    for (frame in ws.incoming) {
                        if (frame is Frame.Text) onMessage(frame.readText())
                    }
                    // The phone closed (or was closed): the pushes end with it
                    coroutineContext.cancelChildren()
                }
            } finally {
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            }
        }

        /** Checks the permission twice a second, pushes the chat and sends the sign of life; Ktor sends the pings. */
        private suspend fun watch() {
            var lastAlive = System.currentTimeMillis()
            while (true) {
                delay(minOf(500L, pingIntervalMs))
                if (!authorisedNow()) {
                    ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not paired"))
                    return
                }
                if (chatSubscribed) pushChat()
                if (System.currentTimeMillis() - lastAlive >= pingIntervalMs) {
                    lastAlive = System.currentTimeMillis()
                    // The browser sees no pings, so a small message tells it the connection is alive
                    sendJson("alive", JsonObject())
                }
            }
        }

        private suspend fun pushChat() {
            val lines = PlaybackStateHub.chatLines?.value ?: return
            val out = JsonArray()
            for (l in lines) {
                if (l.id > lastSentLine) {
                    out.add(ChatMirror.line(l))
                    if (l.deleted) sentDeleted.add(l.id)
                } else if (l.deleted && sentDeleted.add(l.id)) {
                    out.add(JsonObject().apply { addProperty("id", l.id); addProperty("d", true); addProperty("upd", true) })
                }
            }
            lines.lastOrNull()?.let { if (it.id > lastSentLine) lastSentLine = it.id }
            if (out.size() > 0) sendJson("chat", JsonObject().apply { add("lines", out) })
        }

        private fun onMessage(message: String) {
            if (!authorisedNow()) return
            // Max. 10 messages per second and phone
            val now = System.currentTimeMillis()
            if (now - windowStart > 1_000L) { windowStart = now; windowCount = 0 }
            if (++windowCount > 10) return
            val o = try { JsonParser.parseString(message.take(2_000)).asJsonObject } catch (e: Exception) { return }
            // Wrong types in a message (a number where text is expected) must not end the connection
            try { handle(o) } catch (e: Exception) { Log.d(TAG, "message: ${e.message}") }
        }

        private fun handle(o: JsonObject) {
            when (o.get("t")?.asString) {
                "key" -> o.get("k")?.asString?.let { KeyInjector.press(it) }
                "cmd" -> {
                    val name = o.get("name")?.asString ?: return
                    if (name == "chat_message" && !chatAllowed()) return
                    if (name in HubCommandRouter.NAMES) {
                        HubCommandRouter.dispatch(context, name, o.get("payload")?.takeIf { !it.isJsonNull }?.asString ?: "")
                    }
                }
                "chat" -> {
                    chatSubscribed = o.get("on")?.asBoolean ?: false
                    if (chatSubscribed) { lastSentLine = -1L; sentDeleted.clear() }
                }
                "text" -> {
                    // Text from the phone for the field that has the focus on the TV
                    val t = o.get("s")?.asString ?: return
                    if (t.isNotEmpty()) KeyInjector.typeText(t.take(MAX_TEXT))
                }
            }
        }

        private suspend fun sendJson(type: String, data: JsonObject) {
            try {
                ws.send(Frame.Text(JsonObject().apply { addProperty("t", type); add("d", data) }.toString()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Closed in the meantime (ClosedSendChannelException, IOException): the session ends by itself
                Log.d(TAG, "send: ${e.message}")
            }
        }
    }

    /** Same as [stop]; the name the app uses. */
    fun shutdown() = stop()
}
