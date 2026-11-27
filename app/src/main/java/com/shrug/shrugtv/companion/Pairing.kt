package com.shrug.shrugtv.companion

import java.security.SecureRandom

/**
 * Pairing of phones (docs/companion_plan.md section 3): a one-time token for the QR code and a
 * 6-digit PIN, both valid for [OFFER_MS]; redeemed for a session id kept in memory only. Max.
 * [MAX_SESSIONS] phones; the oldest goes first. Pure and thread-safe, so it is unit-tested.
 */
class Pairing(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom()
) {
    data class Offer(val token: String, val pin: String, val expiresAt: Long)
    data class Phone(val sessionId: String, val userAgent: String, val pairedAt: Long)

    companion object {
        const val OFFER_MS = 120_000L
        const val MAX_SESSIONS = 3
        const val MAX_PIN_TRIES = 5
    }

    private var offer: Offer? = null
    private var pinTries = 0
    /** Wrong tries per address: one device in the LAN cannot use up the offer for everyone. */
    private val triesByIp = HashMap<String, Int>()
    private val phones = LinkedHashMap<String, Phone>()

    @Synchronized
    fun newOffer(): Offer {
        val o = Offer(randomHex(16), (random.nextInt(900_000) + 100_000).toString(), now() + OFFER_MS)
        offer = o
        pinTries = 0
        triesByIp.clear()
        return o
    }

    /** The QR link was opened. */
    @Synchronized
    fun redeemToken(token: String, userAgent: String): Phone? {
        val o = offer ?: return null
        if (now() > o.expiresAt || token != o.token) return null
        return pair(userAgent)
    }

    /** The PIN was typed on the phone; too many wrong tries end the offer. */
    @Synchronized
    fun redeemPin(pin: String, userAgent: String, ip: String? = null): Phone? {
        val o = offer ?: return null
        if (now() > o.expiresAt) return null
        val who = ip ?: ""
        if ((triesByIp[who] ?: 0) >= MAX_PIN_TRIES) return null
        if (pin.trim() != o.pin) {
            triesByIp[who] = (triesByIp[who] ?: 0) + 1
            // Many different addresses together still end the offer
            if (++pinTries >= MAX_PIN_TRIES * 4) offer = null
            return null
        }
        return pair(userAgent)
    }

    private fun pair(userAgent: String): Phone {
        offer = null
        while (phones.size >= MAX_SESSIONS) phones.remove(phones.keys.first())
        val p = Phone(randomHex(24), userAgent.take(120), now())
        phones[p.sessionId] = p
        return p
    }

    @Synchronized
    fun isValid(sessionId: String?): Boolean = sessionId != null && phones.containsKey(sessionId)

    @Synchronized
    fun phones(): List<Phone> = phones.values.toList()

    @Synchronized
    fun disconnectAll() {
        phones.clear()
        offer = null
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
