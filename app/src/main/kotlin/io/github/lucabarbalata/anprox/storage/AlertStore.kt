package io.github.lucabarbalata.anprox.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lucabarbalata.anprox.data.alerts.AlertMemoryStore
import io.github.lucabarbalata.anprox.model.alerts.AlertMemory
import io.github.lucabarbalata.anprox.model.alerts.AlertRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Background-alert state in its own DataStore file: what the previous check saw on each
 * server ([AlertMemory]) and a summary of the latest run.
 */
class AlertStore(context: Context) : AlertMemoryStore {
    private val store = context.applicationContext.alertsDataStore
    private val lastRunKey = stringPreferencesKey("last_run")

    private fun memoryKey(serverId: String) = stringPreferencesKey("memory_$serverId")

    private val data = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val lastRun: Flow<AlertRun?> = data.map { prefs ->
        prefs[lastRunKey]?.let { runCatching { StorageJson.decodeFromString<AlertRun>(it) }.getOrNull() }
    }

    suspend fun setLastRun(run: AlertRun) {
        store.edit { it[lastRunKey] = StorageJson.encodeToString(run) }
    }

    override suspend fun load(serverId: String): AlertMemory? =
        data.first()[memoryKey(serverId)]?.let { runCatching { StorageJson.decodeFromString<AlertMemory>(it) }.getOrNull() }

    override suspend fun save(serverId: String, memory: AlertMemory) {
        store.edit { it[memoryKey(serverId)] = StorageJson.encodeToString(memory) }
    }
}
