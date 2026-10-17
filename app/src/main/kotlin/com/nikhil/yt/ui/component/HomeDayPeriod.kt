package com.nikhil.yt.ui.component

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nikhil.yt.R
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

internal enum class HomeDayPeriod(val greetingId: Int) {
    MORNING(R.string.home_greeting_morning),
    AFTERNOON(R.string.home_greeting_afternoon),
    EVENING(R.string.home_greeting_evening),
    NIGHT(R.string.home_greeting_night),
}

/** Local wall-clock boundaries; independent of system Dark Mode and server time. */
internal fun homeDayPeriod(hour: Int): HomeDayPeriod {
    require(hour in 0..23)
    return when (hour) {
        in 5..11 -> HomeDayPeriod.MORNING
        in 12..17 -> HomeDayPeriod.AFTERNOON
        in 18..22 -> HomeDayPeriod.EVENING
        else -> HomeDayPeriod.NIGHT
    }
}

internal fun millisUntilHomeGreetingChanges(now: ZonedDateTime): Long {
    val nextHour = listOf(5, 12, 18, 23).firstOrNull { it > now.hour }
    val next = if (nextHour != null) now.toLocalDate().atTime(nextHour, 0)
        .atZone(now.zone)
    else now.toLocalDate().plusDays(1).atTime(5, 0).atZone(now.zone)
    // At the exact boundary, schedule for the following daypart instead of spinning.
    return Duration.between(now, next).toMillis().coerceAtLeast(1000L)
}

/**
 * One pending delay, ONLY while the Home header is visible and resumed.
 * Manual clock/timezone edits and returning from background invalidate the delay.
 * No polling loop, no location access, no services or network calls.
 */
@Composable
internal fun rememberHomeDayPeriod(): HomeDayPeriod {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var period by remember { mutableStateOf(homeDayPeriod(ZonedDateTime.now().hour)) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var clockRevision by remember { mutableIntStateOf(0) }

    DisposableEffect(context, owner) {
        fun update() {
            period = homeDayPeriod(ZonedDateTime.now().hour)
            clockRevision++
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed = true
                update()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                resumed = false
            }
        }
        owner.lifecycle.addObserver(lifecycleObserver)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = update()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.unregisterReceiver(receiver)
            owner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
    LaunchedEffect(resumed, clockRevision) {
        if (!resumed) return@LaunchedEffect
        delay(millisUntilHomeGreetingChanges(ZonedDateTime.now()))
        period = homeDayPeriod(ZonedDateTime.now().hour)
        clockRevision++
    }
    return period
}
