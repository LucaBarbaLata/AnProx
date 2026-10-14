package io.github.lucabarbalata.anprox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.core.designsystem.theme.ThemeMode
import io.github.lucabarbalata.anprox.storage.AppSettings
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repo.setThemeMode(mode) }
    }

    fun setPureBlack(enabled: Boolean) {
        viewModelScope.launch { repo.setPureBlack(enabled) }
    }

    fun setRefreshInterval(millis: Long) {
        viewModelScope.launch { repo.setRefreshInterval(millis) }
    }

    /** Call only after the user has authenticated (the screen does that). */
    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch { repo.setAppLock(enabled) }
    }

    fun setLockAfter(millis: Long) {
        viewModelScope.launch { repo.setLockAfter(millis) }
    }

    fun setConfirmDangerous(enabled: Boolean) {
        viewModelScope.launch { repo.setConfirmDangerous(enabled) }
    }
}
