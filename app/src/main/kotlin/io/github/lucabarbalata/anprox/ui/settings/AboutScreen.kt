package io.github.lucabarbalata.anprox.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.BuildConfig
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import io.github.lucabarbalata.anprox.ui.common.contentPadding

private const val SOURCE_URL = "https://github.com/LucaBarbaLata/AnProx"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = AnProxTheme.colors
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    AnProxScaffold(title = stringResource(R.string.about_title), onBack = onBack) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Brush.linearGradient(listOf(c.accent, c.accentAlt))),
                        contentAlignment = Alignment.Center,
                    ) { Text("A", style = MaterialTheme.typography.headlineMedium, color = c.onAccent) }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    if (BuildConfig.VERSION_NAME.contains('-')) {
                        Spacer(Modifier.height(8.dp))
                        StatusPill(stringResource(R.string.about_prerelease), Tone.WARNING)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.about_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.BugReport, stringResource(R.string.about_report_bug), stringResource(R.string.about_report_bug_body)) { open("$SOURCE_URL/issues/new/choose") }
                    MenuRow(Icons.Outlined.Code, stringResource(R.string.about_source), SOURCE_URL.removePrefix("https://")) { open(SOURCE_URL) }
                    MenuRow(Icons.Outlined.Description, stringResource(R.string.about_license), stringResource(R.string.about_license_body)) { open("$SOURCE_URL/blob/HEAD/LICENSE") }
                    MenuRow(Icons.Outlined.Favorite, stringResource(R.string.about_donate), stringResource(R.string.about_donate_body)) { open("$SOURCE_URL#support-the-project") }
                }
            }
            item {
                GlassCard {
                    Text(stringResource(R.string.about_privacy_title), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text(stringResource(R.string.about_privacy_body), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.about_disclaimer), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
            }
        }
    }
}
