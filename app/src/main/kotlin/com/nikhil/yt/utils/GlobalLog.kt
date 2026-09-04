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
     * Identical hot-path callbacks can arrive twice within the same frame or
     * loader hand-off (normalization was a common example). Keeping both adds
     * zero diagnostic value but still allocates and copies strings while field
     * logging is enabled. Only exact duplicates in a very small window are
     * collapsed; later repetitions are preserved.
     */
    private const val DUPLICATE_WINDOW_MS = 250L

    /*
     * How often the UI snapshot may be rebuilt. Rebuilding a 5000 element list
     * on every single log line was costing more CPU than the work being logged.
     */
    private const val PUBLISH_INTERVAL_MS = 1_000L

    /*
     * The deque is the real buffer: appending and trimming are both constant
     * time. The StateFlow only ever carries a snapshot, and one is produced at
     * most every PUBLISH_INTERVAL_MS, and only while something is observing it.
     */
    /* Do not reserve storage for 5000 entries while logging is disabled. */
    private val buffer = ArrayDeque<LogEntry>()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val lock = Any()

    @Volatile
    private var enabled = false

    @Volatile
    private var lastPublishedAtMs = 0L

    private var lastAcceptedLevel: Int = Int.MIN_VALUE
    private var lastAcceptedTag: String? = null
    private var lastAcceptedMessage: String? = null
    private var lastAcceptedAtMs: Long = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    val isEnabled: Boolean
        get() = enabled

    internal fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) clear()
    }

    fun append(level: Int, tag: String?, message: String) {
        if (!enabled) return

        val now = System.currentTimeMillis()
        val entry = LogEntry(now, level, tag, message)

        val snapshot =
            synchronized(lock) {
                val isBurstDuplicate =
                    level == lastAcceptedLevel &&
                        tag == lastAcceptedTag &&
                        message == lastAcceptedMessage &&
                        now - lastAcceptedAtMs in 0..DUPLICATE_WINDOW_MS

                if (isBurstDuplicate) return@synchronized null

                lastAcceptedLevel = level
                lastAcceptedTag = tag
                lastAcceptedMessage = message
                lastAcceptedAtMs = now

                buffer.addLast(entry)
                while (buffer.size > MAX_ENTRIES) {
                    buffer.removeFirst()
                }

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
    fun snapshot(): List<LogEntry> {
        if (!enabled) return emptyList()
        return synchronized(lock) { buffer.toList() }
    }

    /*
     * Publish immediately. The viewer calls this when it opens, otherwise it
     * would show whatever snapshot happened to be current when the last
     * observer went away.
     */
    fun refresh() {
        if (!enabled) {
            _logs.value = emptyList()
            return
        }

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
            lastAcceptedLevel = Int.MIN_VALUE
            lastAcceptedTag = null
            lastAcceptedMessage = null
            lastAcceptedAtMs = 0L
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
        if (!GlobalLog.isEnabled) return

        try {
            val final = if (t != null) "$message\n$t" else message
            GlobalLog.append(priority, tag, final)
        } catch (_: Exception) {
            // Logging must never be allowed to crash the process or recurse.
        }
    }
}

/**
 * Owns the only Timber tree used by the main process.
 *
 * The diagnostics screen needs the in-app buffer, not a second copy in
 * Logcat. Keeping only [GlobalLogTree] halves the hot-path work while a field
 * log is being recorded. When logging is disabled Timber's forest is empty,
 * so call sites return before message formatting or stack-trace rendering.
 * Direct [GlobalLog.append] callers are also stopped by the guard in
 * [GlobalLog].
 */
object DebugLoggingController {
    private val globalLogTree = GlobalLogTree()

    @Volatile
    private var enabled = false

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return

        if (value) {
            GlobalLog.setEnabled(true)
            Timber.plant(globalLogTree)
        } else {
            Timber.uproot(globalLogTree)
            GlobalLog.setEnabled(false)
        }

        enabled = value
    }
}