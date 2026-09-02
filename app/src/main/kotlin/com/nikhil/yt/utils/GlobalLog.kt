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
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class LogEntry(val time: Long, val level: Int, val tag: String?, val message: String)

object GlobalLog {
    /*
     * Large enough that a field run can be shared in one piece.
     */
    private const val MAX_ENTRIES = 5000

    /*
     * How often the UI snapshot may be rebuilt. Rebuilding a 5000 element list
     * on every single log line was costing more CPU than the work being logged.
     */
    private const val PUBLISH_INTERVAL_MS = 400L

    /*
     * The deque is the real buffer: appending and trimming are both constant
     * time. The StateFlow only ever carries a snapshot, and one is produced at
     * most every PUBLISH_INTERVAL_MS, and only while something is observing it.
     */
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val lock = Any()

    @Volatile
    private var lastPublishedAtMs = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun append(level: Int, tag: String?, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)

        val snapshot =
            synchronized(lock) {
                buffer.addLast(entry)
                while (buffer.size > MAX_ENTRIES) {
                    buffer.removeFirst()
                }

                val now = entry.time
                val observed = _logs.subscriptionCount.value > 0
                if (!observed || now - lastPublishedAtMs < PUBLISH_INTERVAL_MS) {
                    null
                } else {
                    lastPublishedAtMs = now
                    buffer.toList()
                }
            }

        if (snapshot != null) _logs.value = snapshot
    }

    /*
     * Everything currently held, regardless of when the last snapshot went out.
     * Sharing and exporting must never miss the newest lines.
     */
    fun snapshot(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    /*
     * Publish immediately. The viewer calls this when it opens, otherwise it
     * would show whatever snapshot happened to be current when the last
     * observer went away.
     */
    fun refresh() {
        val snapshot = synchronized(lock) {
            lastPublishedAtMs = System.currentTimeMillis()
            buffer.toList()
        }
        _logs.value = snapshot
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            lastPublishedAtMs = 0L
        }
        _logs.value = emptyList()
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
