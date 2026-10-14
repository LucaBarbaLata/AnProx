package io.github.lucabarbalata.anprox.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lucabarbalata.anprox.data.store.ProfileStore
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Saved servers as one JSON document in a Preferences DataStore. Contains no secrets. */
class DataStoreProfileStore(context: Context) : ProfileStore {
    private val store = context.applicationContext.profilesDataStore
    private val key = stringPreferencesKey("profiles_json")

    override val profiles: Flow<List<ServerProfile>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decode(it) }

    override suspend fun upsert(profile: ServerProfile) {
        store.edit { prefs ->
            val list = decode(prefs).filterNot { it.id == profile.id } + profile
            prefs[key] = StorageJson.encodeToString(list)
        }
    }

    override suspend fun delete(id: String) {
        store.edit { prefs -> prefs[key] = StorageJson.encodeToString(decode(prefs).filterNot { it.id == id }) }
    }

    private fun decode(prefs: Preferences): List<ServerProfile> =
        prefs[key]?.let { runCatching { StorageJson.decodeFromString<List<ServerProfile>>(it) }.getOrNull() } ?: emptyList()
}
