package com.nikhil.yt.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber

/**
 * What the system was doing to this app when playback stopped moving.
 *
 * A capture showed six stretches where the player believed it was playing — playWhenReady true,
 * state READY, ninety seconds of audio decoded and waiting — while the position advanced four
 * seconds in five minutes of wall clock. The listener had turned the screen off. Something is
 * suspending playback, and the candidates are all outside this app's own reasoning: Doze, an
 * OEM power saver, or the wake lock not being what it is believed to be. Nothing in any capture
 * so far can tell them apart, and each has a different remedy — one is ours to fix in code, two
 * are only fixable by asking the listener for an exemption.
 *
 * Broadcasts only. The screen and the power modes announce themselves, so this costs nothing at
 * all while nothing happens: no loop, no timer, no periodic sampling, and nothing registered
 * while the service is not running.
 */
internal class PlaybackPowerWatch(
    context: Context,
    private val isPlaying: () -> Boolean,
    private val isWakeLockHeld: () -> Boolean = { false },
) {
    /*
     * No Context is kept.
     *
     * [describeNow] hands this object to code that has no route to a Context, which means a
     * process-wide reference to it — and a Context behind one of those leaks whatever it belongs
     * to the moment stop() is missed. Only what the reading needs is kept: the power manager,
     * which is a process-wide system service, and the package name, which is a string. The
     * Context for registering and unregistering is passed in at those two moments instead.
     */
    private val power: PowerManager? =
        ContextCompat.getSystemService(context, PowerManager::class.java)

    private val packageName: String = context.packageName

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                report(
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> "screen-off"
                        Intent.ACTION_SCREEN_ON -> "screen-on"
                        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "doze-changed"
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "power-save-changed"
                        else -> return
                    },
                )
            }
        }

    private var registered = false

    fun start(context: Context) {
        if (registered) return
        current = this
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        // Not exported: these are system broadcasts and nothing else may reach this receiver.
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        /*
         * A baseline, once per service.
         *
         * batteryOptimised is a fixed fact about this install and it decides which remedy
         * applies, but it never announces itself — waiting for a transition to learn it means a
         * capture with no screen-off in it cannot answer the question at all.
         */
        report("watch-started")
    }

    fun stop(context: Context) {
        if (current === this) current = null
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    companion object {
        /**
         * The watch belonging to the running service, for code that cannot be handed one.
         *
         * The audio-sink diagnostics live in an ExoPlayer extension with no route to a Context,
         * and the power state has to be read *at the moment a frozen stretch is noticed* — by
         * the time the screen comes back on, Doze has already lifted and the reading says
         * nothing. A process-wide reference for a diagnostic is a poor seam, and it is here
         * rather than anywhere load-bearing: nothing reads it except logging, and it is null
         * whenever there is no foreground service.
         */
        @Volatile
        private var current: PlaybackPowerWatch? = null

        /** The power picture right now, or null when there is no service to ask. */
        fun describeNow(): String? = current?.describe()
    }

    /** The current power picture, as one line. Also used to annotate a frozen stretch. */
    fun describe(): String = buildString {
        append("interactive=").append(power?.isInteractive)
        append(" doze=").append(power?.isDeviceIdleMode)
        append(" powerSave=").append(power?.isPowerSaveMode)
        // Reported as "optimised", because that is the state that suspends playback.
        append(" batteryOptimised=")
            .append(power?.isIgnoringBatteryOptimizations(packageName)?.let { !it })
        // Stated rather than assumed: the last capture ruled out every system explanation, which
        // left only "nothing was keeping the CPU awake" — and that was not something the capture
        // could confirm either way.
        append(" wakeLock=").append(isWakeLockHeld())
    }

    private fun report(event: String) {
        if (!GlobalLog.isEnabled) return
        Timber.tag("PlaybackPower").i(
            "power %s playing=%s %s",
            event,
            isPlaying(),
            describe(),
        )
    }
}
