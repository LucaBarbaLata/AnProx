package io.github.lucabarbalata.anprox.widget

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.model.alerts.AlertRun
import io.github.lucabarbalata.anprox.model.alerts.ServerSummary
import io.github.lucabarbalata.anprox.model.link.AppLink
import io.github.lucabarbalata.anprox.storage.AlertStore
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.ui.navigation.AppLinks
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// Midnight Glass colours (docs/design-system.md); widgets can't read the app's Compose theme.
private val BACKGROUND = Color(0xF00B0F1A)
private val TEXT = Color(0xFFF6F8FC)
private val MUTED = Color(0xFF9AA4B8)
private val OK = Color(0xFF34D399)
private val WARN = Color(0xFFFBBF24)

private const val MAX_SERVERS = 4

/**
 * Home-screen widget (questionnaire Q28): each server as the last background check saw it.
 * It is redrawn after every check and when alerts are switched on or off; tapping it opens the
 * dashboard.
 */
class StatusWidget : GlanceAppWidget(), KoinComponent {
    private val store: AlertStore by inject()
    private val settings: SettingsRepository by inject()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val run = store.lastRun.first()
        val enabled = settings.settings.first().alerts.enabled
        provideContent { Content(run, enabled) }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { StatusWidget().updateAll(context) }
        }
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}

@Composable
private fun Content(run: AlertRun?, enabled: Boolean) {
    val context = LocalContext.current
    val small = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp)
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(BACKGROUND)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(AppLinks.intent(context, AppLink.Dashboard))),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.widget_title),
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = ColorProvider(TEXT), fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            if (run != null) Text(DateUtils.formatDateTime(context, run.atMillis, DateUtils.FORMAT_SHOW_TIME), style = small)
        }
        Spacer(GlanceModifier.height(8.dp))
        if (run == null || run.servers.isEmpty()) {
            Text(context.getString(R.string.widget_empty), style = small)
        } else {
            run.servers.take(MAX_SERVERS).forEach { server ->
                ServerLine(server)
                Spacer(GlanceModifier.height(6.dp))
            }
        }
        if (!enabled && run != null) {
            Spacer(GlanceModifier.height(4.dp))
            Text(context.getString(R.string.widget_alerts_off), style = TextStyle(color = ColorProvider(WARN), fontSize = 12.sp))
        }
    }
}

@Composable
private fun ServerLine(s: ServerSummary) {
    val context = LocalContext.current
    val dot = when {
        !s.reachable -> MUTED
        s.problems > 0 -> WARN
        else -> OK
    }
    val detail = when {
        !s.reachable -> context.getString(R.string.widget_unreachable)
        s.backupServer -> context.getString(R.string.widget_pbs, s.storages)
        else -> context.getString(R.string.widget_pve, s.nodesOnline, s.nodes, s.guestsRunning, s.guests)
    }
    val problems = if (s.reachable && s.problems > 0) " · " + context.getString(R.string.widget_problems, s.problems) else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", style = TextStyle(color = ColorProvider(dot), fontSize = 12.sp))
        Spacer(GlanceModifier.width(8.dp))
        Column {
            Text(s.name, style = TextStyle(color = ColorProvider(TEXT), fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(detail + problems, style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp), maxLines = 1)
        }
    }
}
