package io.github.lucabarbalata.anprox.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.TaskObserver
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.link.AppLink
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.ui.navigation.AppLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Live task notifications (questionnaire Q28). Every task the app waits for is followed here,
 * on the app's own scope, so leaving the screen doesn't lose it:
 *
 * - still running after a few seconds → a silent, ongoing notification;
 * - ended while the app is in the foreground → the notification goes away (the screen said it);
 * - ended while the app is in the background → a notification says how it ended.
 *
 * Tapping either opens the task's log.
 */
class TaskTracker(
    context: Context,
    private val hub: ServerHub,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) : TaskObserver {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)
    private val following: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun createChannels() {
        fun channel(id: String, name: Int, description: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance).apply { this.description = context.getString(description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannels(
            listOf(
                channel(CHANNEL_RUNNING, R.string.tasks_channel_running, R.string.tasks_channel_running_description, NotificationManager.IMPORTANCE_LOW),
                channel(CHANNEL_DONE, R.string.tasks_channel_done, R.string.tasks_channel_done_description, NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    override fun started(serverId: String, node: String, upid: String) {
        if (!following.add(upid)) return
        scope.launch {
            try {
                if (settings.settings.first().taskNotifications) follow(serverId, node, upid)
            } finally {
                following.remove(upid)
            }
        }
    }

    private suspend fun follow(serverId: String, node: String, upid: String) {
        val parsed = Upid.parse(upid)
        val title = listOfNotNull(parsed?.let { TaskTypes.label(it.type) }, parsed?.id?.takeIf { it.isNotBlank() })
            .joinToString(" ").ifEmpty { upid }
        val server = hub.server(serverId)?.profile?.name
        val link = AppLink.Task(serverId, node, upid)
        val startedAt = System.currentTimeMillis()
        var shown = false
        var failures = 0
        while (true) {
            val status: TaskStatus? = try {
                hub.use(serverId) { it.pve { taskStatus(node, upid) } }.also { failures = 0 }
            } catch (_: ApiException) {
                failures++
                null
            }
            if (status != null && !status.isRunning) {
                finished(upid, title, server, link, status, shown)
                return
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (failures >= MAX_FAILURES || elapsed > MAX_FOLLOW_MILLIS) {
                // Lost track of it (no network, server gone): don't leave a stale "running" behind.
                if (shown) manager.cancel(upid, NOTIFICATION_ID)
                return
            }
            if (!shown && elapsed >= SHOW_AFTER_MILLIS) {
                shown = post(upid, CHANNEL_RUNNING, title, context.getString(R.string.task_notify_running), server, link, ongoing = true)
            }
            delay(POLL_MILLIS)
        }
    }

    private fun finished(upid: String, title: String, server: String?, link: AppLink, status: TaskStatus, shown: Boolean) {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground) {
            if (shown) manager.cancel(upid, NOTIFICATION_ID)
            return
        }
        val text = if (status.succeeded) context.getString(R.string.task_notify_ok, title) else context.getString(R.string.task_notify_failed, title)
        post(upid, CHANNEL_DONE, text, status.exitstatus, server, link, ongoing = false)
    }

    /** Returns whether the notification could be posted. */
    private fun post(upid: String, channel: String, title: String, text: String?, server: String?, link: AppLink, ongoing: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!manager.areNotificationsEnabled()) return false
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .apply { if (server != null) setSubText(server) }
            .setColor(ContextCompat.getColor(context, R.color.indigo_300))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply { if (ongoing) setProgress(0, 0, true) else setAutoCancel(true) }
            .setGroup(GROUP)
            .setContentIntent(AppLinks.pendingIntent(context, link))
            .build()
        manager.notify(upid, NOTIFICATION_ID, notification)
        return true
    }

    companion object {
        const val CHANNEL_RUNNING = "tasks_running"
        const val CHANNEL_DONE = "tasks_done"
        private const val GROUP = "tasks"
        private const val NOTIFICATION_ID = 2

        /** Quick tasks finish before a notification would be worth showing. */
        private const val SHOW_AFTER_MILLIS = 3_000L
        private const val POLL_MILLIS = 2_000L
        private const val MAX_FAILURES = 30
        private const val MAX_FOLLOW_MILLIS = 12 * 60 * 60 * 1000L
    }
}
