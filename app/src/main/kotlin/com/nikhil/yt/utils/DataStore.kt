/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.utils

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.nikhil.yt.constants.AudioOffload
import com.nikhil.yt.extensions.toEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.properties.ReadOnlyProperty

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object PreferenceStore {
    private const val INITIAL_LOAD_TIMEOUT_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _prefs = MutableStateFlow<Preferences?>(null)

    @Volatile
    private var started = false

    /**
     * Prime the in-memory snapshot before exposing the store as started.
     *
     * A large part of the app still has synchronous preference delegates. If
     * those are read on the main thread before DataStore's collector produces
     * its first value, silently returning defaults makes user settings appear
     * to reset after a cold start. One bounded disk read here gives all later
     * synchronous reads a real snapshot; continuous updates stay asynchronous.
     *
     * Audio offload is also migrated here, before MusicService can be created.
     * This removes the cold-start race where App wanted the efficient default
     * but the service briefly interpreted a missing key as disabled. An
     * explicit user value of false is never overwritten.
     */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return

            val initialPreferences =
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(INITIAL_LOAD_TIMEOUT_MS) {
                        var snapshot = context.dataStore.data.first()
                        if (snapshot[AudioOffload] == null) {
                            context.dataStore.edit { prefs ->
                                if (prefs[AudioOffload] == null) {
                                    prefs[AudioOffload] = true
                                }
                            }
                            snapshot = context.dataStore.data.first()
                        }
                        snapshot
                    }
                }

            if (initialPreferences != null) {
                _prefs.value = initialPreferences
            } else {
                Timber.tag("PreferenceStore").w(
                    "Initial DataStore snapshot was not available within %d ms",
                    INITIAL_LOAD_TIMEOUT_MS,
                )
            }

            started = true
            scope.launch {
                context.dataStore.data.collect { preferences ->
                    _prefs.value = preferences
                }
            }
        }
    }

    fun <T> get(key: Preferences.Key<T>): T? = _prefs.value?.get(key)

    fun launchEdit(
        dataStore: DataStore<Preferences>,
        block: MutablePreferences.() -> Unit,
    ) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs.block()
            }
        }
    }
}

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? =
    PreferenceStore.get(key)
        ?: if (Looper.getMainLooper().thread == Thread.currentThread()) {
            null
        } else {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(1500) {
                    data.first()[key]
                }
            }
        }

fun <T> DataStore<Preferences>.get(
    key: Preferences.Key<T>,
    defaultValue: T,
): T =
    PreferenceStore.get(key)
        ?: if (Looper.getMainLooper().thread == Thread.currentThread()) {
            defaultValue
        } else {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(1500) {
                    data.first()[key]
                } ?: defaultValue
            }
        }

suspend fun <T> DataStore<Preferences>.getAsync(key: Preferences.Key<T>): T? =
    data.first()[key]

suspend fun <T> DataStore<Preferences>.getAsync(
    key: Preferences.Key<T>,
    defaultValue: T,
): T = data.first()[key] ?: defaultValue

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current

    val state =
        remember {
            context.dataStore.data
                .map { it[key] ?: defaultValue }
                .distinctUntilChanged()
        }.collectAsState(defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    PreferenceStore.launchEdit(context.dataStore) {
                        this[key] = value
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current

    val state =
        remember {
            context.dataStore.data
                .map { it[key].toEnum(defaultValue = defaultValue) }
                .distinctUntilChanged()
        }.collectAsState(defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    PreferenceStore.launchEdit(context.dataStore) {
                        this[key] = value.name
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
