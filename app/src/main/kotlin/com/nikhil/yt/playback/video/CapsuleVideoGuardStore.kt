/*
 * Capsule MUSIC
 * Persistence bridge for the VIDEO-only request circuit breaker.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.content.Context
import com.nikhil.yt.innertube.CapsuleVideoRequestGuard
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

internal object CapsuleVideoGuardStore {
    private val initialized = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        val prefs =
            context.applicationContext.getSharedPreferences(
                "capsule_video_guard",
                Context.MODE_PRIVATE,
            )

        CapsuleVideoRequestGuard.restore(
            CapsuleVideoRequestGuard.Snapshot(
                blockedUntilMs = prefs.getLong("blocked_until", 0L),
                escalationLevel = prefs.getInt("escalation_level", 0),
                lastTripAtMs = prefs.getLong("last_trip", 0L),
                reason = prefs.getString("reason", null),
            ),
        )

        val persist: (CapsuleVideoRequestGuard.Snapshot) -> Unit = { snapshot ->
            Timber.tag("CapsuleVideoGuard").i("state reason=%s untilMs=%s remainingMs=%s escalation=%s lastTripMs=%s",
                snapshot.reason ?: "none", snapshot.blockedUntilMs,
                (snapshot.blockedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L), snapshot.escalationLevel, snapshot.lastTripAtMs)
            prefs.edit()
                .putLong("blocked_until", snapshot.blockedUntilMs)
                .putInt("escalation_level", snapshot.escalationLevel)
                .putLong("last_trip", snapshot.lastTripAtMs)
                .putString("reason", snapshot.reason)
                .apply()
        }
        CapsuleVideoRequestGuard.onStateChanged = persist
        // Save migrated 403-only bans immediately, rather than restoring them again.
        persist(CapsuleVideoRequestGuard.snapshot())
    }
}
