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
import kotlinx.coroutines.Job
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

    private var collector: Job? = null

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
            collector =
                scope.launch {
                    context.dataStore.data.collect { preferences ->
                        _prefs.value = preferences
                    }
                }
        }
    }

    /**
     * Unbinds the store from the DataStore it was started with.
     *
     * This exists for tests, and for a reason that is not a detail. The store is a process-wide
     * singleton that guards itself with a one-shot `started` flag, so the *first* call to [start]
     * decides which DataStore feeds the snapshot for the life of the JVM. That is exactly right in
     * an app, which has one. It is wrong under a test runner, where every test gets a fresh
     * Application and therefore a fresh DataStore: the second test onwards calls [start], gets a
     * silent no-op, and then reads a snapshot fed by a collector still attached to a DataStore that
     * no longer exists. Values written by that test never arrive, and whether a given test notices
     * depends on what the previous one happened to leave behind — which is a test that passes or
     * fails on execution order.
     *
     * Nothing in the app should call this.
     */
    internal fun resetForTesting() {
        synchronized(this) {
            collector?.cancel()
            collector = null
            _prefs.value = null
            started = false
        }
    }

    fun snapshot(): Preferences? = _prefs.value

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

/**
 * A preference as Compose state, correct from the very first composition.
 *
 * The initial value comes from [PreferenceStore]'s in-memory snapshot rather than from
 * [defaultValue]. DataStore's flow is asynchronous, so seeding with the default meant every screen
 * whose shape depends on a setting rendered the *default* shape first and corrected itself a frame
 * or more later. Opening the library with a saved filter showed the stock tab and then jumped to
 * the real one; the same flash applied to anything else keyed off a preference.
 *
 * The snapshot is primed before the first screen is composed, so this is a real value, not a guess.
 * [defaultValue] still applies when the key has never been written, and while the snapshot is
 * somehow unavailable.
 */
@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current

    val initialValue = remember(key) { PreferenceStore.get(key) ?: defaultValue }
    val state =
        remember {
            context.dataStore.data
                .map { it[key] ?: defaultValue }
                .distinctUntilChanged()
        }.collectAsState(initialValue)

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

/** As [rememberPreference], seeded from the primed snapshot so no screen flashes its default. */
@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current

    val initialValue = remember(key) { PreferenceStore.get(key).toEnum(defaultValue) }
    val state =
        remember {
            context.dataStore.data
                .map { it[key].toEnum(defaultValue = defaultValue) }
                .distinctUntilChanged()
        }.collectAsState(initialValue)

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
