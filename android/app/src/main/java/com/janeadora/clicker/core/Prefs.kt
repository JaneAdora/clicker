package com.janeadora.clicker.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

enum class DpadStyle { Buttons, Swipe }

/** The comfort settings under More, with their defaults, plus remembered app state. */
data class Settings(
    val theme: ThemeMode = ThemeMode.System,
    val dpad: DpadStyle = DpadStyle.Buttons,
    val haptics: Boolean = true,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = false,
    /** The user refused local network access; show the notice instead of prompting on every start. */
    val localNetworkDenied: Boolean = false,
)

/**
 * Builds the settings DataStore. Production and the tests both use this, so
 * the tests cover the real wiring. A corrupt file is replaced with defaults,
 * so it can never block writes.
 */
internal fun createSettingsStore(scope: CoroutineScope, produceFile: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = produceFile,
    )

// One DataStore per process for the settings file, so every Prefs shares it.
// Same file and scope as the preferencesDataStore delegate would use.
private val storeLock = Any()
private var settingsStore: DataStore<Preferences>? = null

private fun settingsStore(context: Context): DataStore<Preferences> = synchronized(storeLock) {
    settingsStore ?: createSettingsStore(CoroutineScope(Dispatchers.IO + SupervisorJob())) {
        context.applicationContext.preferencesDataStoreFile("settings")
    }.also { settingsStore = it }
}

/** Settings kept on disk with DataStore. Unknown or missing values read as the defaults. */
class Prefs internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(settingsStore(context))

    val settings: Flow<Settings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::read)

    suspend fun update(f: (Settings) -> Settings) {
        store.edit { p -> write(p, f(read(p))) }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val DPAD = stringPreferencesKey("dpad")
        val HAPTICS = booleanPreferencesKey("haptics")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val LOCAL_NETWORK_DENIED = booleanPreferencesKey("local_network_denied")
        val DEFAULTS = Settings()

        fun read(p: Preferences) = Settings(
            theme = ThemeMode.entries.firstOrNull { it.name == p[THEME] } ?: DEFAULTS.theme,
            dpad = DpadStyle.entries.firstOrNull { it.name == p[DPAD] } ?: DEFAULTS.dpad,
            haptics = p[HAPTICS] ?: DEFAULTS.haptics,
            volumeKeys = p[VOLUME_KEYS] ?: DEFAULTS.volumeKeys,
            keepScreenOn = p[KEEP_SCREEN_ON] ?: DEFAULTS.keepScreenOn,
            localNetworkDenied = p[LOCAL_NETWORK_DENIED] ?: DEFAULTS.localNetworkDenied,
        )

        fun write(p: MutablePreferences, s: Settings) {
            p[THEME] = s.theme.name
            p[DPAD] = s.dpad.name
            p[HAPTICS] = s.haptics
            p[VOLUME_KEYS] = s.volumeKeys
            p[KEEP_SCREEN_ON] = s.keepScreenOn
            p[LOCAL_NETWORK_DENIED] = s.localNetworkDenied
        }
    }
}
