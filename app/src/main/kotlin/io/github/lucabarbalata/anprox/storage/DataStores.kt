package io.github.lucabarbalata.anprox.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.json.Json

/*
 * Jetpack DataStore files. Each `preferencesDataStore` delegate must exist exactly once per file
 * name in the whole process, so they all live here.
 */
internal val Context.profilesDataStore: DataStore<Preferences> by preferencesDataStore(name = "profiles")
internal val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * JSON used for on-device storage. Unlike the API's ProxmoxJson it writes default values, so
 * changing a default in code never silently changes a saved profile.
 */
internal val StorageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
