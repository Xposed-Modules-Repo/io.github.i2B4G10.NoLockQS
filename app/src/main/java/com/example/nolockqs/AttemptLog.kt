package com.example.nolockqs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The history of blocked attempts and the hooks' last status reports. It lives in device-protected
 * storage, so attempts made before the first unlock after a restart are kept as well.
 */
class AttemptLog(context: Context) {

    /** One blocked attempt: when, and which protection refused it ([Reports] KIND_ value). */
    data class Attempt(val time: Long, val kind: String)

    /** A process's last status report: when, and one line per protection layer. */
    data class Status(val time: Long, val layers: List<String>)

    private companion object {
        const val FILE = "attempt_log"
        const val KEY_ENTRIES = "entries"
        const val KEY_UNSEEN = "unseen"
        const val KEY_ALERTS = "alerts_enabled"
        const val KEY_STATUS_LAYERS = "status_layers_"
        const val KEY_STATUS_TIME = "status_time_"

        /** The oldest entries are dropped beyond this many. */
        const val MAX_ENTRIES = 500
    }

    val prefs: SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** All attempts, newest first. */
    fun attempts(): List<Attempt> =
        prefs.getString(KEY_ENTRIES, null).orEmpty().lineSequence().mapNotNull { line ->
            val time = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            Attempt(time, line.substringAfter('|'))
        }.toList().asReversed()

    /** Records an attempt and returns how many came in since the history was last looked at. */
    fun add(attempt: Attempt): Int {
        val lines = prefs.getString(KEY_ENTRIES, null).orEmpty().lines().filter(String::isNotBlank)
        val kept = (lines + "${attempt.time}|${attempt.kind}").takeLast(MAX_ENTRIES)
        val unseen = prefs.getInt(KEY_UNSEEN, 0) + 1
        prefs.edit(commit = true) {
            putString(KEY_ENTRIES, kept.joinToString("\n"))
            putInt(KEY_UNSEEN, unseen)
        }
        return unseen
    }

    /** Empties the history. */
    fun clear() = prefs.edit {
        remove(KEY_ENTRIES)
        remove(KEY_UNSEEN)
    }

    /** The history was looked at: the next alert starts counting again. */
    fun markSeen() = prefs.edit { remove(KEY_UNSEEN) }

    /** Whether a blocked attempt also posts a notification. On unless turned off. */
    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS, true)
        set(value) = prefs.edit { putBoolean(KEY_ALERTS, value) }

    fun saveStatus(process: String, status: Status) = prefs.edit {
        putString(KEY_STATUS_LAYERS + process, status.layers.joinToString("\n"))
        putLong(KEY_STATUS_TIME + process, status.time)
    }

    fun status(process: String): Status? {
        val layers = prefs.getString(KEY_STATUS_LAYERS + process, null) ?: return null
        return Status(prefs.getLong(KEY_STATUS_TIME + process, 0L), layers.lines().filter(String::isNotBlank))
    }
}
