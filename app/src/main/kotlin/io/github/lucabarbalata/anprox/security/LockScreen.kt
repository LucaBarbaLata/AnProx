package io.github.lucabarbalata.anprox.security

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassBackground

/**
 * Covers the whole app while it's locked (questionnaire Q11). Opens the system prompt right
 * away; the button opens it again after a cancel. Back leaves the app.
 *
 * It is a full-screen dialog on purpose: dialogs and menus live in their own windows above the
 * activity, so a plain overlay would leave an open "Roll back?" dialog usable on top of the lock.
 * A newer dialog window covers them all. The app underneath stays composed, so navigation and
 * scroll positions survive the lock.
 *
 * @param onUnavailable the phone can no longer verify the user (its screen lock was removed).
 */
@Composable
fun LockScreen(authenticator: Authenticator, onUnlocked: () -> Unit, onUnavailable: () -> Unit) {
    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)
    var notVerified by remember { mutableStateOf(false) }
    val unlock: () -> Unit = {
        if (authenticator.availability() == AuthAvailability.READY) {
            authenticator.authenticate(title, subtitle) { ok -> if (ok) onUnlocked() else notVerified = true }
        } else {
            onUnavailable()
        }
    }
    LaunchedEffect(Unit) { unlock() }

    val activity = LocalContext.current as? Activity
    Dialog(
        onDismissRequest = { activity?.moveTaskToBack(true) }, // Back leaves the app instead of unlocking
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        GlassBackground(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.lock_title),
                    body = stringResource(if (notVerified) R.string.lock_body_retry else R.string.lock_body),
                    action = { AnProxButton(text = stringResource(R.string.lock_unlock), onClick = unlock, icon = Icons.Outlined.Fingerprint) },
                )
            }
        }
    }
}
