package io.github.lucabarbalata.anprox.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import io.github.lucabarbalata.anprox.model.favourites.FavouriteState
import io.github.lucabarbalata.anprox.model.favourites.FavouriteStatus
import io.github.lucabarbalata.anprox.model.favourites.FavouritesSnapshot
import io.github.lucabarbalata.anprox.model.link.AppLink
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.storage.WidgetConfirm
import io.github.lucabarbalata.anprox.storage.WidgetStore
import io.github.lucabarbalata.anprox.ui.navigation.AppLinks
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Everything the favourites widget draws. */
private data class FavouritesView(
    val snapshot: FavouritesSnapshot?,
    val starred: Set<String>,
    val locked: Boolean,
    val confirm: WidgetConfirm?,
    val busy: Set<String>,
    val error: String?,
) {
    /** Favourites unstarred since the last refresh disappear at once. */
    val guests: List<FavouriteStatus> get() = snapshot?.guests.orEmpty().filter { it.key in starred }

    /** Starred since the last refresh: a refresh is on its way. */
    val waiting: Boolean get() = starred.any { key -> snapshot?.guests.orEmpty().none { it.key == key } }
}

/**
 * Home-screen widget with the favourite guests (questionnaire Q28): each one's state and a
 * button to start, resume or shut it down. Shutting down takes a second tap within a few
 * seconds (Q12). With the app lock on (Q11), the button opens the guest in the app instead, so
 * nothing runs without unlocking.
 */
class FavouritesWidget : GlanceAppWidget(), KoinComponent {
    private val store: WidgetStore by inject()
    private val settings: SettingsRepository by inject()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val view = combine(
            store.favourites,
            settings.settings.map { it.favourites to it.appLock },
            store.confirm,
            store.busy,
            store.error,
        ) { snapshot, (starred, locked), confirm, busy, error -> FavouritesView(snapshot, starred, locked, confirm, busy, error) }
        val first = view.first()
        provideContent {
            val current by view.collectAsState(first)
            Content(current)
        }
    }

    companion object {
        suspend fun redraw(context: Context) {
            runCatching { FavouritesWidget().updateAll(context) }
        }
    }
}

class FavouritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavouritesWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Added to the home screen, or the launcher asked. Android also sends this when the
        // app's components change, which finishing work does, so it is rate-limited.
        val prefs = context.getSharedPreferences(THROTTLE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(THROTTLE_KEY, 0) !in 0..THROTTLE_MILLIS) {
            prefs.edit { putLong(THROTTLE_KEY, now) }
            FavouritesWorker.enqueueRefresh(context)
        }
    }

    private companion object {
        const val THROTTLE_PREFS = "favourites_widget"
        const val THROTTLE_KEY = "last_update_refresh"
        const val THROTTLE_MILLIS = 2 * 60_000L
    }
}

private val GUEST_KEY = ActionParameters.Key<String>("guest")
private val ACTION_KEY = ActionParameters.Key<String>("action")

/** A tap on a favourite's button: the first tap of a shutdown only asks for the second. */
class FavouritePowerCallback : ActionCallback, KoinComponent {
    private val store: WidgetStore by inject()
    private val settings: SettingsRepository by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val key = parameters[GUEST_KEY] ?: return
        val action = PowerAction.entries.firstOrNull { it.name == parameters[ACTION_KEY] } ?: return
        // The lock was switched on since the widget was drawn: draw it again with the app links.
        if (!settings.settings.first().appLock) {
            val now = System.currentTimeMillis()
            val confirm = store.confirm.first()
            val confirmed = confirm != null && confirm.key == key && confirm.action == action.name && confirm.isFresh(now)
            if (action.danger != DangerLevel.SAFE && !confirmed) {
                store.setConfirm(WidgetConfirm(key, action.name, now))
                FavouritesWorker.expireConfirm(context)
            } else {
                store.setConfirm(null)
                store.setBusy(key, true)
                FavouritesWorker.power(context, key, action)
            }
        }
        FavouritesWidget.redraw(context)
    }
}

class FavouritesRefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        FavouritesWorker.enqueueRefresh(context)
    }
}

