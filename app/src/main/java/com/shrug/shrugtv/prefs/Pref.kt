package com.shrug.shrugtv.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * One setting in the default SharedPreferences: key, stored type and default at one place
 *. The descriptors live in [Prefs]; `PrefsDefaultsTest` checks every default
 * against `android:defaultValue` in `res/xml/settings.xml`, so code and settings screen cannot drift
 * apart again.
 *
 * Keys and stored types are exactly those of the existing installations: a [BoolPref] is stored as a
 * boolean, [StringPref] and [IntListPref] as strings (a ListPreference always stores text), an
 * [IntPref] as an int. Secrets never go through here but through `security/SecretStore`.
 */
sealed class Pref<T : Any>(val key: String, val default: T) {

    /** The stored value, or [default] when the key is not set. */
    abstract fun get(p: SharedPreferences): T

    /** Writes [value] in its stored type; the caller applies or commits the editor. */
    abstract fun put(e: SharedPreferences.Editor, value: T): SharedPreferences.Editor

    /** The default as `android:defaultValue` in settings.xml spells it. */
    open val xmlDefault: String get() = default.toString()

    fun get(context: Context): T = get(Prefs.of(context))

    fun set(context: Context, value: T) = put(Prefs.of(context).edit(), value).apply()

    fun isSet(p: SharedPreferences): Boolean = p.contains(key)

    override fun toString() = "$key=$default"
}

/** A switch, stored as boolean. */
class BoolPref(key: String, default: Boolean) : Pref<Boolean>(key, default) {
    override fun get(p: SharedPreferences): Boolean = p.getBoolean(key, default)
    override fun put(e: SharedPreferences.Editor, value: Boolean): SharedPreferences.Editor = e.putBoolean(key, value)
}

/** A text or a choice, stored as string. */
class StringPref(key: String, default: String) : Pref<String>(key, default) {
    override fun get(p: SharedPreferences): String = p.getString(key, default) ?: default
    override fun put(e: SharedPreferences.Editor, value: String): SharedPreferences.Editor = e.putString(key, value)
}

/**
 * A choice whose values are numbers. A ListPreference stores them as text, so this stays a string in
 * the file; [get] parses it and falls back to [default] for anything that is not a number.
 */
class IntListPref(key: String, default: Int) : Pref<Int>(key, default) {
    override fun get(p: SharedPreferences): Int = p.getString(key, null)?.toIntOrNull() ?: default
    override fun put(e: SharedPreferences.Editor, value: Int): SharedPreferences.Editor = e.putString(key, value.toString())

    /** The stored text as is, [default] as text when unset (for parsers that take the raw value). */
    fun text(p: SharedPreferences): String = p.getString(key, null) ?: default.toString()
}

/**
 * A number stored as int (SeekBarPreference). The browser settings store the same key as text, so
 * [get] reads either and falls back to [default].
 */
class IntPref(key: String, default: Int) : Pref<Int>(key, default) {
    override fun get(p: SharedPreferences): Int = when (val v = p.all[key]) {
        is Int -> v
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: default
        else -> default
    }
    override fun put(e: SharedPreferences.Editor, value: Int): SharedPreferences.Editor = e.putInt(key, value)

    /** The stored value as text whatever its type, null when unset. */
    fun raw(p: SharedPreferences): String? = p.all[key]?.toString()
}

/** Shortcut for the default SharedPreferences, so callers need no PreferenceManager import. */
internal fun defaultPrefs(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
