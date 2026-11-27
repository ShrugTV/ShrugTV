package com.shrug.shrugtv.prefs

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The old bot prediction switch is folded into "Hide bots" once. */
@RunWith(RobolectricTestRunner::class)
class PrefsMigrationTest {
    private lateinit var p: SharedPreferences
    private val old = PrefsMigration.LEGACY_FILTER_PREDICT_BOTS

    @Before
    fun clear() {
        p = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
        p.edit().clear().commit()
    }

    @Test
    fun `old switch on turns Hide bots on and goes away`() {
        p.edit().putBoolean(old, true).commit()
        PrefsMigration.run(p)
        assertEquals(true, Prefs.filterBots.get(p))
        assertFalse(p.contains(old))
    }

    @Test
    fun `old switch off leaves Hide bots as it was`() {
        p.edit().putBoolean(old, false).putBoolean(Prefs.filterBots.key, true).commit()
        PrefsMigration.run(p)
        assertEquals(true, Prefs.filterBots.get(p))
        assertFalse(p.contains(old))
        p.edit().clear().putBoolean(old, false).commit()
        PrefsMigration.run(p)
        assertFalse(p.contains(Prefs.filterBots.key))
    }

    @Test
    fun `runs once, so switching Hide bots off afterwards sticks`() {
        p.edit().putBoolean(old, true).commit()
        PrefsMigration.run(p)
        Prefs.filterBots.put(p.edit(), false).commit()
        PrefsMigration.run(p)
        assertEquals(false, Prefs.filterBots.get(p))
    }
}
