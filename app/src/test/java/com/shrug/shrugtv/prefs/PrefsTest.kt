package com.shrug.shrugtv.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.shrug.shrugtv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reading and writing through the descriptors keeps the stored types of existing installations. */
@RunWith(RobolectricTestRunner::class)
class PrefsTest {
    private lateinit var ctx: Context
    private lateinit var p: SharedPreferences

    @Before
    fun clear() {
        ctx = ApplicationProvider.getApplicationContext()
        p = PreferenceManager.getDefaultSharedPreferences(ctx)
        p.edit().clear().commit()
    }

    @Test
    fun `unset keys give the default`() {
        Prefs.FROM_XML.forEach { assertEquals(it.key, it.default, it.get(p)) }
        assertEquals(false, Prefs.developerMode.get(p))
    }

    /**
     * SkinPreference and ColorPreference (ui/tv/settings/DesignPreferences.kt) do not store their default;
     * ThemeSettings reads an unset key as "follow the skin", which is the same.
     */
    private val notStoredByXml = setOf("pref_skin") +
        listOf("accent", "background", "surface", "text", "text_muted", "live", "focus").map { "pref_theme_$it" }

    @Test
    fun `what setDefaultValues writes reads back as the descriptor default`() {
        PreferenceManager.setDefaultValues(ctx, R.xml.settings, true)
        val wrong = Prefs.FROM_XML.mapNotNull { d ->
            when {
                !p.contains(d.key) && d.key !in notStoredByXml -> "${d.key}: not written"
                d.get(p) != d.default -> "${d.key}: ${p.all[d.key]} != ${d.default}"
                else -> null
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `values are stored in the type the settings screen uses`() {
        val e = p.edit()
        Prefs.joinChat.put(e, false)
        Prefs.chatLayout.put(e, "overlay")
        Prefs.chatFontSize.put(e, 5)
        Prefs.chatReplyChars.put(e, 120)
        e.commit()
        assertEquals(false, p.getBoolean("pref_join_chat", true))
        assertEquals("overlay", p.getString("pref_chat_layout", null))
        assertEquals("5", p.getString("prefs_chat_font_size", null))
        assertEquals(120, p.getInt("pref_chat_reply_chars", 0))
    }

    @Test
    fun `numbers stored as text fall back to the default when they are no number`() {
        p.edit().putString("prefs_chat_font_size", "x").putString("pref_chat_reply_chars", " 90 ").commit()
        assertEquals(2, Prefs.chatFontSize.get(p))
        assertEquals("x", Prefs.chatFontSize.text(p))
        assertEquals(90, Prefs.chatReplyChars.get(p))
        Prefs.joinChat.set(ctx, false)
        assertEquals(false, Prefs.joinChat.get(ctx))
    }
}
