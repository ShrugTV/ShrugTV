package com.shrug.shrugtv.ha

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.PreferenceManager
import com.shrug.shrugtv.BuildConfig
import com.shrug.shrugtv.hub.HubCommandRouter
import com.shrug.shrugtv.hub.PlaybackStateHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Home Assistant integration via MQTT (docs/mqtt_ha_plan.md). Connected while ShrugTV is in the
 * foreground (and 2 minutes after), announces the TV via discovery, publishes the player state and
 * events, and passes commands to [HubCommandRouter]. The Last Will marks the TV offline when the app
 * dies or the TV is switched off.
 */
object MqttBridge : DefaultLifecycleObserver {
    private const val TAG = "MqttBridge"
    private const val LINGER_MS = 120_000L
    private const val FOLLOWED_INTERVAL_MS = 60_000L

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var client: Mqtt3AsyncClient? = null
    @Volatile private var discovery: HaDiscovery? = null
    private var jobs: List<Job> = emptyList()
    private var publishedQualities: List<String>? = null
    private val stopRunnable = Runnable { stop() }

    /** Last connection state for the settings ("Connection status"). */
    @Volatile var status: String = "off"
        private set

    /** For the connection card in the browser. */
    @Volatile private var settings: MqttSettings? = null
    @Volatile private var lastPublishMs = 0L
    @Volatile private var orphaned: HaDiscovery? = null
    @Volatile private var connectedSinceMs = 0L

