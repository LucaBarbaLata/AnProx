package io.github.lucabarbalata.anprox.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.lucabarbalata.anprox.core.designsystem.theme.ThemeMode
import io.github.lucabarbalata.anprox.data.security.AppLock
import io.github.lucabarbalata.anprox.model.alerts.AlertSettings
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
    /** Optional app lock (questionnaire Q11). */
    val appLock: Boolean = false,
    /** How long the app may be in the background before it locks again (0 = every time). */
    val lockAfterMillis: Long = AppLock.DEFAULT_LOCK_AFTER_MILLIS,
    /** With the app lock on, check again before actions that can't be undone. */
    val confirmDangerous: Boolean = true,
    /** Guests pinned to the dashboard, as [favouriteKey]s. */
    val favourites: Set<String> = emptySet(),
    /** Background alerts (questionnaire Q26/Q27). */
    val alerts: AlertSettings = AlertSettings(),
)

/** Identifies a guest independently of its node, so a favourite survives migrations. */
fun favouriteKey(serverId: String, type: String, vmid: Int): String = "$serverId|$type|$vmid"

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val themeKey = stringPreferencesKey("theme_mode")
    private val pureBlackKey = booleanPreferencesKey("pure_black")
    private val refreshKey = longPreferencesKey("refresh_interval_ms")
    private val appLockKey = booleanPreferencesKey("app_lock")
    private val lockAfterKey = longPreferencesKey("lock_after_ms")
    private val confirmDangerousKey = booleanPreferencesKey("confirm_dangerous")
    private val favouritesKey = stringSetPreferencesKey("favourite_guests")
    private val alertsKey = stringPreferencesKey("alert_settings")

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                pureBlack = prefs[pureBlackKey] ?: false,
                refreshIntervalMillis = prefs[refreshKey] ?: 5_000,
                appLock = prefs[appLockKey] ?: false,
                lockAfterMillis = prefs[lockAfterKey] ?: AppLock.DEFAULT_LOCK_AFTER_MILLIS,
                confirmDangerous = prefs[confirmDangerousKey] ?: true,
                favourites = prefs[favouritesKey].orEmpty(),
                alerts = prefs[alertsKey]?.let { runCatching { StorageJson.decodeFromString<AlertSettings>(it) }.getOrNull() } ?: AlertSettings(),
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[themeKey] = mode.name }
    suspend fun setPureBlack(enabled: Boolean) = store.edit { it[pureBlackKey] = enabled }
    suspend fun setRefreshInterval(millis: Long) = store.edit { it[refreshKey] = millis }
    suspend fun setAppLock(enabled: Boolean) = store.edit { it[appLockKey] = enabled }
    suspend fun setLockAfter(millis: Long) = store.edit { it[lockAfterKey] = millis }
    suspend fun setConfirmDangerous(enabled: Boolean) = store.edit { it[confirmDangerousKey] = enabled }

    suspend fun setAlerts(alerts: AlertSettings) = store.edit { it[alertsKey] = StorageJson.encodeToString(alerts) }

    suspend fun setFavourite(key: String, favourite: Boolean) = store.edit {
        val current = it[favouritesKey].orEmpty()
        it[favouritesKey] = if (favourite) current + key else current - key
    }
}
