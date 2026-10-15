package io.github.lucabarbalata.anprox.ui.navigation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.lucabarbalata.anprox.MainActivity
import io.github.lucabarbalata.anprox.model.link.AppLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [AppLink] a notification, shortcut or widget asked to open, until the navigation graph
 * has opened it. Links arrive in explicit intents for [MainActivity]; there is no intent filter
 * for `anprox://`, so browsers can't open them. A link only ever opens a screen: every action
 * on it still needs the user's tap (and confirmation where the screen asks for one).
 */
class AppLinks {
    private val pending = MutableStateFlow<AppLink?>(null)
    val next: StateFlow<AppLink?> = pending.asStateFlow()

    /** Remembers the link in [intent], if it has one. */
    fun open(intent: Intent?) {
        AppLink.parse(intent?.dataString)?.let { pending.value = it }
    }

    fun opened(link: AppLink) {
        pending.compareAndSet(link, null)
    }

    companion object {
        /** An intent that brings the app to the front and opens [link]. */
        fun intent(context: Context, link: AppLink): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(link.toUri()), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** The same for a notification or widget. The link is part of the intent's identity. */
        fun pendingIntent(context: Context, link: AppLink): PendingIntent =
            PendingIntent.getActivity(context, 0, intent(context, link), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
