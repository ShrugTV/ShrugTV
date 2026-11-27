package com.shrug.shrugtv.ha

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.shrug.shrugtv.prefs.Prefs
import com.shrug.shrugtv.security.SecretStore
import java.security.MessageDigest

/**
 * Settings group "Home Assistant (MQTT)". The password is moved out of the normal settings into
 * `Mqtt.xml`, encrypted by [SecretStore] and excluded from backups like `User.xml`;
 * the settings then only show [MASK].
 */
data class MqttSettings(
    val enabled: Boolean,
    val url: String,
    val user: String,
    val password: String,
    val deviceId: String,
    val deviceName: String,
    val commandsEnabled: Boolean,
    val topicPrefix: String = DEFAULT_TOPIC_PREFIX,
    val discoveryPrefix: String = DEFAULT_DISCOVERY_PREFIX,
    /** Seconds between state updates while something plays (uptime, VOD position). */
    val intervalS: Int = DEFAULT_INTERVAL_S,
    val groups: Set<String> = HaDiscovery.GROUPS.toSet(),
    /** Short hash of this TV (ANDROID_ID): unique client id, and a suffix when the name is taken. */
    val deviceHash: String = ""
) {
    val isUsable: Boolean get() = enabled && (url.startsWith("tcp://") || url.startsWith("ssl://"))

    companion object {
        const val MASK = "••••••••"
        const val DEFAULT_TOPIC_PREFIX = "shrugtv"
        const val DEFAULT_DISCOVERY_PREFIX = "homeassistant"
        const val DEFAULT_INTERVAL_S = 60
        const val SECRET_FILE = "Mqtt"

        @JvmStatic
        fun load(context: Context): MqttSettings {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            // A newly typed password replaces the stored one; the mask means "unchanged"
            val typed = prefs.getString("pref_mqtt_password", "") ?: ""
            if (typed.isNotEmpty() && typed != MASK) {
                SecretStore.put(context, SECRET_FILE, SecretStore.MQTT_PASSWORD, typed)
                prefs.edit().putString("pref_mqtt_password", MASK).apply()
            } else if (typed.isEmpty() && SecretStore.contains(context, SECRET_FILE, SecretStore.MQTT_PASSWORD)) {
                SecretStore.remove(context, SECRET_FILE, SecretStore.MQTT_PASSWORD)
            }
            val name = Prefs.mqttDeviceName.get(prefs).trim()
            val hash = deviceHash(context)
            return MqttSettings(
                enabled = Prefs.mqttEnabled.get(prefs),
                url = normaliseUrl(Prefs.mqttUrl.get(prefs)),
                user = Prefs.mqttUser.get(prefs).trim(),
                password = SecretStore.get(context, SECRET_FILE, SecretStore.MQTT_PASSWORD),
                deviceId = HaDiscovery.deviceIdFrom(name, hash),
                deviceName = name.ifEmpty { "ShrugTV ${Build.MODEL}" },
                commandsEnabled = Prefs.mqttCommands.get(prefs),
                topicPrefix = topicPrefix(Prefs.mqttTopicPrefix.get(prefs)),
                discoveryPrefix = discoveryPrefix(Prefs.mqttDiscoveryPrefix.get(prefs)),
                intervalS = Prefs.mqttInterval.get(prefs).coerceIn(5, 3_600),
                groups = buildSet {
                    if (Prefs.mqttEntitiesInfo.get(prefs)) add(HaDiscovery.INFO)
                    if (Prefs.mqttEntitiesAvatar.get(prefs)) add(HaDiscovery.AVATAR)
                    if (Prefs.mqttEntitiesPreview.get(prefs)) add(HaDiscovery.PREVIEW)
                    if (Prefs.mqttEntitiesEvents.get(prefs)) add(HaDiscovery.EVENTS)
                    if (Prefs.mqttEntitiesFollowed.get(prefs)) add(HaDiscovery.FOLLOWED)
                    add(HaDiscovery.CONTROLS) // switched by "Allow control from Home Assistant"
                },
                deviceHash = hash
            )
        }

        /**
         * First topic levels of other systems: a topic prefix there would write into their topics
         * (zigbee2mqtt/<name>/…, tasmota's tele/stat/cmnd, Home Assistant's own …).
         */
        val FOREIGN_ROOTS = setOf(
            "homeassistant", "zigbee2mqtt", "zwave", "zwavejs", "zwave2mqtt", "tasmota", "tele", "stat", "cmnd",
            "esphome", "shellies", "shelly", "shellyplus", "frigate", "owntracks", "valetudo", "rtl_433",
            "deconz", "ring", "teslamate", "wled", "openmqttgateway"
        )

        private val TOPIC_PATH = Regex("^[A-Za-z0-9_\\-]+(/[A-Za-z0-9_\\-]+)*$")

        /** A topic path without wildcards ("home/tv"), else null. */
        private fun cleanPath(raw: String?): String? {
            val p = (raw ?: "").trim()
            return if (TOPIC_PATH.matches(p) && p.length <= 64) p else null
        }

        /**
         * ShrugTV's own topics: a clean path whose first level is not one of [FOREIGN_ROOTS];
         * otherwise "shrugtv".
         */
        @JvmStatic
        fun topicPrefix(raw: String?): String {
            val p = cleanPath(raw) ?: return DEFAULT_TOPIC_PREFIX
            return if (p.substringBefore('/').lowercase() in FOREIGN_ROOTS) DEFAULT_TOPIC_PREFIX else p
        }

        /** Home Assistant's discovery prefix: a clean path, otherwise "homeassistant". */
        @JvmStatic
        fun discoveryPrefix(raw: String?): String = cleanPath(raw) ?: DEFAULT_DISCOVERY_PREFIX

        /** "192.168.0.10" -> "tcp://192.168.0.10:1883"; keeps tcp:// and ssl:// URLs. */
        @JvmStatic
        fun normaliseUrl(raw: String): String {
            val u = raw.trim()
            if (u.isEmpty()) return ""
            if (u.startsWith("tcp://") || u.startsWith("ssl://")) return u
            if (u.startsWith("mqtt://")) return "tcp://" + u.removePrefix("mqtt://").let { if (':' in it) it else "$it:1883" }
            if (u.startsWith("mqtts://")) return "ssl://" + u.removePrefix("mqtts://").let { if (':' in it) it else "$it:8883" }
            return "tcp://" + if (':' in u) u else "$u:1883"
        }

        @SuppressLint("HardwareIds")
        private fun deviceHash(context: Context): String {
            val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            return digest.take(4).joinToString("") { "%02x".format(it) }
        }
    }
}
