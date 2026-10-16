package com.nikhil.yt.ui.component

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDayPeriodTest {
    @Test fun boundariesAreLocalAndExact() {
        assertEquals(HomeDayPeriod.NIGHT, homeDayPeriod(4))
        assertEquals(HomeDayPeriod.MORNING, homeDayPeriod(5))
        assertEquals(HomeDayPeriod.MORNING, homeDayPeriod(11))
        assertEquals(HomeDayPeriod.AFTERNOON, homeDayPeriod(12))
        assertEquals(HomeDayPeriod.AFTERNOON, homeDayPeriod(17))
        assertEquals(HomeDayPeriod.EVENING, homeDayPeriod(18))
        assertEquals(HomeDayPeriod.EVENING, homeDayPeriod(22))
        assertEquals(HomeDayPeriod.NIGHT, homeDayPeriod(23))
    }
    @Test fun boundarySchedulesWithoutMinutePolling() {
        val utc = ZonedDateTime.of(2026, 10, 16, 11, 59, 30, 0, ZoneId.of("UTC"))
        assertEquals(30000L, millisUntilHomeGreetingChanges(utc))
        val late = ZonedDateTime.of(2026, 10, 16, 23, 1, 0, 0, ZoneId.of("UTC"))
        assertTrue(millisUntilHomeGreetingChanges(late) > 5L * 60 * 60 * 1000)
    }
}
