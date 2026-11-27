package com.shrug.shrugtv.apihelpers

/**
 * One value that is valid for [ttlMs] and only for the key it was stored under. A different
 * key (another account, signed out) or an expired entry is a miss and drops the entry. [clock] is injectable for tests.
 */
internal class TimedCache<T>(private val ttlMs: Long, private val clock: () -> Long) {

    private class Entry<T>(val key: String, val at: Long, val value: T)

    private var entry: Entry<T>? = null

    @Synchronized
    fun get(key: String): T? {
        val e = entry ?: return null
        if (e.key != key || clock() - e.at >= ttlMs) {
            entry = null
            return null
        }
        return e.value
    }

    @Synchronized
    fun put(key: String, value: T) {
        entry = Entry(key, clock(), value)
    }

    @Synchronized
    fun clear() {
        entry = null
    }
}
