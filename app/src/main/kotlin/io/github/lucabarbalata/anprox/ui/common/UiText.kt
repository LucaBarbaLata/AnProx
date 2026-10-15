package io.github.lucabarbalata.anprox.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.ssh.SshException
import io.github.lucabarbalata.anprox.data.ssh.SshKeyException
import io.github.lucabarbalata.anprox.network.ApiException

/**
 * Text produced by a ViewModel. ViewModels don't hold a Context, so they hand the UI a string
 * *resource* (translatable, questionnaire Q16) or, for messages that come from the server, the
 * raw text.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Raw -> value
}

/** For code outside composition (callbacks, coroutines started from the UI). */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.toTypedArray())
    is UiText.Raw -> value
}

/** A precise, actionable message for every [ApiException] and SSH failure. */
fun Throwable.toUiText(): UiText = when (this) {
    is ApiException.Unreachable -> UiText.res(
        R.string.error_unreachable,
        attempts.joinToString("\n") { "• ${it.url} — ${it.reason}" },
    )
    is ApiException.UntrustedCertificate -> UiText.res(R.string.error_untrusted_certificate)
    is ApiException.Unauthorized -> UiText.res(R.string.error_unauthorized)
    is ApiException.Forbidden -> UiText.res(R.string.error_forbidden, message.orEmpty())
    is ApiException.NotFound -> UiText.res(R.string.error_not_found, message.orEmpty())
    is ApiException.Validation -> UiText.Raw(
        (listOf(message.orEmpty()) + fieldErrors.map { (k, v) -> "$k: $v" }).joinToString("\n"),
    )
    is ApiException.Server -> UiText.res(R.string.error_server, code, message.orEmpty())
    is ApiException.Protocol -> UiText.res(R.string.error_protocol)
    is SshException.AuthFailed -> UiText.res(R.string.ssh_error_auth)
    is SshException.HostKeyRejected -> UiText.res(R.string.ssh_error_host_key)
    is SshException.KeyMissing -> UiText.res(R.string.ssh_error_key_missing)
    is SshException.Unreachable -> UiText.res(R.string.ssh_error_unreachable, message.orEmpty())
    is SshException.Failed -> UiText.res(R.string.ssh_error_failed, message.orEmpty())
    is SshKeyException -> UiText.res(
        when (reason) {
            SshKeyException.Reason.UNREADABLE -> R.string.ssh_key_error_unreadable
            SshKeyException.Reason.PASSPHRASE_NEEDED -> R.string.ssh_key_error_passphrase_needed
            SshKeyException.Reason.WRONG_PASSPHRASE -> R.string.ssh_key_error_wrong_passphrase
        },
    )
    else -> UiText.Raw(message ?: this::class.simpleName.orEmpty())
}