    /** Connection, broker, topics and entity counts as JSON (companion settings page). */
    fun info(context: Context): com.google.gson.JsonObject {
        val s = settings ?: MqttSettings.load(context)
        val d = discovery ?: HaDiscovery(s.deviceId, s.deviceName, android.os.Build.MODEL, BuildConfig.VERSION_NAME, s.commandsEnabled,
            s.topicPrefix, s.discoveryPrefix, s.groups)
        val now = System.currentTimeMillis()
        return com.google.gson.JsonObject().apply {
            addProperty("status", status)
            addProperty("connected", status == "connected")
            addProperty("enabled", s.enabled)
            addProperty("broker", s.url)
            addProperty("tls", s.url.startsWith("ssl://"))
            addProperty("device_id", d.deviceId)
            if (d.deviceId != s.deviceId) addProperty("name_taken", true)
            addProperty("device_name", s.deviceName)
            addProperty("state_topic", d.stateTopic)
            addProperty("command_topic", d.commandWildcard)
            addProperty("discovery_prefix", s.discoveryPrefix)
            if (lastPublishMs > 0) addProperty("last_publish_s", (now - lastPublishMs) / 1000)
            if (connectedSinceMs > 0 && status == "connected") addProperty("connected_s", (now - connectedSinceMs) / 1000)
            add("counts", com.google.gson.JsonObject().apply { d.counts().forEach { (k, v) -> addProperty(k, v) } })
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        // Replacing a typed password by the mask is our own write, not a change by the user
        val ownMaskWrite = key == "pref_mqtt_password" && prefs.getString(key, null) == MqttSettings.MASK
        if (key != null && key.startsWith("pref_mqtt_") && !ownMaskWrite) {
            main.removeCallbacks(restartRunnable)
            main.postDelayed(restartRunnable, 1_500L)
        }
    }
    private val restartRunnable = Runnable { stop(); start() }

    fun init(context: Context) {
        appContext = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        PreferenceManager.getDefaultSharedPreferences(appContext).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStart(owner: LifecycleOwner) {
        main.removeCallbacks(stopRunnable)
        if (client == null) start()
    }

    override fun onStop(owner: LifecycleOwner) {
        main.removeCallbacks(stopRunnable)
        main.postDelayed(stopRunnable, LINGER_MS)
    }

    @Synchronized
    fun start() {
        if (client != null) return
        val s = MqttSettings.load(appContext)
        if (!s.isUsable) {
            status = if (s.enabled) "no broker address" else "off"
            return
        }
        val d = HaDiscovery(idFor(s), s.deviceName, android.os.Build.MODEL, BuildConfig.VERSION_NAME, s.commandsEnabled,
            s.topicPrefix, s.discoveryPrefix, s.groups)
        // New device id or prefixes: the old entities are removed after connecting (no orphans in HA)
        discovery?.let { old -> if (old.base != d.base || old.discoveryRoot != d.discoveryRoot) orphaned = old }
        discovery = d
        settings = s
        try {
            val uri = URI(s.url)
            val tls = s.url.startsWith("ssl://")
            var everConnected = false
            // Client id with the device hash: two TVs with the same name must not kick each other off
            val builder = MqttClient.builder().useMqttVersion3()
                .identifier("shrugtv-${d.deviceId}-${s.deviceHash}")
                .serverHost(uri.host.removeSurrounding("[", "]"))
                .serverPort(if (uri.port > 0) uri.port else if (tls) 8883 else 1883)
                .transportConfig().mqttConnectTimeout(10, TimeUnit.SECONDS).applyTransportConfig()
                // Reconnects after a lost connection and after a failed first attempt, up to once a minute
                .automaticReconnect().initialDelay(1, TimeUnit.SECONDS).maxDelay(60, TimeUnit.SECONDS).applyAutomaticReconnect()
            if (tls) builder.sslWithDefaultConfig()
            lateinit var c: Mqtt3AsyncClient
            builder.addConnectedListener {
                everConnected = true
                status = "connected"
                connectedSinceMs = System.currentTimeMillis()
                Log.i(TAG, "Connected to ${s.url}")
                onConnected(c, d, s)
            }
            builder.addDisconnectedListener { ctx ->
                // Stopped or replaced: no reconnect for this client
                if (client !== c) { ctx.reconnector.reconnect(false); return@addDisconnectedListener }
                status = (if (everConnected) "connection lost: " else "cannot connect: ") + (ctx.cause.message ?: "?")
                Log.w(TAG, status)
            }
            c = builder.buildAsync()
            // Every message no subscription callback took (the owner check has its own): the commands
            c.publishes(MqttGlobalPublishFilter.REMAINING) { msg ->
                if (!s.commandsEnabled) return@publishes
                val name = msg.topic.toString().substringAfterLast('/')
                if (name in HubCommandRouter.NAMES) {
                    HubCommandRouter.dispatch(appContext, name, String(msg.payloadAsBytes, Charsets.UTF_8).take(2_000))
                }
            }
            client = c
            status = "connecting"
            c.connectWith()
                .cleanSession(true)
                .keepAlive(30)
                .apply {
                    if (s.user.isNotEmpty()) simpleAuth().username(s.user)
                        .apply { if (s.password.isNotEmpty()) password(s.password.toByteArray(Charsets.UTF_8)) }
                        .applySimpleAuth()
                }
                .willPublish().topic(d.availabilityTopic).payload("offline".toByteArray()).qos(MqttQos.AT_LEAST_ONCE).retain(true).applyWillPublish()
                .send()
        } catch (e: Exception) {
            status = "error: ${e.message}"
            Log.w(TAG, "MQTT start failed", e)
            client = null
        }
    }

    /** Device id to use: the one from the name, or "<id>_<hash>" if another TV already uses that name. */
    private fun idFor(s: MqttSettings): String =
        appContext.getSharedPreferences(MqttSettings.SECRET_FILE, Context.MODE_PRIVATE).getString(overrideKey(s), null) ?: s.deviceId

    private fun overrideKey(s: MqttSettings) = "id_for_${s.topicPrefix}/${s.deviceId}"

    private fun onConnected(c: Mqtt3AsyncClient, d: HaDiscovery, s: MqttSettings) {
        // Off the client's network thread: the owner check waits for a retained message
        scope.launch { claimAndPublish(c, d, s) }
    }

    private suspend fun claimAndPublish(c: Mqtt3AsyncClient, d: HaDiscovery, s: MqttSettings) {
        val owner = readRetained(c, d.ownerTopic)
        if (!owner.isNullOrEmpty() && owner != s.deviceHash && d.deviceId == s.deviceId) {
            // Another TV already uses this name: switch to "<id>_<hash>" and reconnect, so the Last
            // Will points to our own topics; nothing was sent on the other TV's topics.
            val newId = "${s.deviceId}_${s.deviceHash.take(4)}"
            Log.w(TAG, "Device id ${s.deviceId} belongs to another TV, using $newId")
            appContext.getSharedPreferences(MqttSettings.SECRET_FILE, Context.MODE_PRIVATE).edit().putString(overrideKey(s), newId).apply()
            main.post {
                synchronized(this) { discovery = null; orphaned = null }
                stop()
                start()
            }
            return
        }
        publish(c, d.ownerTopic, s.deviceHash, retained = true)
        orphaned?.let { old ->
            orphaned = null
            for ((topic, _) in old.configs(emptyList())) publish(c, topic, "", retained = true)
            publish(c, old.stateTopic, "", retained = true)
            publish(c, old.availabilityTopic, "", retained = true)
            publish(c, old.ownerTopic, "", retained = true)
        }
        publish(c, d.availabilityTopic, "online", retained = true)
        publishedQualities = null
        publishDiscovery(c, d, PlaybackStateHub.state.value.qualities)
        if (s.commandsEnabled) {
            // Without a callback: commands go to the one handler set up in start(), also after a reconnect
            c.subscribeWith().topicFilter(d.commandWildcard).qos(MqttQos.AT_LEAST_ONCE).send()
                .whenComplete { _, e -> if (e != null) Log.w(TAG, "subscribe", e) }
        }
        publishState(c, d)
        if (jobs.isEmpty()) startCollectors()
    }

    /** The retained message on [topic], or null if there is none within 1.5 s. */
    private suspend fun readRetained(c: Mqtt3AsyncClient, topic: String): String? {
        val got = kotlinx.coroutines.CompletableDeferred<String>()
        return try {
            c.subscribeWith().topicFilter(topic).qos(MqttQos.AT_LEAST_ONCE)
                .callback { msg -> got.complete(String(msg.payloadAsBytes, Charsets.UTF_8)) }.send()
            val value = kotlinx.coroutines.withTimeoutOrNull(1_500L) { got.await() }
            c.unsubscribeWith().topicFilter(topic).send()
            value
        } catch (e: Exception) {
            Log.w(TAG, "owner check: ${e.message}")
            null
        }
    }

    @OptIn(FlowPreview::class)
    private fun startCollectors() {
        jobs = listOf(
            scope.launch {
                PlaybackStateHub.state.debounce(1_000L).collect {
                    val c = client ?: return@collect
                    val d = discovery ?: return@collect
                    if (it.qualities != publishedQualities) publishDiscovery(c, d, it.qualities)
                    publishState(c, d)
                }
            },
            scope.launch {
                // Uptime and VOD position move without a state change
                while (true) {
                    delay((settings?.intervalS ?: MqttSettings.DEFAULT_INTERVAL_S) * 1_000L)
                    val c = client ?: continue
                    val d = discovery ?: continue
                    if (PlaybackStateHub.state.value.isActive) publishState(c, d)
                }
            },
            scope.launch {
                PlaybackStateHub.events.collect { e ->
                    val c = client ?: return@collect
                    val d = discovery ?: return@collect
                    publish(c, d.eventTopic, e.toJson().toString(), retained = false)
                    // Someone went live: refresh the followed list at once
                    if (e.type == com.shrug.shrugtv.hub.HubEvent.FOLLOWED_LIVE) publishFollowed(c, d)
                }
            },
            scope.launch {
                // Followed channels for dashboards: once a minute while the group is on
                while (true) {
                    val c = client
                    val d = discovery
                    if (c != null && d != null) publishFollowed(c, d)
                    delay(FOLLOWED_INTERVAL_MS)
                }
            }
        )
    }

    private fun publishDiscovery(c: Mqtt3AsyncClient, d: HaDiscovery, qualities: List<String>) {
        publishedQualities = qualities
        followedSignature = null
        for ((topic, payload) in d.configs(qualities)) publish(c, topic, payload, retained = true)
        // Group off: also remove a message of an earlier run that is still retained on the broker
        if (settings?.groups?.contains(HaDiscovery.FOLLOWED) != true) publish(c, d.followedTopic, "", retained = true)
    }

    @Volatile private var followedSignature: String? = null
    private val followedLock = Any()

    /**
     * Fetches who is live and who is followed and publishes it, unless the group is off or nothing changed. Both lists
     * load in parallel in the bridge's scope (before one after the other in runBlocking); the
     * followed channels come from the cache of FollowedChannels when home asked for them within the last minute.
     */
    private suspend fun publishFollowed(c: Mqtt3AsyncClient, d: HaDiscovery) {
        val groups = settings?.groups ?: return
        if (HaDiscovery.FOLLOWED !in groups) {
            // Group switched off: the retained message goes away with the sensors
            if (followedSignature != null) { publish(c, d.followedTopic, "", retained = true); followedSignature = null }
            return
        }
        val (live, followed) = try {
            coroutineScope {
                val liveAsync = async { com.shrug.shrugtv.ui.tv.home.HomeData.followedLive(appContext) }
                val allAsync = async { com.shrug.shrugtv.ui.tv.home.HomeData.followedChannels(appContext) }
                val liveMovies = liveAsync.await()
                val all = allAsync.await()
                val favorites = com.shrug.shrugtv.session.AppState.favoriteChannelIds
                fun item(m: com.shrug.shrugtv.model.Movie) = FollowedList.Item(
                    login = m.login ?: "", name = m.displayName.ifEmpty { m.login ?: "" }, game = m.gameName,
                    title = m.title ?: "", viewers = m.viewerCount,
                    favorite = m.isFavorite || m.userId.toString() in favorites
                )
                liveMovies.filter { !it.login.isNullOrEmpty() }.map(::item) to all.filter { !it.login.isNullOrEmpty() }.map(::item)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "followed: ${e.message}")
            return
        }
        // Without a login or without a network both lists are empty: keep what HA has instead of wiping it
        if (live.isEmpty() && followed.isEmpty() && followedSignature != null) return
        synchronized(followedLock) {
            val signature = FollowedList.signature(live, followed)
            if (signature == followedSignature) return
            followedSignature = signature
            publish(c, d.followedTopic, FollowedList.build(live, followed, System.currentTimeMillis()), retained = true)
        }
    }

    private fun publishState(c: Mqtt3AsyncClient, d: HaDiscovery) {
        val json = PlaybackStateHub.state.value.toJson(System.currentTimeMillis(), BuildConfig.VERSION_NAME)
        publish(c, d.stateTopic, json.toString(), retained = true)
    }

    private fun publish(c: Mqtt3AsyncClient, topic: String, payload: String, retained: Boolean) {
        try {
            if (c.state.isConnected) {
                c.publishWith().topic(topic).payload(payload.toByteArray(Charsets.UTF_8)).qos(MqttQos.AT_LEAST_ONCE).retain(retained).send()
                    .whenComplete { _, e -> if (e != null) Log.w(TAG, "publish $topic: ${e.message}") }
                lastPublishMs = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.w(TAG, "publish $topic: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(stopRunnable)
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        val c = client ?: return
        client = null
        val d = discovery
        scope.launch {
            try {
                // Clean goodbye; the Last Will only fires on an unclean disconnect
                if (c.state.isConnected && d != null) {
                    c.publishWith().topic(d.availabilityTopic).payload("offline".toByteArray()).qos(MqttQos.AT_LEAST_ONCE).retain(true)
                        .send().get(2_000, TimeUnit.MILLISECONDS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "goodbye: ${e.message}")
            }
            try {
                // Also ends a running reconnect
                c.disconnect().get(3_000, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "disconnect: ${e.message}")
            }
        }
        status = "off"
    }
}
