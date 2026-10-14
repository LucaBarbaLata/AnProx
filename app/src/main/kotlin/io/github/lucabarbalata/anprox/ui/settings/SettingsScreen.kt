package io.github.lucabarbalata.anprox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.ThemeMode
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel

private val REFRESH_OPTIONS = listOf(2_000L, 5_000L, 10_000L, 30_000L)

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
            item {
                GlassCard {
                    Text(stringResource(R.string.settings_app_lock), style = MaterialTheme.typography.titleSmall, color = AnProxTheme.colors.textPrimary)
                    Text(stringResource(R.string.settings_app_lock_soon), style = MaterialTheme.typography.bodySmall, color = AnProxTheme.colors.textSecondary)
                }
            }
        }
    }
}
