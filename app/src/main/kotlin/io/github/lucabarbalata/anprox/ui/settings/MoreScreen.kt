package io.github.lucabarbalata.anprox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import io.github.lucabarbalata.anprox.ui.common.contentPadding

/** The "More" tab: everything that isn't a live view. */
@Composable
fun MoreScreen(onServers: () -> Unit, onSettings: () -> Unit, onAlerts: () -> Unit, onAbout: () -> Unit) {
    AnProxScaffold(title = stringResource(R.string.more_title)) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Dns, stringResource(R.string.servers_title), null, onServers)
                    MenuRow(Icons.Outlined.NotificationsActive, stringResource(R.string.alerts_title), null, onAlerts)
                    MenuRow(Icons.Outlined.Palette, stringResource(R.string.settings_title), null, onSettings)
                    MenuRow(Icons.Outlined.Info, stringResource(R.string.about_title), null, onAbout)
                }
            }
        }
    }
}
