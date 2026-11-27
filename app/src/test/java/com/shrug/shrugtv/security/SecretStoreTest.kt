package com.shrug.shrugtv.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shrug.shrugtv.ha.MqttSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted secrets with a JVM key in place of the Android Keystore. */
@RunWith(RobolectricTestRunner::class)
class SecretStoreTest {

    /** AES-GCM like [KeystoreCipher], with a key in memory; [lose] stands for a keystore that lost its key. */
    private class JvmCipher : SecretCipher {
        private var key: SecretKey? = newKey()
        var failEncrypt = false

        fun lose() { key = null }
        fun replace() { key = newKey() }

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            if (failEncrypt) throw java.security.KeyStoreException("keystore unavailable")
            val k = key ?: newKey().also { key = it }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return iv + c.doFinal(plain)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            val k = key ?: throw IllegalStateException("key missing")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 0, 12))
            c.updateAAD(aad)
            return c.doFinal(blob, 12, blob.size - 12)
        }

        private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val cipher = JvmCipher()
    private val user get() = ctx.getSharedPreferences(SecretStore.USER_FILE, 0)
    private val mqtt get() = ctx.getSharedPreferences(SecretStore.MQTT_FILE, 0)
    private val encToken = SecretStore.encryptedKey(SecretStore.TOKEN)

    @Before
    fun setUp() {
        user.edit().clear().commit()
        mqtt.edit().clear().commit()
        SecretStore.setCipherForTest(cipher)
    }

    @After
    fun tearDown() = SecretStore.setCipherForTest(null)

    /** As after a restart of the app: nothing decrypted in memory. */
    private fun restart() = SecretStore.forgetCache()

    @Test
    fun `plaintext from before is encrypted on the first read and stays signed in`() {
        user.edit().putString("TOKEN", "access-123").putString("REFRESH_TOKEN", "refresh-456")
            .putString("USER_ID", "42").commit()

        assertEquals("access-123", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
        assertEquals("refresh-456", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.REFRESH_TOKEN))

        assertNull(user.getString("TOKEN", null))
        assertNull(user.getString("REFRESH_TOKEN", null))
        val stored = user.getString(encToken, null)!!
        assertTrue(stored.startsWith("v1:"))
        assertFalse(stored.contains("access-123"))
        assertEquals("42", user.getString("USER_ID", null)) // not a secret, untouched

        restart()
        assertEquals("access-123", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
        assertEquals("refresh-456", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.REFRESH_TOKEN))
    }

    @Test
    fun `a written value reads back after a restart and never lies in plaintext`() {
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "fresh")
        assertNull(user.getString("TOKEN", null))
        assertFalse(user.all.values.any { it.toString().contains("fresh") })
        restart()
        assertEquals("fresh", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
    }

    @Test
    fun `each encryption uses a new IV`() {
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "same")
        val first = user.getString(encToken, null)
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "same")
        assertNotEquals(first, user.getString(encToken, null))
    }

    @Test
    fun `a lost key reads as not set without a crash`() {
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "secret")
        cipher.lose()
        restart()
        assertEquals("", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
    }

    @Test
    fun `a new key after a restore reads as not set, and a new sign-in works again`() {
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "secret")
        cipher.replace()
        restart()
        assertEquals("", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "again")
        restart()
        assertEquals("again", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
    }

    @Test
    fun `broken ciphertext reads as not set without a crash`() {
        for (garbage in listOf("v1:not base64 !!", "v1:AAAA", "v2:whatever", "v1:" + "A".repeat(64))) {
            user.edit().putString(encToken, garbage).commit()
            restart()
            assertEquals(garbage, "", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
        }
    }

    @Test
    fun `a value moved to another key does not decrypt`() {
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "access")
        val sealed = user.getString(encToken, null)
        user.edit().putString(SecretStore.encryptedKey(SecretStore.REFRESH_TOKEN), sealed).commit()
        restart()
        assertEquals("", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.REFRESH_TOKEN))
    }

    @Test
    fun `removing and writing empty delete plain and encrypted values`() {
        user.edit().putString("REFRESH_TOKEN", "old-plain").commit()
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "access")
        SecretStore.remove(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, SecretStore.REFRESH_TOKEN)
        assertTrue(user.all.isEmpty())
        assertEquals("", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))

        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "access")
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "")
        assertTrue(user.all.isEmpty())
        restart()
        assertEquals("", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
    }

    @Test
    fun `a failing keystore keeps the value as before instead of losing it`() {
        cipher.failEncrypt = true
        user.edit().putString("TOKEN", "keep-me").commit()
        assertEquals("keep-me", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
        assertEquals("keep-me", user.getString("TOKEN", null))

        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.REFRESH_TOKEN, "new")
        restart()
        assertEquals("new", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.REFRESH_TOKEN))

        // Once the keystore works again, the next read encrypts it
        cipher.failEncrypt = false
        restart()
        assertEquals("keep-me", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
        assertNull(user.getString("TOKEN", null))
    }

    @Test
    fun `the MQTT password moves from the settings into the encrypted store`() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit().putString("pref_mqtt_password", "broker-pw").commit()
        assertEquals("broker-pw", MqttSettings.load(ctx).password)
        assertEquals(MqttSettings.MASK, prefs.getString("pref_mqtt_password", null))
        assertNull(mqtt.getString("password", null))
        assertFalse(mqtt.all.values.any { it.toString().contains("broker-pw") })

        restart()
        assertEquals("broker-pw", MqttSettings.load(ctx).password)

        // An old plaintext password is migrated, emptying the field removes it
        mqtt.edit().clear().putString("password", "old-pw").commit()
        restart()
        assertEquals("old-pw", MqttSettings.load(ctx).password)
        assertNull(mqtt.getString("password", null))
        prefs.edit().putString("pref_mqtt_password", "").commit()
        assertEquals("", MqttSettings.load(ctx).password)
        assertFalse(SecretStore.contains(ctx, SecretStore.MQTT_FILE, SecretStore.MQTT_PASSWORD))
    }

    @Test
    fun `the session loads a plaintext token from before the update`() {
        user.edit().putString("TOKEN", "access-old").putString("USER_ID", "7").putString("USER_NAME", "me").commit()
        com.shrug.shrugtv.session.SessionStore.loadFromPrefs(ctx)
        val s = com.shrug.shrugtv.session.SessionStore.state.value
        assertEquals("access-old", s.accessToken)
        assertTrue(s.isLoggedIn)
        assertNull(user.getString("TOKEN", null))
        assertTrue(SecretStore.contains(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
    }

    @Test
    fun `signing out deletes both tokens and returns them for the revocation`() {
        user.edit().putString("REFRESH_TOKEN", "refresh-plain").putLong("TOKEN_EXPIRES_AT", 99L).commit()
        SecretStore.put(ctx, SecretStore.USER_FILE, SecretStore.TOKEN, "access")
        val (access, refresh) = com.shrug.shrugtv.login.TwitchDeviceAuth.forgetTokens(ctx)
        assertEquals("access", access)
        assertEquals("refresh-plain", refresh)
        assertTrue(user.all.toString(), user.all.isEmpty())
        restart()
        assertEquals("", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.TOKEN))
        assertEquals("", SecretStore.get(ctx, SecretStore.USER_FILE, SecretStore.REFRESH_TOKEN))
    }

    @Test
    fun `a lost key leaves MQTT without a password instead of crashing`() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit().putString("pref_mqtt_password", "broker-pw").commit()
        MqttSettings.load(ctx)
        cipher.lose()
        restart()
        assertEquals("", MqttSettings.load(ctx).password)
    }
}
