package com.shrug.shrugtv.prefs

import com.shrug.shrugtv.stats.StatsPrefs
import com.shrug.shrugtv.ui.tv.theme.ThemeSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Code and settings screen share one default per key: every
 * `android:defaultValue` in settings.xml must equal the descriptor in [Prefs], with the matching type.
 */
class PrefsDefaultsTest {

    private data class XmlPref(val tag: String, val key: String, val default: String)

    private val strings: Map<String, String> by lazy {
        File("src/main/res/values").listFiles { f -> f.name.endsWith(".xml") }!!.flatMap { f ->
            Regex("""<string name="([^"]+)"[^>]*?(?:/>|>(.*?)</string>)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toMap()
    }

    private val xml: List<XmlPref> by lazy {
        val text = File("src/main/res/xml/settings.xml").readText()
        Regex("""<([\w.]+)\s([^>]*?)/?>""").findAll(text).mapNotNull { m ->
            val attrs = m.groupValues[2]
            val key = Regex("""android:key="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val raw = Regex("""android:defaultValue="([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val value = if (raw.startsWith("@string/")) strings.getValue(raw.removePrefix("@string/")) else raw
            XmlPref(m.groupValues[1].substringAfterLast('.'), key, value)
        }.toList()
    }

    @Test
    fun `every default in settings xml has a descriptor with the same value`() {
        val wrong = xml.mapNotNull { x ->
            val p = Prefs.byKey(x.key) ?: return@mapNotNull "${x.key}: no descriptor in Prefs"
            if (p.xmlDefault != x.default) "${x.key}: settings.xml '${x.default}', Prefs '${p.xmlDefault}'" else null
        }
        assertTrue("defaults differ:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `every descriptor type fits the preference that stores it`() {
        val wrong = xml.mapNotNull { x ->
            val p = Prefs.byKey(x.key) ?: return@mapNotNull null
            val fits = when (x.tag) {
                "SwitchPreference" -> p is BoolPref
                "SeekBarPreference" -> p is IntPref
                "ListPreference" -> p is StringPref || p is IntListPref
                else -> p is StringPref
            }
            if (fits) null else "${x.key}: ${x.tag} but ${p::class.simpleName}"
        }
        assertTrue("types do not fit:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `descriptors mirror settings xml and nothing else`() {
        val xmlKeys = xml.map { it.key }.toSet()
        val stale = Prefs.FROM_XML.map { it.key }.filter { it !in xmlKeys }
        assertTrue("in Prefs.FROM_XML but without a default in settings.xml: $stale", stale.isEmpty())
        val inXml = Prefs.CODE_ONLY.map { it.key }.filter { it in xmlKeys }
        assertTrue("Prefs.CODE_ONLY keys that settings.xml has: $inXml", inXml.isEmpty())
        val keys = Prefs.ALL.map { it.key }
        assertEquals("duplicate keys", keys.size, keys.toSet().size)
        assertEquals("settings.xml keys with a default", xml.size, Prefs.FROM_XML.size)
    }

    /** Statistics and design still read through their own functions; their defaults must be the same as the XML ones. */
    @Test
    fun `statistics and design read an unset key like the settings xml default`() {
        val xmlValue: (String) -> String? = { k -> Prefs.byKey(k)?.xmlDefault }
        assertEquals(
            StatsPrefs.parse({ null }, { _, d -> d }),
            StatsPrefs.parse(xmlValue, { k, d -> (Prefs.byKey(k) as? BoolPref)?.default ?: d }),
        )
        assertEquals(ThemeSettings.resolve { _, d -> d }, ThemeSettings.resolve { k, d -> xmlValue(k) ?: d })
    }
}
