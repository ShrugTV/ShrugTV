package com.shrug.shrugtv.companion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.preference.PreferenceManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.shrug.shrugtv.ui.tv.settings.Entry
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The companion server on a real local port (docs/companion_plan.md 3). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class CompanionServerTest {
    private val port = 18765
    private val pairing = Pairing()
    private lateinit var server: CompanionServer
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val base = "http://127.0.0.1:$port"

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        // These tests are about pairing: PIN on (the default is off since )
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(com.shrug.shrugtv.prefs.Prefs.companionRequirePin.key, true).commit()
        server = CompanionServer(ctx, port, pairing) {}
        server.start(0)
    }

    @After
    fun tearDown() {
        server.shutdown()
        BrowseApi.source = BrowseApi.Helix
        CompanionServer.listTimeoutMs = 8_000L
    }

    private fun get(path: String, cookie: String? = null): Response =
        client.newCall(Request.Builder().url(base + path).apply { if (cookie != null) header("Cookie", cookie) }.build()).execute()

    private fun pairedCookie(): String {
        val offer = pairing.newOffer()
        val r = get("/p/${offer.token}")
        assertEquals(303, r.code)
        assertEquals("/", r.header("Location"))
        val setCookie = r.header("Set-Cookie")!!
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict"))
        return setCookie.substringBefore(";")
    }

    @Test
    fun `api needs pairing, the QR link pairs`() {
        assertEquals(401, get("/api/state").code)
        val cookie = pairedCookie()
        val r = get("/api/state", cookie)
        assertEquals(200, r.code)
        val json = JsonParser.parseString(r.body!!.string()).asJsonObject
        assertEquals("none", json.get("kind").asString)
        assertTrue(json.has("chat_allowed"))
    }

    @Test
    fun `without the PIN switch every LAN request is allowed`() {
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
            .edit().putBoolean(com.shrug.shrugtv.prefs.Prefs.companionRequirePin.key, false).commit()
        assertEquals(200, get("/api/state").code)
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
            .edit().putBoolean(com.shrug.shrugtv.prefs.Prefs.companionRequirePin.key, true).commit()
        assertEquals(401, get("/api/state").code)
    }

    @Test
    fun `used or wrong tokens redirect to the expired page`() {
        val offer = pairing.newOffer()
        get("/p/${offer.token}").close()
        assertEquals("/?expired=1", get("/p/${offer.token}").header("Location"))
        assertEquals("/?expired=1", get("/p/nonsense").header("Location"))
    }

    @Test
    fun `wrong PIN is refused, right PIN pairs`() {
        val offer = pairing.newOffer()
        val wrong = client.newCall(Request.Builder().url("$base/api/pin").post("""{"pin":"000000"}""".toRequestBody()).build()).execute()
        assertEquals(401, wrong.code)
        val right = client.newCall(Request.Builder().url("$base/api/pin").post("""{"pin":"${offer.pin}"}""".toRequestBody()).build()).execute()
        assertEquals(200, right.code)
        assertNotNull(right.header("Set-Cookie"))
    }

    @Test
    fun `web app is served, unknown files are not`() {
        val r = get("/")
        assertEquals(200, r.code)
        assertTrue(r.body!!.string().contains("<script src=\"/app.js?v="))
        assertEquals(200, get("/app.js").code)
        assertEquals(404, get("/../secrets").code)
    }

    @Test
    fun `favicon is served as png under both addresses`() {
        for (path in listOf("/favicon.png", "/favicon.ico")) {
            val r = get(path)
            assertEquals(200, r.code)
            assertEquals("image/png", r.header("Content-Type"))
            val bytes = r.body!!.bytes()
            assertTrue("PNG signature", bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte())
        }
    }

    @Test
    fun `websocket sends the state to a paired phone and closes for others`() {
        val cookie = pairedCookie()
        val got = CountDownLatch(1)
        var first: String? = null
        val ws = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/ws").header("Cookie", cookie).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { first = text; got.countDown() }
        })
        assertTrue("state within 5 s", got.await(5, TimeUnit.SECONDS))
        assertEquals("state", JsonParser.parseString(first).asJsonObject.get("t").asString)
        ws.close(1000, null)

        val closed = CountDownLatch(1)
        var code = 0
        client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/ws").build(), object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, c: Int, reason: String) { code = c; closed.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
        })
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(1008, code)
    }

    private fun openSocket(cookie: String?, origin: String?, onText: (String) -> Unit = {}): Pair<WebSocket, Int> {
        val closed = CountDownLatch(1)
        var code = 0
        val req = Request.Builder().url("ws://127.0.0.1:$port/ws").apply {
            if (cookie != null) header("Cookie", cookie)
            if (origin != null) header("Origin", origin)
        }.build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
            override fun onClosing(webSocket: WebSocket, c: Int, reason: String) { code = c; closed.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
        })
        closed.await(1500, TimeUnit.MILLISECONDS)
        return ws to code
    }

    @Test
    fun `websocket refuses a foreign origin and takes the own one (michael-shrugtv-321)`() {
        val cookie = pairedCookie()
        assertEquals("a page on another site", 1008, openSocket(cookie, "http://evil.example").second)
        assertEquals("the own page", 0, openSocket(cookie, "http://127.0.0.1:$port").second)
        assertEquals("not a browser", 0, openSocket(cookie, null).second)
    }

    @Test
    fun `an open websocket ends when the phone is no longer paired`() {
        val cookie = pairedCookie()
        val ended = CountDownLatch(1)
        var code = 0
        val ws = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/ws").header("Cookie", cookie).build(), object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, c: Int, reason: String) { code = c; ended.countDown() }
        })
        pairing.disconnectAll()
        ws.send("""{"t":"key","k":"up"}""")
        assertTrue("closed within 5 s", ended.await(5, TimeUnit.SECONDS))
        assertEquals(1008, code)
    }

    @Test
    fun `wrong types in websocket messages do not end the connection`() {
        val cookie = pairedCookie()
        val ended = CountDownLatch(1)
        val ws = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/ws").header("Cookie", cookie).build(), object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, c: Int, reason: String) { ended.countDown() }
        })
        ws.send("""{"t":["x"],"k":5}""")
        ws.send("""{"t":"key","k":{"a":1}}""")
        ws.send("""{"t":"text","s":{"a":1}}""")
        ws.send("""{"t":"chat","on":"maybe"}""")
        assertFalse("still open after 1.5 s", ended.await(1500, TimeUnit.MILLISECONDS))
        ws.close(1000, null)
    }

    // ------------------------------------------------------------------ files of the page

    private fun getWith(path: String, vararg headers: Pair<String, String>): Response =
        client.newCall(Request.Builder().url(base + path).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute()

    @Test
    fun `the page names its texts, has no inline script and sets the protective headers`() {
        val r = getWith("/", "Accept-Language" to "de-DE,de;q=0.9,en;q=0.8", "Accept-Encoding" to "identity")
        val html = r.body!!.string()
        assertTrue(html, html.contains("<script src=\"/i18n/en.js?v=") && html.contains("<script src=\"/i18n/de.js?v="))
        assertTrue(html.contains("<script src=\"/theme.js?v="))
        assertFalse("no inline script", Regex("<script>").containsMatchIn(html))
        val csp = r.header("Content-Security-Policy")!!
        assertTrue(csp, csp.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'"))
        assertEquals("DENY", r.header("X-Frame-Options"))
        // Other languages get their own text file, unknown ones English only
        val fr = getWith("/", "Accept-Language" to "fr-CH").body!!.string()
        assertTrue(fr.contains("/i18n/fr.js?v="))
        val xx = getWith("/", "Accept-Language" to "xx").body!!.string()
        assertFalse(xx.contains("/i18n/xx.js") || xx.contains("/i18n/de.js"))
    }

    @Test
    fun `language files exist for every language, others are not served`() {
        for (l in listOf("en", "de", "es", "fr", "pt", "it", "pl", "tr", "ru", "ja", "ko", "zh")) {
            val r = getWith("/i18n/$l.js", "Accept-Encoding" to "identity")
            assertEquals(l, 200, r.code)
            assertTrue(r.body!!.string().contains("window.T.$l = {"))
        }
        assertEquals(404, get("/i18n/xx.js").code)
    }

    @Test
    fun `files come compressed, with an ETag, and files with a checksum are kept for good`() {
        val plain = getWith("/app.js", "Accept-Encoding" to "identity")
        val size = plain.body!!.bytes().size
        assertNull(plain.header("Content-Encoding"))
        val zipped = client.newCall(Request.Builder().url("$base/app.js").header("Accept-Encoding", "gzip").build()).execute()
        // OkHttp unzips by itself only if it set the header; here we asked ourselves, so the bytes stay packed
        assertEquals("gzip", zipped.header("Content-Encoding"))
        assertTrue("smaller than the plain file", zipped.body!!.bytes().size < size)
        val etag = plain.header("ETag")!!
        assertEquals("no checksum in the address: asked again every time", "no-cache", plain.header("Cache-Control"))
        assertEquals(304, getWith("/app.js", "If-None-Match" to etag).code)
        val versioned = getWith("/app.js?v=abc123", "Accept-Encoding" to "identity")
        assertTrue(versioned.header("Cache-Control")!!, versioned.header("Cache-Control")!!.contains("immutable"))
    }

    @Test
    fun `fonts and large icons are served and named with their checksum (michael-shrugtv-336)`() {
        val css = getWith("/app.css", "Accept-Encoding" to "identity").body!!.string()
        for (w in listOf("400", "600", "700")) {
            assertTrue(css, Regex("""/fonts/inter-$w\.woff2\?v=[0-9a-f]+\)""").containsMatchIn(css))
            val r = getWith("/fonts/inter-$w.woff2?v=1", "Accept-Encoding" to "gzip")
            assertEquals(200, r.code)
            assertEquals("font/woff2", r.header("Content-Type"))
            assertNull("already compressed", r.header("Content-Encoding"))
            assertTrue("cut down", r.body!!.bytes().size in 10_000..120_000)
        }
        assertEquals(404, get("/fonts/other.woff2").code)
        val manifest = JsonParser.parseString(get("/manifest.json").body!!.string()).asJsonObject
        val icons = manifest.getAsJsonArray("icons").map { it.asJsonObject }
        assertTrue(icons.any { it.get("sizes").asString == "512x512" && it.get("purpose").asString == "maskable" })
        for (i in icons) assertEquals(i.get("src").asString, 200, get(i.get("src").asString).code)
        // The page names app.css with the checksum of what is sent, so a new font also means a new app.css address
        val html = getWith("/", "Accept-Encoding" to "identity").body!!.string()
        val v = Regex("""/app\.css\?v=([0-9a-f]+)""").find(html)!!.groupValues[1]
        assertEquals(java.util.zip.CRC32().apply { update(css.toByteArray()) }.value.toString(16), v)
    }

    @Test
    fun `a Host header is needed for the files too`() {
        assertEquals(403, getWith("/", "Host" to "evil.example").code)
    }

    @Test
    fun `the log needs a paired phone also without a PIN`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(com.shrug.shrugtv.prefs.Prefs.companionRequirePin.key, false).commit()
        assertEquals("api is open without a PIN", 200, get("/api/state").code)
        assertEquals("the log is not", 401, get("/api/log").code)
        assertEquals(200, get("/api/log", pairedCookie()).code)
    }

    @Test
    fun `text and the Accept-Language header are picked`() {
        assertEquals("de", CompanionServer.pickLanguage("de-DE,de;q=0.9"))
        assertEquals("en", CompanionServer.pickLanguage(null))
        assertEquals("zh", CompanionServer.pickLanguage("zh-CN,zh;q=0.9,en;q=0.8"))
        assertEquals("pt", CompanionServer.pickLanguage("xx,pt-BR"))
        assertEquals("en", CompanionServer.pickLanguage("nl,sv;q=0.8"))
    }

    @Test
    fun `a silent HTTP connection is closed after the read timeout (michael-shrugtv-317)`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val ws2 = CompanionServer(ctx, port + 2, Pairing()) {}
        ws2.start(1_000)
        try {
            java.net.Socket("127.0.0.1", port + 2).use { s ->
                s.soTimeout = 5_000
                val started = System.currentTimeMillis()
                // Nothing sent: the server gives up after its timeout and closes, the read sees the end
                val read = try { s.getInputStream().read() } catch (e: java.net.SocketException) { -1 }
                val took = System.currentTimeMillis() - started
                assertEquals("closed by the server", -1, read)
                assertTrue("after about the timeout, took $took ms", took in 800..4_000)
            }
        } finally {
            ws2.stop()
        }
    }

    @Test
    fun `the server sends a sign of life the browser can see (michael-shrugtv-324)`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(com.shrug.shrugtv.prefs.Prefs.companionRequirePin.key, false).commit()
        val before = CompanionServer.pingIntervalMs
        CompanionServer.pingIntervalMs = 300L
        val ws2 = CompanionServer(ctx, port + 2, Pairing()) {}
        ws2.start(5_000)
        try {
            val alive = CountDownLatch(1)
            val ws = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:${port + 2}/ws").build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { if (text.contains("\"t\":\"alive\"")) alive.countDown() }
            })
            assertTrue("alive within 3 s", alive.await(3, TimeUnit.SECONDS))
            ws.close(1000, null)
        } finally {
            CompanionServer.pingIntervalMs = before
            ws2.stop()
        }
    }

    @Test
    fun `a body without a length is answered with 411 (michael-shrugtv-336)`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(com.shrug.shrugtv.prefs.Prefs.companionRequirePin.key, false).commit()
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun writeTo(sink: okio.BufferedSink) { sink.writeUtf8("{}") }
        } // no contentLength: OkHttp sends it chunked
        val r = client.newCall(Request.Builder().url("$base/api/settings").post(body).build()).execute()
        assertEquals(411, r.code)
    }

    // ------------------------------------------------------------------ settings from the browser

    private fun post(path: String, body: String, cookie: String?, origin: String? = null): Response =
        client.newCall(Request.Builder().url(base + path).post(body.toRequestBody()).apply {
            if (cookie != null) header("Cookie", cookie)
            if (origin != null) header("Origin", origin)
        }.build()).execute()

    private fun leaves(e: com.google.gson.JsonElement, out: MutableList<JsonObject> = ArrayList()): List<JsonObject> {
        val o = e.asJsonObject
        if (o.has("children")) o.getAsJsonArray("children").forEach { leaves(it, out) } else out.add(o)
        return out
    }

    private fun allLeaves(tree: JsonObject) = tree.getAsJsonArray("pages").flatMap { leaves(it) }

    @Test
    fun `settings need pairing and come as the same tree as on the TV`() {
        assertEquals(401, get("/api/settings").code)
        val cookie = pairedCookie()
        val r = get("/api/settings?lang=en", cookie)
        assertEquals(200, r.code)
        val tree = JsonParser.parseString(r.body!!.string()).asJsonObject
        val keys = allLeaves(tree).mapNotNull { it.get("key")?.asString }
        assertTrue(keys.contains("pref_companion_settings"))
        assertTrue(keys.contains("pref_chat_layout"))
        assertFalse("developer page hidden", tree.getAsJsonArray("pages").any { it.asJsonObject.get("key")?.asString == "pref_developer" })
        val password = allLeaves(tree).first { it.get("key")?.asString == "pref_mqtt_password" }
        assertTrue(password.get("secret").asBoolean)
        assertNull("password never sent", password.get("value"))
        assertTrue("MQTT group has the connection card", tree.toString().contains("\"panel\":\"mqtt\""))
        val mqtt = JsonParser.parseString(get("/api/mqtt", cookie).body!!.string()).asJsonObject
        assertEquals("off", mqtt.get("status").asString)
        assertTrue(mqtt.getAsJsonObject("counts").get("controls").asInt > 5)
        assertEquals(401, get("/api/mqtt").code)
    }

    @Test
    fun `valid values are stored, wrong ones refused`() {
        val cookie = pairedCookie()
        val tree = JsonParser.parseString(get("/api/settings", cookie).body!!.string()).asJsonObject
        val list = allLeaves(tree).first { it.get("type")?.asString == "list" && it.getAsJsonArray("options").size() > 1 }
        val key = list.get("key").asString
        val option = list.getAsJsonArray("options")[1].asJsonArray[0].asString
        val body = """{"values":{"$key":"$option","pref_companion_chat":false,"pref_chat_layout":"nonsense","pref_unknown":true,"pref_developer_mode":true}}"""
        val res = JsonParser.parseString(post("/api/settings", body, cookie).body!!.string()).asJsonObject
        assertEquals(2, res.get("stored").asInt)
        val rejected = res.getAsJsonArray("rejected").map { it.asString }.toSet()
        assertEquals(setOf("pref_chat_layout", "pref_unknown", "pref_developer_mode"), rejected)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(option, prefs.getString(key, null))
        assertFalse(prefs.getBoolean("pref_companion_chat", true))
        assertFalse(prefs.getBoolean("pref_developer_mode", false))
    }

    @Test
    fun `home rows are sorted and moved from the browser without a screen`() {
        val cookie = pairedCookie()
        fun places(): Map<String, Int> {
            val tree = JsonParser.parseString(get("/api/settings", cookie).body!!.string()).asJsonObject
            return allLeaves(tree).filter { it.get("key")?.asString?.startsWith("pref_row_move_") == true }
                .associate { it.get("key").asString to it.get("place").asInt }
        }
        val before = places()
        assertEquals(com.shrug.shrugtv.ui.tv.home.HomeRowOrder.DEFAULT.size, before.size)
        assertEquals(1, before.getValue("pref_row_move_followed_live"))
        assertEquals(2, before.getValue("pref_row_move_live_history"))
        // The first one in the tree is the one in first place
        val tree = JsonParser.parseString(get("/api/settings", cookie).body!!.string()).asJsonObject
        val order = allLeaves(tree).mapNotNull { it.get("key")?.asString }.filter { it.startsWith("pref_row_move_") }
        assertEquals("pref_row_move_followed_live", order.first())
        // "Move up" on the second row: it becomes the first
        val res = JsonParser.parseString(post("/api/settings/action", """{"key":"pref_row_move_live_history"}""", cookie).body!!.string()).asJsonObject
        assertTrue(res.get("ok").asBoolean)
        val after = places()
        assertEquals(1, after.getValue("pref_row_move_live_history"))
        assertEquals(2, after.getValue("pref_row_move_followed_live"))
        val order2 = allLeaves(JsonParser.parseString(get("/api/settings", cookie).body!!.string()).asJsonObject)
            .mapNotNull { it.get("key")?.asString }.filter { it.startsWith("pref_row_move_") }
        assertEquals("pref_row_move_live_history", order2.first())
        // Reset
        assertTrue(JsonParser.parseString(post("/api/settings/action", """{"key":"pref_row_order_reset"}""", cookie).body!!.string()).asJsonObject.get("ok").asBoolean)
        assertEquals(before, places())
        // Clearing the caches needs the main thread, which the server thread must not use directly
        assertTrue(JsonParser.parseString(post("/api/settings/action", """{"key":"pref_clear_caches"}""", cookie).body!!.string()).asJsonObject.get("ok").asBoolean)
        // A button that really needs the TV still says so
        val tv = JsonParser.parseString(post("/api/settings/action", """{"key":"pref_request_draw_over_permission"}""", cookie).body!!.string()).asJsonObject
        assertEquals("tv_only", tv.get("error").asString)
    }

    @Test
    fun `foreign origins and switched off settings are refused`() {
        val cookie = pairedCookie()
        assertEquals(403, post("/api/settings", """{"values":{"pref_companion_chat":false}}""", cookie, "http://evil.example").code)
        assertEquals(200, post("/api/settings", """{"values":{"pref_companion_chat":false}}""", cookie, "http://127.0.0.1:$port").code)
        assertEquals(200, post("/api/settings/action", """{"key":"pref_mqtt_status"}""", cookie).code)
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
            .edit().putBoolean(SettingsApi.ALLOW_KEY, false).commit()
        assertEquals(403, get("/api/settings", cookie).code)
        assertEquals(403, post("/api/settings", """{"values":{"pref_companion_chat":true}}""", cookie).code)
    }

    @Test
    fun `a foreign Host header is refused, so DNS rebinding gets nothing (michael-shrugtv-294)`() {
        val cookie = pairedCookie()
        fun withHost(host: String, origin: String? = null) = client.newCall(Request.Builder().url("$base/api/state")
            .header("Cookie", cookie).header("Host", host).apply { if (origin != null) header("Origin", origin) }.build()).execute().code
        assertEquals(403, withHost("evil.example:$port"))
        assertEquals(403, withHost("evil.example"))
        assertEquals(403, withHost("8.8.8.8:$port"))
        assertEquals(200, withHost("127.0.0.1:$port"))
        assertEquals(200, withHost("192.168.0.50:$port"))
        assertEquals(200, withHost("localhost:$port"))
        // the origin is only compared with an allowed host
        val post = { host: String, origin: String ->
            client.newCall(Request.Builder().url("$base/api/settings").header("Cookie", cookie).header("Host", host)
                .header("Origin", origin).post("""{"values":{}}""".toRequestBody()).build()).execute().code
        }
        assertEquals(403, post("evil.example:$port", "http://evil.example:$port"))
    }

    @Test
    fun `a silent connection is closed after the read timeout (michael-shrugtv-294)`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val quiet = CompanionServer(ctx, port + 1, Pairing()) {}
        quiet.start(300)
        try {
            java.net.Socket("127.0.0.1", port + 1).use { s ->
                s.soTimeout = 5_000
                val t0 = System.currentTimeMillis()
                // Sends nothing: the server gives up after its read timeout and closes, so the read ends with -1
                assertEquals(-1, s.getInputStream().read())
                assertTrue(System.currentTimeMillis() - t0 < 4_000)
            }
        } finally {
            quiet.stop()
        }
    }

    @Test
    fun `an open WebSocket outlives the read timeout because the pings are answered (michael-shrugtv-294)`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(com.shrug.shrugtv.prefs.Prefs.companionRequirePin.key, false).commit()
        val before = CompanionServer.pingIntervalMs
        CompanionServer.pingIntervalMs = 300L
        val ws2 = CompanionServer(ctx, port + 2, Pairing()) {}
        ws2.start(1_000)
        try {
            val ended = CountDownLatch(1)
            val ws = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:${port + 2}/ws").build(), object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { ended.countDown() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ended.countDown() }
            })
            // Three times the read timeout without a message from the phone: only the pongs keep it open
            assertFalse("closed within 3 s", ended.await(3, TimeUnit.SECONDS))
            assertTrue(ws.send("{}"))
            ws.close(1000, null)
        } finally {
            CompanionServer.pingIntervalMs = before
            ws2.stop()
        }
    }

    @Test
    fun `allowed hosts are private IP literals and localhost`() {
        for (h in listOf("127.0.0.1:8080", "192.168.1.5", "10.0.0.2:80", "localhost", "LOCALHOST:1", "[::1]:8080", "[fe80::1]"))
            assertTrue(h, CompanionServer.isAllowedHost(h))
        for (h in listOf(null, "", "evil.example", "evil.example:80", "8.8.8.8", "192.168.0.1.evil.example", "tv.local", "[2001:db8::1]:80"))
            assertFalse(h.toString(), CompanionServer.isAllowedHost(h))
    }

    @Test
    fun `backups leave out MQTT and companion settings`() {
        val cookie = pairedCookie()
        val tree = JsonParser.parseString(get("/api/settings", cookie).body!!.string()).asJsonObject
        val byKey = allLeaves(tree).filter { it.has("key") }.associateBy { it.get("key").asString }
        assertTrue(byKey.getValue("pref_mqtt_url").get("noBackup").asBoolean)
        assertTrue(byKey.getValue("pref_companion_chat").get("noBackup").asBoolean)
        assertNull(byKey.getValue("pref_chat_layout").get("noBackup"))
        val body = """{"backup":true,"values":{"pref_mqtt_url":"10.0.0.9","pref_companion_chat":false,"pref_chat_layout":"overlay"}}"""
        val res = JsonParser.parseString(post("/api/settings", body, cookie).body!!.string()).asJsonObject
        assertEquals(1, res.get("stored").asInt)
        assertEquals(setOf("pref_mqtt_url", "pref_companion_chat"), res.getAsJsonArray("skipped").map { it.asString }.toSet())
        val prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
        assertEquals("overlay", prefs.getString("pref_chat_layout", null))
        assertTrue(prefs.getString("pref_mqtt_url", "").isNullOrEmpty())
    }

    @Test
    fun `version page needs pairing and installing needs the switch and our own origin`() {
        assertEquals(401, get("/api/update").code)
        val cookie = pairedCookie()
        val r = get("/api/update", cookie)
        assertEquals(200, r.code)
        val json = JsonParser.parseString(r.body!!.string()).asJsonObject
        assertEquals(com.shrug.shrugtv.BuildConfig.VERSION_NAME, json.get("version").asString)
        assertTrue(json.has("status") && json.has("install") && json.has("install_enabled"))
        // Without a switch the install never starts, a foreign origin is refused, a GET is not a command
        assertEquals(400, get("/api/update/install", cookie).code)
        assertEquals(403, post("/api/update/install", "{}", cookie, "http://evil.example").code)
        val answer = JsonParser.parseString(post("/api/update/install", "{}", cookie, "http://127.0.0.1:$port").body!!.string()).asJsonObject
        assertFalse(answer.get("install_enabled").asBoolean)
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
            .edit().putBoolean(SettingsApi.ALLOW_KEY, false).commit()
        assertEquals(403, post("/api/update/check", "{}", cookie).code)
    }

    @Test
    fun `skin colours reach the page and the manifest follows them, the switch turns it off`() {
        assertEquals(401, get("/api/theme").code)
        val cookie = pairedCookie()
        val theme = JsonParser.parseString(get("/api/theme", cookie).body!!.string()).asJsonObject
        assertTrue(theme.get("on").asBoolean)
        val colors = theme.getAsJsonObject("colors")
        for (k in listOf("background", "surface", "surfaceHigh", "text", "textMuted", "accent", "accentLight", "onAccent", "live")) {
            assertTrue(k, colors.get(k).asString.matches(Regex("#[0-9A-F]{6}")))
        }
        val manifest = JsonParser.parseString(get("/manifest.json").body!!.string()).asJsonObject
        assertEquals(colors.get("background").asString, manifest.get("theme_color").asString)
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
            .edit().putBoolean(com.shrug.shrugtv.prefs.Prefs.companionSkin.key, false).commit()
        val off = JsonParser.parseString(get("/api/theme", cookie).body!!.string()).asJsonObject
        assertFalse(off.get("on").asBoolean)
        assertFalse(off.has("colors"))
        assertEquals("#0B0B0E", JsonParser.parseString(get("/manifest.json").body!!.string()).asJsonObject.get("theme_color").asString)
    }

    @Test
    fun `the page asks for script and style with a version, and those addresses still work`() {
        val html = get("/").body!!.string()
        val js = Regex("""src="(/app\.js\?v=[0-9a-f]+)"""").find(html)?.groupValues?.get(1)
        val css = Regex("""href="(/app\.css\?v=[0-9a-f]+)"""").find(html)?.groupValues?.get(1)
        assertNotNull(js)
        assertNotNull(css)
        assertEquals(200, get(js!!).code)
        assertEquals(200, get(css!!).code)
    }

    @Test
    fun `switcher lists need pairing, follow the TV settings and refuse unknown names`() {
        assertEquals(401, get("/api/lists").code)
        CompanionServer.listTimeoutMs = 300L
        val cookie = pairedCookie()
        // Without Twitch in the test the list fails (502) or comes back empty; both answers name the lists switched on
        val r = JsonParser.parseString(get("/api/lists?name=top_streams", cookie).body!!.string()).asJsonObject
        assertEquals("top_streams", r.get("name").asString)
        assertTrue((r.has("items") || r.has("error")) && r.has("playing"))
        assertEquals(6, r.getAsJsonArray("enabled").size())
        assertEquals(404, get("/api/lists?name=nonsense", cookie).code)
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
            .edit().putBoolean("pref_switcher_list_top_language", false).commit()
        val off = JsonParser.parseString(get("/api/lists", cookie).body!!.string()).asJsonObject
        assertEquals(5, off.getAsJsonArray("enabled").size())
        assertFalse(off.getAsJsonArray("enabled").map { it.asString }.contains("top_language"))
    }

    /** Twitch as the test wants it: [answer] gives the Helix answer per request name, null = no answer. */
    private fun fakeTwitch(answer: (String) -> JsonObject?) {
        BrowseApi.source = object : BrowseApi.Source {
            override suspend fun searchChannels(context: Context, q: String) = answer("channels")
            override suspend fun searchCategories(context: Context, q: String) = answer("categories")
            override suspend fun streamsOfCategory(context: Context, gameId: Long) = answer("streams")
            override suspend fun users(context: Context, ids: List<String>) = answer("users")
            override suspend fun videos(context: Context, userId: String) = answer("videos")
            override suspend fun clips(context: Context, userId: String) = answer("clips")
        }
    }

    private fun data(vararg rows: String) = JsonParser.parseString("""{"data":[${rows.joinToString(",")}]}""").asJsonObject

    @Test
    fun `a failed Twitch request is an error, not an empty list (michael-shrugtv-355)`() {
        val cookie = pairedCookie()
        fakeTwitch { null }
        for (path in listOf("/api/search?q=failsearch", "/api/category?id=33214", "/api/channel/videos?id=12826", "/api/channel/clips?id=12826")) {
            val r = get(path, cookie)
            assertEquals(path, 502, r.code)
            val json = JsonParser.parseString(r.body!!.string()).asJsonObject
            assertEquals(path, "twitch", json.get("error").asString)
            assertFalse(path, json.has("items"))
        }
        // An error object from Helix counts as no answer, too
        fakeTwitch { if (it == "categories") JsonParser.parseString("""{"error":"Unauthorized","status":401}""").asJsonObject else data() }
        assertEquals(502, get("/api/search?q=halfsearch", cookie).code)
        // A switcher list that does not answer within the timeout: error, but the page still learns which lists are on
        CompanionServer.listTimeoutMs = 1L
        val list = get("/api/lists?name=top_streams", cookie)
        assertEquals(502, list.code)
        val json = JsonParser.parseString(list.body!!.string()).asJsonObject
        assertEquals("twitch", json.get("error").asString)
        assertFalse(json.has("items"))
        assertTrue(json.getAsJsonArray("enabled").size() > 0)
    }

    @Test
    fun `search finds categories, a category lists its live channels (michael-shrugtv-333)`() {
        val cookie = pairedCookie()
        fakeTwitch {
            when (it) {
                "channels" -> data("""{"id":"4711","broadcaster_login":"somechannel","display_name":"SomeChannel","is_live":true,"thumbnail_url":"https://x/a.png"}""")
                "categories" -> data("""{"id":"33214","name":"Some Game","box_art_url":"https://x/art/33214-52x72.jpg"}""", """{"id":"no number","name":"bad"}""")
                "streams" -> data("""{"user_id":"1","user_login":"small","user_name":"Small","game_name":"Some Game","viewer_count":5,"thumbnail_url":"https://x/live_small-{width}x{height}.jpg"}""",
                    """{"user_id":"2","user_login":"big","user_name":"Big","game_name":"Some Game","viewer_count":900}""")
                "users" -> data("""{"id":"2","profile_image_url":"https://x/big.png"}""")
                else -> data()
            }
        }
        assertEquals(200, get("/api/search?q=", cookie).code)
        val s = JsonParser.parseString(get("/api/search?q=okSearch", cookie).body!!.string()).asJsonObject
        assertEquals("somechannel", s.getAsJsonArray("items")[0].asJsonObject.get("login").asString)
        val cats = s.getAsJsonArray("categories")
        assertEquals(1, cats.size())
        assertEquals("https://x/art/33214-144x192.jpg", cats[0].asJsonObject.get("image").asString)
        assertEquals(400, get("/api/category?id=abc", cookie).code)
        val c = JsonParser.parseString(get("/api/category?id=33214", cookie).body!!.string()).asJsonObject
        val items = c.getAsJsonArray("items")
        assertEquals(listOf("big", "small"), items.map { it.asJsonObject.get("login").asString })
        assertEquals("https://x/big.png", items[0].asJsonObject.get("avatar").asString)
        assertEquals("https://x/live_small-320x180.jpg", items[1].asJsonObject.get("image").asString)
    }

    @Test
    fun `recordings and clips of a channel come with what the watch command needs (michael-shrugtv-333)`() {
        val cookie = pairedCookie()
        fakeTwitch {
            when (it) {
                "videos" -> data("""{"id":"2233445566","title":"Stream","duration":"1h2m3s","view_count":12,"published_at":"2026-11-20T18:00:00Z",
                    "thumbnail_url":"https://x/v/%{width}x%{height}.jpg"}""", """{"id":"not-a-number"}""")
                "clips" -> data("""{"id":"FunnyClip-abc_123","title":"Clip","duration":27.6,"view_count":99,"thumbnail_url":"https://x/c.jpg"}""")
                else -> data()
            }
        }
        val v = JsonParser.parseString(get("/api/channel/videos?id=12826", cookie).body!!.string()).asJsonObject.getAsJsonArray("items")
        assertEquals(1, v.size())
        assertEquals("2233445566", v[0].asJsonObject.get("vod_id").asString)
        assertEquals(3723L, v[0].asJsonObject.get("duration_s").asLong)
        assertEquals("https://x/v/320x180.jpg", v[0].asJsonObject.get("image").asString)
        val c = JsonParser.parseString(get("/api/channel/clips?id=12826", cookie).body!!.string()).asJsonObject.getAsJsonArray("items")
        assertEquals("FunnyClip-abc_123", c[0].asJsonObject.get("clip").asString)
        assertEquals(27L, c[0].asJsonObject.get("duration_s").asLong)
        assertEquals(401, get("/api/channel/clips?id=12826").code)
    }

    @Test
    fun `the statistics short view answers today and the week`() {
        val cookie = pairedCookie()
        val r = get("/api/stats", cookie)
        assertEquals(200, r.code)
        val json = JsonParser.parseString(r.body!!.string()).asJsonObject
        assertTrue(json.getAsJsonObject("today").has("total_s"))
        assertTrue(json.getAsJsonObject("week").has("sessions"))
    }

    @Test
    fun `stars from the phone are checked and stored`() {
        assertEquals(401, get("/api/favorites").code)
        val cookie = pairedCookie()
        val own = "http://127.0.0.1:$port"
        assertEquals(400, post("/api/favorite", """{"id":"abc","on":true}""", cookie, own).code)
        assertEquals(400, post("/api/favorite", """{"id":"123"}""", cookie, own).code)
        assertEquals(403, post("/api/favorite", """{"id":"123","on":true}""", cookie, "http://evil.example").code)
        assertEquals(200, post("/api/favorite", """{"id":"4242","on":true}""", cookie, own).code)
        fun ids() = JsonParser.parseString(get("/api/favorites", cookie).body!!.string()).asJsonObject.getAsJsonArray("ids").map { it.asString }
        assertTrue(ids().contains("4242"))
        assertEquals(200, post("/api/favorite", """{"id":"4242","on":false}""", cookie, own).code)
        assertFalse(ids().contains("4242"))
    }

    @Test
    fun `log needs pairing and can be switched off`() {
        assertEquals(401, get("/api/log").code)
        val cookie = pairedCookie()
        val r = get("/api/log?after=0", cookie)
        assertEquals(200, r.code)
        val json = JsonParser.parseString(r.body!!.string()).asJsonObject
        assertTrue(json.has("lines") && json.has("first"))
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
            .edit().putBoolean("pref_companion_log", false).commit()
        assertEquals(403, get("/api/log", cookie).code)
    }

    @Test
    fun `remembered devices come back without a code after a restart`() {
        server.shutdown()
        val memory = object : TrustedDevices.Store {
            var json: String? = null
            override fun load() = json
            override fun save(json: String) { this.json = json }
        }
        val devices = TrustedDevices(memory)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        server = CompanionServer(ctx, port, pairing, devices) {}
        server.start(0)

        val offer = pairing.newOffer()
        val setCookie = get("/p/${offer.token}").header("Set-Cookie")!!
        assertTrue("persistent cookie", setCookie.contains("Max-Age="))
        val cookie = setCookie.substringBefore(";")
        assertEquals(1, devices.list().size)

        // App restart: sessions in memory are gone, the device key still works
        pairing.disconnectAll()
        assertEquals(200, get("/api/state", cookie).code)
        val list = JsonParser.parseString(get("/api/devices", cookie).body!!.string()).asJsonObject
        assertEquals(list.getAsJsonArray("devices")[0].asJsonObject.get("id").asString, list.get("current").asString)

        // Removed on the TV: the code is needed again
        devices.remove(devices.list()[0].id)
        assertEquals(401, get("/api/state", cookie).code)

        // "Remember connected devices" off: a plain session cookie, gone after a restart
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean("pref_companion_remember", false).commit()
        val offer2 = pairing.newOffer()
        val plain = get("/p/${offer2.token}").header("Set-Cookie")!!
        assertFalse(plain.contains("Max-Age="))
        pairing.disconnectAll()
        assertEquals(401, get("/api/state", plain.substringBefore(";")).code)
    }

    @Test
    fun `stopping the server ends open websockets, so a restart pushes removed devices out (michael-shrugtv-341)`() {
        val cookie = pairedCookie()
        val opened = CountDownLatch(1)
        val ended = CountDownLatch(1)
        client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/ws").header("Cookie", cookie).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { opened.countDown() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { ended.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ended.countDown() }
        })
        assertTrue("open within 5 s", opened.await(5, TimeUnit.SECONDS))
        server.stop()
        assertTrue("ended within 3 s", ended.await(3, TimeUnit.SECONDS))
        // The port is free again at once
        server.start(0)
        assertEquals(200, get("/").code)
    }

    @Test
    fun `a refused body does not spoil the next request on the same connection (michael-shrugtv-341)`() {
        val cookie = pairedCookie()
        val big = "{\"values\":{\"x\":\"" + "a".repeat(100_000) + "\"}}"
        assertEquals(400, post("/api/settings", big, cookie).code)
        assertEquals(403, post("/api/settings", "{\"values\":{}}", cookie, "http://evil.example").code)
        assertEquals(200, get("/api/state", cookie).code)
    }

    @Test
    fun `validation follows the entry type`() {
        val toggle = Entry.Toggle("k", "t", null, false, null, null, null)
        val list = Entry.ListChoice("k", "t", null, listOf("A", "B"), listOf("a", "b"), "a", null)
        val text = Entry.TextField("k", "t", null, "", null, null)
        assertEquals(true, SettingsApi.validate(toggle, JsonPrimitive(true)))
        assertNull(SettingsApi.validate(toggle, JsonPrimitive("true")))
        assertEquals("b", SettingsApi.validate(list, JsonPrimitive("b")))
        assertNull(SettingsApi.validate(list, JsonPrimitive("c")))
        assertEquals("hello", SettingsApi.validate(text, JsonPrimitive("hello")))
        assertNull(SettingsApi.validate(text, JsonPrimitive("x".repeat(SettingsApi.MAX_TEXT + 1))))
        assertNull(SettingsApi.validate(text, JsonPrimitive("line\nbreak")))
        assertNull(SettingsApi.validate(text, JsonObject()))
        val number = Entry.NumberField("k", "t", null, 78, 20, 300, 5, null)
        assertEquals("120", SettingsApi.validate(number, JsonPrimitive(120)))
        assertEquals("120", SettingsApi.validate(number, JsonPrimitive("120")))
        assertNull(SettingsApi.validate(number, JsonPrimitive(19)))
        assertNull(SettingsApi.validate(number, JsonPrimitive(301)))
        assertNull(SettingsApi.validate(number, JsonPrimitive(12.5)))
        assertNull(SettingsApi.validate(number, JsonPrimitive("abc")))
        assertNull(SettingsApi.validate(number, JsonPrimitive(true)))
    }
}
