package com.shrug.shrugtv.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The main database across Room versions and its migrations. Room checks identity hash and
 * schema itself when it opens a file, so a wrong migration or a changed entity fails here, not on the TV.
 */
@RunWith(RobolectricTestRunner::class)
class RoomMigrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun open(name: String): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `the exported schema keeps the identity hash of the databases on the devices`() {
        // Unit tests run in the module folder; Room writes the schemas to app/schemas
        val json = File("schemas/com.shrug.shrugtv.database.AppDatabase/3.json").readText()
        val hash = JsonParser.parseString(json).asJsonObject.getAsJsonObject("database").get("identityHash").asString
        assertEquals("dd460f1e22be79f1ead2e1fc14bef293", hash)
    }

    @Test
    fun `a real version 3 database opens with Room and keeps its rows`() {
        val file = ctx.getDatabasePath("real_v3")
        file.parentFile!!.mkdirs()
        javaClass.getResourceAsStream("/database/database_v3.db")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val db = open("real_v3")
        try {
            val vod = db.vodInfoDao().findByBroadcastId(2904525110L)
            assertNotNull(vod)
            assertEquals(176, vod.position)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 1 database migrates to 3 and keeps its rows`() {
        val file: File = ctx.getDatabasePath("old_v1")
        file.parentFile!!.mkdirs()
        file.delete()
        // Version 1: vod_info before the columns of the two migrations
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE vod_info (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `broadcast_id` INTEGER NOT NULL, `position` INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE favorite_info (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channel_id` TEXT)")
            old.execSQL("INSERT INTO vod_info (broadcast_id, position) VALUES (123456789, 42)")
            old.execSQL("INSERT INTO favorite_info (channel_id) VALUES ('43683025')")
            old.version = 1
        }
        val db = open("old_v1")
        try {
            val vod = db.vodInfoDao().findByBroadcastId(123456789L)
            assertNotNull("the row survives both migrations", vod)
            assertEquals(42, vod.position)
            assertNull("new columns start empty", vod.lastAccess)
        } finally {
            db.close()
        }
    }
}
