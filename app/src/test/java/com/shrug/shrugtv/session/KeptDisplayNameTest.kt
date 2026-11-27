package com.shrug.shrugtv.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** The display name kept for the start greeting. */
class KeptDisplayNameTest {
    @Test
    fun `the kept name shows when it belongs to the login`() {
        assertEquals("ReRedman", SessionStore.keptDisplayName("reredman", "ReRedman", "reredman"))
        assertEquals("ReRedman", SessionStore.keptDisplayName("reredman", "ReRedman", "ReRedman"))
    }

    @Test
    fun `another account's name or a missing one never shows`() {
        assertEquals("", SessionStore.keptDisplayName("other", "ReRedman", "reredman"))
        assertEquals("", SessionStore.keptDisplayName("", "ReRedman", "reredman"))
        assertEquals("", SessionStore.keptDisplayName("reredman", null, null))
        assertEquals("", SessionStore.keptDisplayName("reredman", "ReRedman", null))
    }
}
