package io.github.lucabarbalata.anprox.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.MonoStyle
import org.koin.compose.koinInject

/** Shown once after a crash: lets the user read the report and share it, or discard it. */
@Composable
fun CrashReportDialog(reporter: CrashReporter = koinInject()) {
    var report by remember { mutableStateOf(reporter.pendingReport()) }
    val text = report ?: return
    val context = LocalContext.current
    val c = AnProxTheme.colors
    val subject = stringResource(R.string.crash_share_subject)
    val chooserTitle = stringResource(R.string.crash_share)

    fun close() {
        reporter.clear()
        report = null
    }

    AlertDialog(
        onDismissRequest = ::close,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column {
                Text(stringResource(R.string.crash_body), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        text,
                        style = MonoStyle.copy(color = c.textTertiary),
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, chooserTitle)) }
                    close()
                },
            ) { Text(stringResource(R.string.crash_share)) }
        },
        dismissButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.crash_discard)) } },
    )
}
