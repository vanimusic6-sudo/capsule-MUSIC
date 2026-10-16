package com.nikhil.yt.ui.component

import com.nikhil.yt.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeGuestIconTest {
    @Test fun signedInUsersAlwaysRetainAvatar() {
        HomeDayPeriod.entries.forEach { assertNull(homeGuestIcon(true, it)) }
    }
    @Test fun guestsSeeSunInMorningAfternoonAndEvening() {
        listOf(HomeDayPeriod.MORNING, HomeDayPeriod.AFTERNOON, HomeDayPeriod.EVENING)
            .forEach { assertEquals(R.drawable.capsule_sun, homeGuestIcon(false, it)) }
    }
    @Test fun guestsSeeMoonAtNight() {
        assertEquals(R.drawable.bedtime, homeGuestIcon(false, HomeDayPeriod.NIGHT))
    }
}
