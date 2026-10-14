package io.github.lucabarbalata.anprox.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.model.pve.Upid

/** A finished task the user may want to open. */
data class TaskResult(val message: UiText, val upid: String?, val ok: Boolean)

/**
 * Shows [result] once as a snackbar with a **View log** action (when there is a task to open),
 * then calls [onConsumed]. [fallbackNode] is used if the UPID can't be parsed.
 */
@Composable
fun TaskResultSnackbar(
    result: TaskResult?,
    snackbar: SnackbarHostState,
    serverId: String,
    fallbackNode: String,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onConsumed: () -> Unit,
) {
    val text = result?.message?.asString()
    val viewLog = stringResource(R.string.action_view_log)
    LaunchedEffect(result) {
        if (result != null && text != null) {
            val action = snackbar.showSnackbar(
                message = text,
                actionLabel = if (result.upid != null) viewLog else null,
                duration = SnackbarDuration.Long,
            )
            if (action == SnackbarResult.ActionPerformed && result.upid != null) {
                onTask(serverId, Upid.parse(result.upid)?.node ?: fallbackNode, result.upid)
            }
            onConsumed()
        }
    }
}
