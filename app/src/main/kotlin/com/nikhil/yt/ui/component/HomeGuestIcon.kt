package com.nikhil.yt.ui.component

import com.nikhil.yt.R

/**
 * Only an unauthenticated Home account shortcut receives the time-of-day icon.
 * Authenticated sessions retain their photo (or existing initial while it loads).
 */
internal fun homeGuestIcon(signedIn: Boolean, period: HomeDayPeriod): Int? =
    when {
        signedIn -> null
        period == HomeDayPeriod.NIGHT -> R.drawable.bedtime
        else -> R.drawable.capsule_sun
    }
