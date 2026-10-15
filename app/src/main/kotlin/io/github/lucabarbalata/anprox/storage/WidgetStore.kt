package io.github.lucabarbalata.anprox.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.lucabarbalata.anprox.model.favourites.FavouritesSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.IOException

/** A button on the favourites widget that was tapped once and waits for the second tap. */
@Serializable
data class WidgetConfirm(val key: String, val action: String, val atMillis: Long) {
    fun isFresh(now: Long): Boolean = now - atMillis in 0..WINDOW_MILLIS

    companion object {
        /** How long the second tap is waited for. */
        const val WINDOW_MILLIS = 8_000L
    }
}

/**
 * What the favourites widget shows (questionnaire Q28), in its own DataStore file: the guests as
 * the last refresh saw them, a button waiting for its second tap and the actions on their way.
 */
class WidgetStore(context: Context) {
    private val store = context.applicationContext.widgetsDataStore
    private val favouritesKey = stringPreferencesKey("favourites")
    private val confirmKey = stringPreferencesKey("confirm")
    private val busyKey = stringSetPreferencesKey("busy")
    private val errorKey = stringPreferencesKey("error")

    private val data = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val favourites: Flow<FavouritesSnapshot?> = data.map { prefs ->
        prefs[favouritesKey]?.let { runCatching { StorageJson.decodeFromString<FavouritesSnapshot>(it) }.getOrNull() }
    }

    val confirm: Flow<WidgetConfirm?> = data.map { prefs ->
        prefs[confirmKey]?.let { runCatching { StorageJson.decodeFromString<WidgetConfirm>(it) }.getOrNull() }
    }

    /** Favourites (by key) with an action on its way. */
    val busy: Flow<Set<String>> = data.map { it[busyKey].orEmpty() }

    /** Why the last action failed, until the next refresh. */
    val error: Flow<String?> = data.map { it[errorKey] }

    suspend fun setFavourites(snapshot: FavouritesSnapshot) {
        store.edit {
            it[favouritesKey] = StorageJson.encodeToString(snapshot)
            it.remove(errorKey)
        }
    }

    suspend fun setConfirm(confirm: WidgetConfirm?) {
        store.edit { if (confirm == null) it.remove(confirmKey) else it[confirmKey] = StorageJson.encodeToString(confirm) }
    }

    /** Clears the waiting button if its time is up. */
    suspend fun expireConfirm(now: Long) {
        if (confirm.first()?.isFresh(now) == false) setConfirm(null)
    }

    suspend fun setBusy(key: String, busy: Boolean) {
        store.edit {
            val current = it[busyKey].orEmpty()
            it[busyKey] = if (busy) current + key else current - key
        }
    }

    suspend fun setError(message: String?) {
        store.edit { if (message == null) it.remove(errorKey) else it[errorKey] = message }
    }
}
