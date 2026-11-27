package com.shrug.shrugtv.ui.tv.player

import androidx.media3.common.Player
import com.shrug.shrugtv.database.VodInfo
import com.shrug.shrugtv.database.VodInfoDao
import com.shrug.shrugtv.model.Movie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** The resume position of recordings as a class of its own. */
class VodProgressStoreTest {

    private class FakeDao : VodInfoDao {
        val rows = HashMap<Long, VodInfo>()
        var inserts = 0
        var updates = 0
        var fail = false
        override fun findByBroadcastId(broadcastId: Long): VodInfo? {
            if (fail) throw IllegalStateException("database closed")
            return rows[broadcastId]
        }
        override fun clearHistory() = Unit
        override fun insert(vodInfo: VodInfo) { inserts++; rows[vodInfo.broadcastId] = vodInfo }
        override fun update(vodInfo: VodInfo) { updates++; rows[vodInfo.broadcastId] = vodInfo }
        override fun getHistory(): List<VodInfo> = emptyList()
        override fun delete(vodInfo: VodInfo) { rows.remove(vodInfo.broadcastId) }
        override fun getAll(): List<VodInfo> = rows.values.toList()
    }

    private var position = 754_300L
    private var duration = 3_600_900L
    private val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, _ ->
        when (m.name) {
            "getCurrentPosition" -> position
            "getDuration" -> duration
            else -> when (m.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as Player

    private val dao = FakeDao()
    private val errors = ArrayList<Exception>()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val store = VodProgressStore(
        dao = { dao },
        player = { player },
        saveScope = scope,
        io = Dispatchers.Unconfined,
        now = { "2026-11-25T20:00:00Z" },
        onError = { errors.add(it) }
    )

    private val vod = Movie().apply {
        setVideoIdFromString("100")
        userId = 7
        login = "chan"
        displayName = "Chan"
        title = "Long stream"
        gameName = "Chess"
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a new recording gets a row with position, length and channel`() {
        store.save(vod)
        val row = dao.rows[100L]!!
        assertEquals(754, row.position)
        assertEquals(3600, row.vodLength)
        assertEquals("2026-11-25T20:00:00Z", row.lastAccess)
        assertEquals("Chan", row.channelName)
        assertEquals("chan", row.channelNameEnglish)
        assertEquals("Long stream", row.title)
        assertEquals("Chess", row.categoryName)
        assertEquals(7, row.channelId)
        assertEquals(1, dao.inserts)
    }

    @Test
    fun `a known recording is updated, a length of 0 is kept`() {
        store.save(vod)
        val row = dao.rows[100L]!!
        position = 900_000L
        duration = 0L
        store.save(vod)
        assertSame(row, dao.rows[100L])
        assertEquals(900, row.position)
        assertEquals(3600, row.vodLength)
        assertEquals(1, dao.updates)
    }

    @Test
    fun `live streams, the recording of a live stream and the first second are not saved`() {
        store.save(Movie().apply { userId = 7; login = "chan" })
        store.save(Movie().apply { setVideoIdFromString("101"); archiveOfLive = true })
        position = 999L
        store.save(vod)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `the saved position comes back in ms, 0 without one`() = runBlocking {
        store.save(vod)
        assertEquals(754_000L, store.positionMs(vod))
        assertEquals(0L, store.positionMs(Movie().apply { setVideoIdFromString("555") }))
        assertEquals(0L, store.positionMs(Movie()))
    }

    @Test
    fun `an unknown duration saves no length and keeps a known one (michael-shrugtv-354)`() = runBlocking {
        duration = androidx.media3.common.C.TIME_UNSET
        store.save(vod)
        assertEquals("new row: no nonsense length", 0, dao.rows[100L]!!.vodLength)
        duration = 3_600_900L
        store.save(vod)
        assertEquals(3600, dao.rows[100L]!!.vodLength)
        duration = androidx.media3.common.C.TIME_UNSET
        store.save(vod)
        assertEquals("the known length stays", 3600, dao.rows[100L]!!.vodLength)
    }

    @Test
    fun `a failing database is recorded and reads as 0`() = runBlocking {
        dao.fail = true
        store.save(vod)
        assertEquals(0L, store.positionMs(vod))
        assertEquals(2, errors.size)
        assertNull(dao.rows[100L])
    }
}