@Composable
private fun Content(view: FavouritesView) {
    val context = LocalContext.current
    val small = TextStyle(color = ColorProvider(WidgetColors.MUTED), fontSize = 12.sp)
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.BACKGROUND)
            .cornerRadius(20.dp)
            .padding(14.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.widget_favourites_title),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(AppLinks.intent(context, AppLink.Dashboard))),
                style = TextStyle(color = ColorProvider(WidgetColors.TEXT), fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            view.snapshot?.let { Text(DateUtils.formatDateTime(context, it.atMillis, DateUtils.FORMAT_SHOW_TIME), style = small) }
            Spacer(GlanceModifier.width(10.dp))
            Text(
                "↻",
                modifier = GlanceModifier.clickable(actionRunCallback<FavouritesRefreshCallback>()),
                style = TextStyle(color = ColorProvider(WidgetColors.ACCENT), fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        view.error?.let {
            Text(it, style = TextStyle(color = ColorProvider(WidgetColors.ERROR), fontSize = 12.sp), maxLines = 2)
            Spacer(GlanceModifier.height(4.dp))
        }
        when {
            view.starred.isEmpty() -> Text(context.getString(R.string.widget_favourites_empty), style = small)
            view.guests.isEmpty() -> Text(context.getString(R.string.widget_favourites_loading), style = small)
            else -> {
                if (view.waiting) Text(context.getString(R.string.widget_favourites_loading), style = small)
                LazyColumn {
                    items(view.guests, itemId = { it.key.hashCode().toLong() }) { guest ->
                        Column {
                            GuestLine(guest, view)
                            Spacer(GlanceModifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuestLine(g: FavouriteStatus, view: FavouritesView) {
    val context = LocalContext.current
    val dot = when (g.state) {
        FavouriteState.RUNNING -> WidgetColors.OK
        FavouriteState.PAUSED -> WidgetColors.WARN
        FavouriteState.GONE, FavouriteState.SIGNED_OUT -> WidgetColors.ERROR
        else -> WidgetColors.MUTED
    }
    val state = context.getString(
        when (g.state) {
            FavouriteState.RUNNING -> R.string.status_running
            FavouriteState.STOPPED -> R.string.status_stopped
            FavouriteState.PAUSED -> R.string.status_paused
            FavouriteState.GONE -> R.string.widget_favourites_gone
            FavouriteState.UNREACHABLE -> R.string.widget_unreachable
            FavouriteState.SIGNED_OUT -> R.string.widget_favourites_signed_out
        },
    )
    val type = GuestType.fromApi(g.type)?.label ?: g.type
    val detail = listOfNotNull("$type ${g.vmid}", g.node, g.serverName, state, g.lock).joinToString(" · ")
    val link = g.node?.let { AppLink.Guest(g.serverId, it, g.type, g.vmid) } ?: AppLink.Dashboard
    val open = actionStartActivity(AppLinks.intent(context, link))
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("●", style = TextStyle(color = ColorProvider(dot), fontSize = 12.sp))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight().clickable(open)) {
            Text(g.title, style = TextStyle(color = ColorProvider(WidgetColors.TEXT), fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(detail, style = TextStyle(color = ColorProvider(WidgetColors.MUTED), fontSize = 12.sp), maxLines = 1)
        }
        val action = g.action
        when {
            g.key in view.busy -> Text("…", modifier = GlanceModifier.padding(horizontal = 12.dp), style = TextStyle(color = ColorProvider(WidgetColors.MUTED), fontSize = 14.sp))
            action != null -> {
                val confirming = view.confirm?.let { it.key == g.key && it.action == action.name && it.isFresh(System.currentTimeMillis()) } == true
                val label = context.getString(if (confirming) R.string.widget_favourites_confirm else action.label())
                val click = if (view.locked) open else actionRunCallback<FavouritePowerCallback>(actionParametersOf(GUEST_KEY to g.key, ACTION_KEY to action.name))
                Text(
                    label,
                    modifier = GlanceModifier
                        .background(if (confirming) WidgetColors.WARN else WidgetColors.BUTTON)
                        .cornerRadius(12.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable(click),
                    style = TextStyle(
                        color = ColorProvider(if (confirming) WidgetColors.BACKGROUND else WidgetColors.TEXT),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun PowerAction.label(): Int = when (this) {
    PowerAction.START -> R.string.power_start
    PowerAction.RESUME -> R.string.power_resume
    else -> R.string.power_shutdown
}
