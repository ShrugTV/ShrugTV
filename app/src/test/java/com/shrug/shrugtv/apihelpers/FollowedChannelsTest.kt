package com.shrug.shrugtv.apihelpers

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Followed channels without own process scope: the short cache, the pagination and that a
 * cancelled caller stops the requests and gets nothing delivered.
 */
class FollowedChannelsTest {

    private fun channel(id: Int, login: String) = JsonObject().apply {
        addProperty("broadcaster_id", id.toString())
        addProperty("broadcaster_login", login)
        addProperty("broadcaster_name", login.uppercase())
        addProperty("followed_at", "2026-11-01T10:00:00Z")
    }

    private fun page(cursor: String?, vararg channels: JsonObject) = JsonObject().apply {
        add("data", JsonArray().apply { channels.forEach { add(it) } })
        add("pagination", JsonObject().apply { if (cursor != null) addProperty("cursor", cursor) })
    }

    private fun users(query: String) = JsonObject().apply {
        add("data", JsonArray().apply {
            for (id in query.removePrefix("?id=").split("&id=")) add(JsonObject().apply {
                addProperty("id", id)
                addProperty("login", "user$id")
                addProperty("display_name", "User $id")
                addProperty("profile_image_url", "https://img.example/$id.png")
                addProperty("description", "about $id")
            })
        })
    }

    // --- cache ---

    @Test
    fun cacheHitsWithinTheValidityAndExpiresAfterIt() {
        var now = 1_000L
        val cache = TimedCache<String>(60_000L) { now }
        cache.put("user|token", "list")
        now += 59_999L
        assertEquals("list", cache.get("user|token"))
        now += 1L
        assertNull("expired after 60 s", cache.get("user|token"))
        now -= 10L
        assertNull("an expired entry is dropped, not revived", cache.get("user|token"))
    }

    @Test
    fun cacheMissesForAnotherAccountOrAfterClear() {
        val cache = TimedCache<String>(60_000L) { 0L }
        cache.put("1|tokenA", "list of A")
        assertNull("signed out or switched account: different key", cache.get("1|"))
        assertNull("the other key dropped the entry", cache.get("1|tokenA"))
        cache.put("1|tokenA", "list of A")
        cache.clear()
        assertNull(cache.get("1|tokenA"))
    }

    @Test
    fun followedChannelsCacheIsValidForOneMinute() {
        assertEquals(60_000L, FollowedChannels.CACHE_MS)
    }

    // --- pagination ---

    @Test
    fun loadsAllPagesAndEnrichesThem() = runBlocking {
        val asked = ArrayList<String>()
        val pages = mapOf(
            "" to page("c1", channel(1, "b"), channel(2, "a")),
            "c1" to page("c2", channel(3, "d")),
            "c2" to page(null, channel(4, "c")),
        )
        val list = FollowedChannels.loadPages(10, page = { asked.add(it); pages[it] }, users = ::users)!!
        assertEquals(listOf("", "c1", "c2"), asked)
        assertEquals(listOf(1, 2, 3, 4), list.map { it.userId })
        assertEquals("https://img.example/3.png", list[2].avatarUrl)
        assertEquals("about 4", list[3].description)
    }

    @Test
    fun stopsAfterMaxPagesPlusOneAsBefore() = runBlocking {
        var count = 0
        val list = FollowedChannels.loadPages(2, page = { count++; page("next$count", channel(count, "x$count")) }, users = ::users)!!
        assertEquals(3, count)
        assertEquals(3, list.size)
    }

    @Test
    fun aBrokenPageFailsTheWholeLoad() = runBlocking {
        val broken = JsonObject().apply { addProperty("error", "Unauthorized") }
        assertNull(FollowedChannels.loadPages(10, page = { if (it == "") page("c1", channel(1, "a")) else broken }, users = ::users))
        assertNull(FollowedChannels.loadPages(10, page = { null }, users = ::users))
    }

    @Test
    fun favoritesComeFirstThenByLogin() {
        val list = listOf("c", "a", "b").mapIndexed { i, l ->
            com.shrug.shrugtv.model.Movie().apply { userId = i; login = l; isFavorite = l == "c" }
        }
        assertEquals(listOf("c", "a", "b"), FollowedChannels.sortFavoritesFirst(list).map { it.login })
    }

    // --- cancellation ---

    @Test
    fun cancellingTheCallerStopsThePaginationAndDeliversNothing() = runBlocking {
        val asked = Collections.synchronizedList(ArrayList<String>())
        val usersStarted = CountDownLatch(1)
        val releaseUsers = CountDownLatch(1)
        var delivered = false
        val job = launch(Dispatchers.IO) {
            FollowedChannels.loadPages(
                10,
                page = { asked.add(it); page("c${asked.size}", channel(asked.size, "x")) },
                users = { q -> usersStarted.countDown(); releaseUsers.await(5, TimeUnit.SECONDS); users(q) },
            )
            delivered = true
        }
        assertTrue(usersStarted.await(5, TimeUnit.SECONDS))
        job.cancel()
        releaseUsers.countDown()
        withTimeout(5_000L) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals("no second page after the caller left", listOf(""), asked.toList())
        assertFalse("nothing reaches the caller after cancel", delivered)
    }

    @Test
    fun aFavoriteWriteFinishesWhenThePageClosesMeanwhile() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var written = false
        val afterWrite = CompletableDeferred<Boolean>()
        val job = launch(Dispatchers.IO) {
            FavoriteChannels.write { started.countDown(); release.await(5, TimeUnit.SECONDS); written = true }
            afterWrite.complete(true)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        withTimeout(5_000L) { job.join() }
        assertTrue("the write ran to the end", written)
        assertTrue("the caller continues after the write (event, state)", afterWrite.isCompleted)
    }
}
