package io.github.lucabarbalata.anprox.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lucabarbalata.anprox.core.designsystem.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** App preferences (not per server). */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pureBlack: Boolean = false,
    /** How often live screens refresh, in milliseconds. */
    val refreshIntervalMillis: Long = 5_000,
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val themeKey = stringPreferencesKey("theme_mode")
    private val pureBlackKey = booleanPreferencesKey("pure_black")
    private val refreshKey = longPreferencesKey("refresh_interval_ms")

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                pureBlack = prefs[pureBlackKey] ?: false,
                refreshIntervalMillis = prefs[refreshKey] ?: 5_000,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[themeKey] = mode.name }
    suspend fun setPureBlack(enabled: Boolean) = store.edit { it[pureBlackKey] = enabled }
    suspend fun setRefreshInterval(millis: Long) = store.edit { it[refreshKey] = millis }
}
