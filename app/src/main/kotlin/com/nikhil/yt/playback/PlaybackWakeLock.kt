package com.nikhil.yt.playback

import android.content.Context
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber

/** The tag the system shows for this lock in battery reports. */
private const val WAKE_LOCK_TAG = "Capsule:playback"

/**
 * A backstop, not a policy. The lock is released on the same callback that took it, so this only
 * ever fires if that release is somehow missed — and then the system takes it back instead of the
 * battery draining until the phone is rebooted.
 *
 * Eight hours, because the cost of being wrong in each direction is not the same. Too short and
 * it expires mid-listen and reintroduces the exact freeze this exists to fix; too long and a
 * leaked lock lives longer before the system cleans up after it. Nobody plays eight hours without
 * a single pause, and if they do, the symptom comes back rather than their battery dying.
 */
private const val MAX_HELD_MS = 8L * 60 * 60 * 1000

/**
 * A CPU wake lock of this app's own, held only while audio is actually playing.
 *
 * Media3 is asked for one already — the player is built with WAKE_MODE_LOCAL, which is supposed
 * to take a partial wake lock whenever playWhenReady is set and the player is not idle. A capture
 * says that is not enough in practice. With the screen off, playback froze: the player reported
 * playWhenReady true and READY with eighty-three seconds of audio already decoded and the whole
 * track downloaded, and the position advanced 2.4 seconds across 335 seconds of wall clock. There
 * was nothing left for it to wait for.
 *
 * The three system explanations are ruled out by the same capture: doze false, powerSave false,
 * batteryOptimised false, and the WAKE_LOCK permission is declared. What is left is that nothing
 * was keeping the CPU awake, so this holds one directly rather than trusting that something else
 * already did.
 *
 * Bounded by construction. It is taken when playback starts and dropped the moment it stops, it
 * is not reference counted so a release can never be missed by being one short, and the service
 * drops it again when it goes away. Nothing here runs on a timer or a loop: both edges are
 * driven by the player's own isPlaying callback.
 *
 * If the next capture shows playback still freezing while this is held, then the CPU was never
 * the problem and this should come out again rather than stay as a charm.
 */
internal class PlaybackWakeLock(context: Context) {
    private val lock: PowerManager.WakeLock? =
        ContextCompat.getSystemService(context, PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    /** Whether the lock is currently held, for the capture to state rather than imply. */
    val isHeld: Boolean get() = lock?.isHeld == true

    fun setPlaying(playing: Boolean) {
        val lock = lock ?: return
        if (playing == lock.isHeld) return
        try {
            if (playing) lock.acquire(MAX_HELD_MS) else lock.release()
        } catch (failure: RuntimeException) {
            // A lock that cannot be released is worth a line; it is a battery fault, not a
            // playback one, and it would otherwise be invisible.
            if (GlobalLog.isEnabled) {
                Timber.tag("PlaybackPower").w(failure, "wake-lock %s failed", if (playing) "acquire" else "release")
            }
            return
        }
        if (GlobalLog.isEnabled) {
            Timber.tag("PlaybackPower").i(
                "wake-lock %s held=%s",
                if (playing) "acquired" else "released",
                lock.isHeld,
            )
        }
    }

    /** Dropped unconditionally when the service goes away, whatever the player last said. */
    fun release() = setPlaying(false)
}
