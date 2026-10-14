package io.github.lucabarbalata.anprox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.ThemeMode
import io.github.lucabarbalata.anprox.security.AuthAvailability
import io.github.lucabarbalata.anprox.security.LocalAuthenticator
import io.github.lucabarbalata.anprox.storage.AppSettings
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel

private val REFRESH_OPTIONS = listOf(2_000L, 5_000L, 10_000L, 30_000L)
private val LOCK_AFTER_OPTIONS = listOf(0L, 60_000L, 300_000L)

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val themeLabels = mapOf(
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )
    val refreshLabels = REFRESH_OPTIONS.associateWith { stringResource(R.string.settings_refresh_seconds, it / 1000) }
    AnProxScaffold(title = stringResource(R.string.settings_title), onBack = onBack) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                GlassCard {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall, color = AnProxTheme.colors.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    SegmentedPicker(ThemeMode.entries, settings.themeMode, { themeLabels.getValue(it) }, viewModel::setThemeMode)
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = stringResource(R.string.settings_pure_black),
                        description = stringResource(R.string.settings_pure_black_description),
                        checked = settings.pureBlack,
                        onCheckedChange = viewModel::setPureBlack,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_data)) }
            item {
                GlassCard {
                    Text(stringResource(R.string.settings_refresh), style = MaterialTheme.typography.titleSmall, color = AnProxTheme.colors.textPrimary)
                    Text(stringResource(R.string.settings_refresh_description), style = MaterialTheme.typography.bodySmall, color = AnProxTheme.colors.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    SegmentedPicker(
                        REFRESH_OPTIONS,
                        REFRESH_OPTIONS.firstOrNull { it == settings.refreshIntervalMillis } ?: 5_000L,
                        { refreshLabels.getValue(it) },
                        viewModel::setRefreshInterval,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_security)) }
            item { AppLockCard(settings, viewModel) }
        }
    }
}

/**
 * App lock settings (questionnaire Q11). Turning the lock, or the extra check before dangerous
 * actions, on or off asks for the fingerprint/face/screen lock first, so someone holding the
 * unlocked phone can't quietly switch the protection off.
 */
@Composable
private fun AppLockCard(settings: AppSettings, viewModel: SettingsViewModel) {
    val c = AnProxTheme.colors
    val authenticator = LocalAuthenticator.current
    var problem by remember { mutableStateOf<Int?>(null) }
    val promptOn = stringResource(R.string.lock_prompt_enable)
    val promptOff = stringResource(R.string.lock_prompt_disable)
    val promptChecksOff = stringResource(R.string.lock_prompt_checks_off)
    val lockAfterLabels = mapOf(
        0L to stringResource(R.string.settings_lock_immediately),
        60_000L to stringResource(R.string.settings_lock_minutes, 1),
        300_000L to stringResource(R.string.settings_lock_minutes, 5),
    )

    /** Runs [action] once the user has authenticated, or explains why that isn't possible. */
    fun verified(title: String, allowWithoutLock: Boolean, action: () -> Unit) {
        problem = null
        val availability = authenticator?.availability() ?: AuthAvailability.UNAVAILABLE
        if (authenticator != null && availability == AuthAvailability.READY) {
            authenticator.authenticate(title, null) { ok -> if (ok) action() }
        } else if (allowWithoutLock) {
            // The phone's own screen lock was removed: switching protection off must still work.
            action()
        } else {
            problem = if (availability == AuthAvailability.NO_SCREEN_LOCK) R.string.lock_no_screen_lock else R.string.lock_unavailable
        }
    }

    GlassCard {
        SwitchRow(
            title = stringResource(R.string.settings_app_lock),
            description = stringResource(R.string.settings_app_lock_description),
            checked = settings.appLock,
            onCheckedChange = { enable ->
                verified(if (enable) promptOn else promptOff, allowWithoutLock = !enable) { viewModel.setAppLock(enable) }
            },
        )
        problem?.let {
            Spacer(Modifier.height(8.dp))
            ErrorBanner(stringResource(it), tone = Tone.WARNING)
        }
        if (settings.appLock) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_lock_after), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Spacer(Modifier.height(8.dp))
            SegmentedPicker(
                LOCK_AFTER_OPTIONS,
                LOCK_AFTER_OPTIONS.firstOrNull { it == settings.lockAfterMillis } ?: 60_000L,
                { lockAfterLabels.getValue(it) },
                viewModel::setLockAfter,
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = stringResource(R.string.settings_confirm_dangerous),
                description = stringResource(R.string.settings_confirm_dangerous_description),
                checked = settings.confirmDangerous,
                onCheckedChange = { enable ->
                    if (enable) viewModel.setConfirmDangerous(true)
                    else verified(promptChecksOff, allowWithoutLock = true) { viewModel.setConfirmDangerous(false) }
                },
            )
        }
    }
}
