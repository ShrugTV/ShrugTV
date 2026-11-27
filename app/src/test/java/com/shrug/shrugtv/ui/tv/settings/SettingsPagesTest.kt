package com.shrug.shrugtv.ui.tv.settings

import com.shrug.shrugtv.ui.tv.home.HomeRowOrder
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The cleanup of : every page has a short guide, the page "Rows" holds switch and
 * "Move up" of every home screen row in one group, and its groups follow the home screen order.
 */
class SettingsPagesTest {

    private val doc by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/xml/settings.xml"))
    }

    private fun Element.attr(name: String) = getAttributeNS("http://schemas.android.com/apk/res/android", name)
    private fun Element.kids() = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    /** The entries of a page without its sub-pages, groups looked into. */
    private fun leaves(page: Element): List<Element> = page.kids().flatMap { e ->
        when (e.tagName) {
            "PreferenceScreen" -> emptyList()
            "PreferenceCategory" -> leaves(e)
            else -> listOf(e)
        }
    }

    private fun pages(): List<Element> {
        val list = doc.getElementsByTagName("PreferenceScreen")
        return (0 until list.length).map { list.item(it) as Element }.filter { it.attr("key") != "pref_main" }
    }

    private fun strings(folder: String): Map<String, String> =
        File("src/main/res/$folder").listFiles { f -> f.name.endsWith(".xml") }!!.flatMap { f ->
            Regex("""<string name="([^"]+)"[^>]*?(?:/>|>(.*?)</string>)""", RegexOption.DOT_MATCHES_ALL).findAll(f.readText())
                .map { it.groupValues[1] to it.groupValues[2].replace("\\", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&") }
                .toList()
        }.toMap()

    private fun isTip(e: Element) = e.attr("title") == "@string/pref_tip"

    @Test
    fun `every page with settings has a guide`() {
        val without = pages().filter { p -> leaves(p).any { it.attr("key").isNotEmpty() } && leaves(p).none(::isTip) }
            .map { it.attr("key") }
        assertTrue("pages without a pref_tip: $without", without.isEmpty())
    }

    @Test
    fun `guides stay short in English and German`() {
        val names = pages().flatMap { leaves(it) }.filter(::isTip).map { it.attr("summary").removePrefix("@string/") }.distinct()
        for (folder in listOf("values", "values-de")) {
            val s = strings(folder)
            val long = names.mapNotNull { n -> s.getValue(n).length.takeIf { it > 350 }?.let { "$n ($it)" } }
            assertTrue("$folder: guides over 350 characters: $long", long.isEmpty())
        }
    }

    @Test
    fun `the page Rows has switch and move of every home row in one group`() {
        val rows = pages().single { it.attr("key") == "pref_mainscreen_row_visibility" }
        val groups = rows.kids().filter { it.tagName == "PreferenceCategory" }
            .map { g -> g.kids().map { it.attr("key") }.filter { it.isNotEmpty() } }
        val moves = groups.mapNotNull { g -> g.firstOrNull { it.startsWith(HomeRowOrder.MOVE_PREFIX) }?.removePrefix(HomeRowOrder.MOVE_PREFIX) }
        assertEquals(HomeRowOrder.DEFAULT, moves)
        groups.filter { g -> g.any { it.startsWith(HomeRowOrder.MOVE_PREFIX) } }.forEach { g ->
            assertEquals("one switch and one move per group: $g", 2, g.size)
        }
        assertTrue(groups.flatten().contains(HomeRowOrder.RESET))
    }

    private fun toggle(key: String) = Entry.Toggle(key, key, null, true, null, null, null)
    private fun move(id: String) = Entry.Action(HomeRowOrder.MOVE_PREFIX + id, id, null, true, null)
    private fun row(id: String) = Entry.Category(id, listOf(toggle("show_$id"), move(id)))

    @Test
    fun `row groups follow the saved order, other entries keep their place`() {
        val head = Entry.Category("order", listOf(Entry.Action(HomeRowOrder.RESET, "reset", null, true, null)))
        val rowsPage = Entry.Page("pref_mainscreen_row_visibility", "Rows", null, 0,
            listOf(head) + HomeRowOrder.DEFAULT.map(::row))
        val home = Entry.Page("pref_general_settings", "Home", null, 0, listOf(Entry.Category("content", listOf(rowsPage, toggle("x")))))
        val saved = "top_streams,clips"
        val sorted = SettingsModel.inRowOrder(listOf(home), saved).single()
        val content = sorted.children.single() as Entry.Category
        val page = content.children[0] as Entry.Page
        assertEquals("x", content.children[1].key)
        assertEquals("order", page.children[0].title)
        assertEquals(HomeRowOrder.order(HomeRowOrder.DEFAULT, saved), page.children.drop(1).map { it.title })
        // Nothing saved: the built-in order
        val plain = SettingsModel.inRowOrder(listOf(home), null).single()
        val plainPage = (plain.children.single() as Entry.Category).children[0] as Entry.Page
        assertEquals(HomeRowOrder.DEFAULT, plainPage.children.drop(1).map { it.title })
    }
}
