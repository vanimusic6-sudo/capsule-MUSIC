package com.nikhil.yt.utils

import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalLogTest {
    @After
    fun tearDown() {
        GlobalLog.setEnabled(false)
    }

    @Test
    fun disabledLoggerDoesNotRetainEntries() {
        GlobalLog.setEnabled(false)
        GlobalLog.append(Log.INFO, "test", "must not be retained")

        assertTrue(GlobalLog.snapshot().isEmpty())
    }

    @Test
    fun disablingLoggerClearsExistingBuffer() {
        GlobalLog.setEnabled(true)
        GlobalLog.append(Log.INFO, "test", "retained while enabled")
        assertEquals(1, GlobalLog.snapshot().size)

        GlobalLog.setEnabled(false)

        assertTrue(GlobalLog.snapshot().isEmpty())
        assertTrue(GlobalLog.logs.value.isEmpty())
    }
}
