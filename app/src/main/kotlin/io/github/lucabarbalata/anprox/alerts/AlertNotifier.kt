package io.github.lucabarbalata.anprox.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.lucabarbalata.anprox.MainActivity
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.alerts.AlertCheck
import io.github.lucabarbalata.anprox.data.alerts.ServerAlert
import io.github.lucabarbalata.anprox.model.alerts.Alert.Kind
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.Upid

/**
 * Turns alerts into notifications, one per problem: the same problem again replaces its
 * notification instead of adding another. Each kind of alert has its own channel, so the user
 * can silence, say, resource limits in Android's settings and keep "node down" loud.
 */
class AlertNotifier(context: Context) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Creates the channels (safe to call repeatedly; Android keeps the user's choices). */
    fun createChannels() {
        fun channel(id: String, name: Int, description: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance).apply { this.description = context.getString(description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannels(
            listOf(
                channel(CHANNEL_DOWN, R.string.alerts_channel_down, R.string.alerts_channel_down_description, NotificationManager.IMPORTANCE_HIGH),
                channel(CHANNEL_FAILURES, R.string.alerts_channel_failures, R.string.alerts_channel_failures_description, NotificationManager.IMPORTANCE_DEFAULT),
                channel(CHANNEL_THRESHOLDS, R.string.alerts_channel_thresholds, R.string.alerts_channel_thresholds_description, NotificationManager.IMPORTANCE_DEFAULT),
                channel(CHANNEL_ACCOUNT, R.string.alerts_channel_account, R.string.alerts_channel_account_description, NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    /** Whether notifications can be shown at all (Android 13+ asks the user first). */
    fun allowed(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    fun show(check: AlertCheck) {
        if (!allowed()) return
        createChannels()
        check.alerts.forEach { show(it) }
        check.signInNeeded.forEach { server ->
            post(
                tag = "signin:$server",
                channel = CHANNEL_ACCOUNT,
                title = context.getString(R.string.notify_sign_in, server),
                text = context.getString(R.string.notify_sign_in_body),
                server = null,
            )
        }
    }

    private fun show(item: ServerAlert) {
        val a = item.alert
        val percent = a.percent ?: 0
        val (channel, title, text) = when (a.kind) {
            Kind.NODE_DOWN -> Triple(CHANNEL_DOWN, context.getString(R.string.notify_node_down, a.subject), null)
            Kind.QUORUM_LOST -> Triple(CHANNEL_DOWN, context.getString(R.string.notify_quorum_lost, item.serverName), context.getString(R.string.notify_quorum_lost_body))
            Kind.GUEST_STOPPED -> Triple(
                CHANNEL_DOWN,
                context.getString(R.string.notify_guest_stopped, a.subject, a.detail.orEmpty()),
                context.getString(R.string.notify_guest_stopped_body),
            )
            Kind.TASK_FAILED -> Triple(CHANNEL_FAILURES, context.getString(R.string.notify_task_failed, taskName(a.subject)), a.detail)
            Kind.BACKUP_FAILED -> Triple(CHANNEL_FAILURES, context.getString(R.string.notify_backup_failed, taskName(a.subject)), a.detail)
            Kind.CPU_HIGH -> Triple(CHANNEL_THRESHOLDS, context.getString(R.string.notify_cpu_high, percent, a.subject), null)
            Kind.MEMORY_HIGH -> Triple(CHANNEL_THRESHOLDS, context.getString(R.string.notify_memory_high, percent, a.subject), null)
            Kind.STORAGE_HIGH -> Triple(CHANNEL_THRESHOLDS, context.getString(R.string.notify_storage_high, a.subject, percent), null)
            Kind.CEPH_WARN -> Triple(CHANNEL_THRESHOLDS, context.getString(R.string.notify_ceph_warn), context.getString(R.string.notify_ceph_body))
            Kind.CEPH_ERR -> Triple(CHANNEL_DOWN, context.getString(R.string.notify_ceph_err), context.getString(R.string.notify_ceph_body))
        }
        post("${item.serverId}:${a.key}", channel, title, text, item.serverName)
    }

    /** "VM start 102" from a UPID; the raw UPID if it can't be read. */
    private fun taskName(upid: String): String {
        val parsed = Upid.parse(upid) ?: return upid
        return listOf(TaskTypes.label(parsed.type), parsed.id).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun post(tag: String, channel: String, title: String, text: String?, server: String?) {
        // Checked here again so lint can see the permission check next to notify().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)) }
            .apply { if (server != null) setSubText(server) }
            .setColor(ContextCompat.getColor(context, R.color.indigo_300))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(GROUP)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(tag, NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_DOWN = "alerts_down"
        const val CHANNEL_FAILURES = "alerts_failures"
        const val CHANNEL_THRESHOLDS = "alerts_thresholds"
        const val CHANNEL_ACCOUNT = "alerts_account"
        private const val GROUP = "alerts"

        /** Notifications are told apart by tag; the id is the same for all. */
        private const val NOTIFICATION_ID = 1
    }
}
