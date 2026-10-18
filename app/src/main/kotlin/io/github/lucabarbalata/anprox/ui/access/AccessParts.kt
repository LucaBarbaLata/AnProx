package io.github.lucabarbalata.anprox.ui.access

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.MonoStyle
import io.github.lucabarbalata.anprox.ui.common.PasswordField

// Small pieces shared by the access screens.

/** The signed-in user's own password, which Proxmox wants for changes to passwords and second factors. */
@Composable
internal fun YourPasswordField(value: String, onValueChange: (String) -> Unit) {
    PasswordField(value, onValueChange, stringResource(R.string.access_your_password), supportingText = stringResource(R.string.access_your_password_hint))
}

/** A value shown once (a token secret, recovery keys) in a mono box with a copy button. */
@Composable
internal fun SecretBox(text: String, onCopied: () -> Unit, copyText: String = text) {
    val c = AnProxTheme.colors
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.glassStrong)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(text, style = MonoStyle, color = c.textPrimary)
        }
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(copyText))
            onCopied()
        }) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy), tint = c.textSecondary) }
    }
}
