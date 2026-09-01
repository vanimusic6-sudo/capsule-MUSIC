/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(val time: Long, val level: Int, val tag: String?, val message: String)

object GlobalLog {
    /*
     * Sized for a field run: a couple of hundred track resolves produce far
     * more than 500 lines once every subsystem is logging, and at 500 the
     * beginning of a session is gone before it can be shared.
     */
    private const val MAX_ENTRIES = 5000

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val lock = Any()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun append(level: Int, tag: String?, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        /*
         * Timber is called from playback, network and UI threads at once, so
         * the read-modify-write below has to be guarded or entries get lost.
         */
        synchronized(lock) {
            _logs.value = (_logs.value + entry).takeLast(MAX_ENTRIES)
        }
    }

    fun clear() {
        synchronized(lock) {
            _logs.value = emptyList()
        }
    }

    fun format(entry: LogEntry): String {
        val ts = timeFormat.format(Date(entry.time))
        val lvl = when (entry.level) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            else -> "?"
        }
        val tag = entry.tag ?: ""
        return "[$ts] $lvl/$tag: ${entry.message}"
    }
}

/** Timber Tree that forwards logs to GlobalLog */
class GlobalLogTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        try {
            val final = if (t != null) "$message\n$t" else message
            GlobalLog.append(priority, tag, final)
        } catch (_: Exception) {
            // swallow
        }
    }
}
