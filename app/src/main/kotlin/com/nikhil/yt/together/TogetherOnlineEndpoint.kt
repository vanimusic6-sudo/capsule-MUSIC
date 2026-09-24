/*
 * Local-network-only Listen Together. The legacy online transport is disabled;
 * no third-party service URL is bundled or contacted by this build.
 */
package com.nikhil.yt.together

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

object TogetherOnlineEndpoint {
    @Suppress("UNUSED_PARAMETER")
    fun baseUrlOrNull(dataStore: DataStore<Preferences>): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun onlineWebSocketUrlOrNull(rawWsUrl: String, baseUrl: String): String? = null
}
