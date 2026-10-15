package io.github.lucabarbalata.anprox.ui.navigation

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.model.link.AppLink

/**
 * App shortcuts (questionnaire Q28): long-press the launcher icon for Resources, Tasks or
 * Alerts. Published from the activity, while the app is in the foreground, because Android
 * rate-limits shortcut updates from the background.
 */
object Shortcuts {
    private class Entry(val id: String, val short: Int, val long: Int, val icon: Int, val link: AppLink)

    private val entries = listOf(
        Entry("resources", R.string.shortcut_resources, R.string.shortcut_resources_long, R.drawable.ic_shortcut_resources, AppLink.Resources),
        Entry("tasks", R.string.shortcut_tasks, R.string.shortcut_tasks_long, R.drawable.ic_shortcut_tasks, AppLink.Tasks),
        Entry("alerts", R.string.shortcut_alerts, R.string.shortcut_alerts_long, R.drawable.ic_shortcut_alerts, AppLink.Alerts),
    )

    fun publish(context: Context) {
        val shortcuts = entries.mapIndexed { rank, e ->
            ShortcutInfoCompat.Builder(context, e.id)
                .setShortLabel(context.getString(e.short))
                .setLongLabel(context.getString(e.long))
                .setIcon(IconCompat.createWithResource(context, e.icon))
                .setIntent(AppLinks.intent(context, e.link))
                .setRank(rank)
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
}
