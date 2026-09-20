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
    private val context: Context,
    private val isPlaying: () -> Boolean,
) {
    private val power: PowerManager? =
        ContextCompat.getSystemService(context, PowerManager::class.java)

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

    fun start() {
        if (registered) return
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
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    /** The current power picture, as one line. Also used to annotate a frozen stretch. */
    fun describe(): String = buildString {
        append("interactive=").append(power?.isInteractive)
        append(" doze=").append(power?.isDeviceIdleMode)
        append(" powerSave=").append(power?.isPowerSaveMode)
        // Reported as "optimised", because that is the state that suspends playback.
        append(" batteryOptimised=")
            .append(power?.isIgnoringBatteryOptimizations(context.packageName)?.let { !it })
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
