package com.nikhil.yt.constants

import androidx.datastore.preferences.core.MutablePreferences

/** Keep only the old storage spelling, never a retired screen or renderer. */
internal fun migrateRetiredPlayerDesign(preferences: MutablePreferences) {
    if (preferences[CapsulePlayerDesignKey] == "SUPER") {
        preferences[CapsulePlayerDesignKey] = CapsulePlayerDesign.LIGHT.name
    }
}
