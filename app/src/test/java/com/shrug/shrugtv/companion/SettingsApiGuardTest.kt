package com.shrug.shrugtv.companion

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.shrug.shrugtv.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Keys that decide who reaches or steers the TV need a paired phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsApiGuardTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit()
    }

    private fun pin(on: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(Prefs.companionRequirePin.key, on).commit()

    private fun values(vararg kv: Pair<String, Any>) = JsonObject().apply {
        for ((k, v) in kv) add(k, if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()))
    }

    @Test
    fun guardedKeys() {
        assertTrue(SettingsApi.guarded("pref_companion_require_pin"))
        assertTrue(SettingsApi.guarded("pref_companion_settings"))
        assertTrue(SettingsApi.guarded("pref_update_install"))
        assertTrue(SettingsApi.guarded("pref_mqtt_url"))
        assertTrue(SettingsApi.guarded("pref_mqtt_commands"))
        assertFalse(SettingsApi.guarded("pref_mqtt_interval"))
        assertFalse(SettingsApi.guarded("pref_update_check"))
        assertFalse(SettingsApi.guarded("pref_join_chat"))
    }

    @Test
    fun withoutPinGuardedKeysAreRejectedAndTheRestIsStored() {
        pin(false)
        val res = SettingsApi.apply(ctx, values("pref_companion_require_pin" to true, "pref_join_chat" to false))
        assertEquals("tv only", res.get("error").asString)
        assertEquals(listOf("pref_companion_require_pin"), res.getAsJsonArray("tv_only").map { it.asString })
        assertEquals(1, res.get("stored").asInt)
        val p = PreferenceManager.getDefaultSharedPreferences(ctx)
        assertFalse(p.getBoolean("pref_companion_require_pin", false))
        assertFalse(p.getBoolean("pref_join_chat", true))
    }

    @Test
    fun withPinAPairedPhoneMayChangeThem() {
        pin(true)
        val res = SettingsApi.apply(ctx, values("pref_companion_require_pin" to false))
        assertFalse(res.has("error"))
        assertEquals(1, res.get("stored").asInt)
        assertFalse(PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("pref_companion_require_pin", true))
    }

    @Test
    fun theTreeMarksThemOnlyWithoutPin() {
        pin(false)
        assertTrue(tvOnlyKeys().contains("pref_companion_require_pin"))
        assertFalse(tvOnlyKeys().contains("pref_join_chat"))
        pin(true)
        assertTrue(tvOnlyKeys().isEmpty())
    }

    private fun tvOnlyKeys(): Set<String> {
        val out = HashSet<String>()
        fun walk(o: com.google.gson.JsonElement) {
            when {
                o.isJsonObject -> {
                    val j = o.asJsonObject
                    if (j.get("tv_only")?.asBoolean == true) out += j.get("key").asString
                    j.entrySet().forEach { walk(it.value) }
                }
                o.isJsonArray -> o.asJsonArray.forEach { walk(it) }
            }
        }
        walk(SettingsApi.tree(ctx, "en"))
        return out
    }
}
