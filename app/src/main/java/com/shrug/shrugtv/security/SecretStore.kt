package com.shrug.shrugtv.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.Base64

/**
 * The one way to read and write the app's secrets: the Twitch access and refresh token in
 * `User.xml` and the MQTT password in `Mqtt.xml`. Values are stored encrypted with [SecretCipher] under
 * `<key>_ENC` as `v1:<base64 of IV + ciphertext>`, bound to file and key as additional data.
 *
 * - **Migration:** a plaintext value under the old key is encrypted on the first read and the plaintext removed,
 *   so an update keeps the sign-in.
 * - **Lost or broken key** (backup restore, keystore failure): the value reads as empty, nothing crashes, a log line
 *   names file and key but never the value. The next sign-in overwrites it.
 * - **Encryption fails** (keystore unusable on a device): the value is kept as plaintext as before, so the app keeps
 *   working; the next read tries the migration again.
 */
object SecretStore {
    private const val TAG = "SecretStore"
    private const val SUFFIX = "_ENC"
    private const val PREFIX = "v1:"

    const val USER_FILE = "User"
    const val TOKEN = "TOKEN"
    const val REFRESH_TOKEN = "REFRESH_TOKEN"
    const val MQTT_FILE = "Mqtt"
    const val MQTT_PASSWORD = "password"

    @Volatile
    private var cipherOverride: SecretCipher? = null
    private val defaultCipher by lazy { KeystoreCipher() }
    private val cipher: SecretCipher get() = cipherOverride ?: defaultCipher

    /** Decrypted values of this process, so the keystore is asked once per value and not on every read. */
    private val cache = HashMap<String, String>()

    /** Tests: replace the keystore and forget the cache (as after a restart of the app). */
    @VisibleForTesting
    @JvmStatic
    @Synchronized
    fun setCipherForTest(c: SecretCipher?) {
        cipherOverride = c
        cache.clear()
    }

    /** Tests: forget what was decrypted, as after a restart of the app. */
    @VisibleForTesting
    @JvmStatic
    @Synchronized
    fun forgetCache() = cache.clear()

    /** Name under which the encrypted value is stored. */
    @JvmStatic
    fun encryptedKey(key: String) = key + SUFFIX

    /** The secret [key] of [file], or "" when there is none or it cannot be decrypted. */
    @JvmStatic
    @Synchronized
    fun get(context: Context, file: String, key: String): String {
        val id = "$file/$key"
        cache[id]?.let { return it }
        val prefs = prefs(context, file)
        val plain = prefs.getString(key, null)
        if (!plain.isNullOrEmpty()) {
            // Plaintext from before the encryption, or from a device whose keystore failed: move it over
            val sealed = seal(file, key, plain)
            if (sealed != null) {
                prefs.edit().putString(encryptedKey(key), sealed).remove(key).apply()
                Log.i(TAG, "Encrypted $id")
            }
            cache[id] = plain
            return plain
        }
        val stored = prefs.getString(encryptedKey(key), null)
        if (stored.isNullOrEmpty()) return ""
        val value = open(file, key, stored) ?: return ""
        cache[id] = value
        return value
    }

    /** Stores [value] for [key] of [file] at once; empty or null removes it. */
    @JvmStatic
    @Synchronized
    fun put(context: Context, file: String, key: String, value: String?) {
        val edit = prefs(context, file).edit()
        putInto(edit, file, key, value)
        edit.apply()
    }

    /**
     * Writes [value] for [key] into an open [edit] of [file], so it lands together with other values in one
     * `apply()`; empty or null removes it.
     */
    @JvmStatic
    @Synchronized
    fun putInto(edit: SharedPreferences.Editor, file: String, key: String, value: String?) {
        val id = "$file/$key"
        if (value.isNullOrEmpty()) {
            edit.remove(key).remove(encryptedKey(key))
            cache.remove(id)
            return
        }
        val sealed = seal(file, key, value)
        if (sealed != null) {
            edit.putString(encryptedKey(key), sealed).remove(key)
        } else {
            edit.putString(key, value).remove(encryptedKey(key))
        }
        cache[id] = value
    }

    /** Removes [keys] of [file], plain and encrypted. */
    @JvmStatic
    @Synchronized
    fun remove(context: Context, file: String, vararg keys: String) {
        val edit = prefs(context, file).edit()
        for (k in keys) putInto(edit, file, k, null)
        edit.apply()
    }

    /** Whether [key] of [file] has a stored value (plain or encrypted), without decrypting it. */
    @JvmStatic
    fun contains(context: Context, file: String, key: String): Boolean {
        val prefs = prefs(context, file)
        return !prefs.getString(key, null).isNullOrEmpty() || !prefs.getString(encryptedKey(key), null).isNullOrEmpty()
    }

    private fun prefs(context: Context, file: String) =
        context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    private fun aad(file: String, key: String) = "$file/$key".toByteArray(Charsets.UTF_8)

    @Suppress("TooGenericExceptionCaught") // any keystore failure means "keep it as before", never a crash
    private fun seal(file: String, key: String, value: String): String? = try {
        PREFIX + Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8), aad(file, key)))
    } catch (e: Exception) {
        Log.w(TAG, "Cannot encrypt $file/$key, keeping it as before: ${e.javaClass.simpleName}")
        null
    }

    @Suppress("TooGenericExceptionCaught") // a broken value or lost key means "not set", never a crash
    private fun open(file: String, key: String, stored: String): String? = try {
        require(stored.startsWith(PREFIX)) { "Unknown format" }
        String(cipher.decrypt(Base64.getDecoder().decode(stored.substring(PREFIX.length)), aad(file, key)), Charsets.UTF_8)
    } catch (e: Exception) {
        // Never the value or the exception message, only what failed
        Log.w(TAG, "Cannot decrypt $file/$key, treating it as not set: ${e.javaClass.simpleName}")
        null
    }
}
