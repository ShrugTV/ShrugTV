package com.shrug.shrugtv.net

import okhttp3.Call

/**
 * The OkHttp calls of one blocking job (e.g. PlaybackUrlResolver), so another thread can abort
 * them all at once. After [cancel], every running call is cancelled and
 * every later [Http.Result.get] with this group fails at once.
 */
class CallGroup {
    private val calls = HashSet<Call>()

    @Volatile
    var isCancelled = false
        private set

    /** Registers [call]; false (and the call cancelled) when the group is cancelled already. */
    @Synchronized
    internal fun add(call: Call): Boolean {
        if (isCancelled) {
            call.cancel()
            return false
        }
        calls.add(call)
        return true
    }

    @Synchronized
    internal fun remove(call: Call) {
        calls.remove(call)
    }

    @Synchronized
    fun cancel() {
        isCancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }
}
