package io.github.lucabarbalata.anprox.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.lucabarbalata.anprox.data.security.AppLock

enum class AuthAvailability {
    READY,

    /** The phone has no screen lock (PIN, pattern, password) set up. */
    NO_SCREEN_LOCK,

    /** Anything else the system reports: no hardware, a security update needed, … */
    UNAVAILABLE,
}

/** Asks Android to confirm it's the phone's owner: fingerprint, face, or the screen lock itself. */
interface Authenticator {
    fun availability(): AuthAvailability

    /** Shows the system prompt. [onResult] gets `true` on success, `false` if cancelled or failed. */
    fun authenticate(title: String, subtitle: String?, onResult: (Boolean) -> Unit)
}

/**
 * [Authenticator] backed by BiometricPrompt (questionnaire Q11, ADR 0013). Accepts any biometric
 * the phone offers or the screen lock PIN/pattern/password, so there's no extra AnProx PIN to
 * remember or leak. Create it in `onCreate`, as BiometricPrompt requires.
 */
class BiometricAuthenticator(private val activity: FragmentActivity, private val appLock: AppLock) : Authenticator {
    private var pending: ((Boolean) -> Unit)? = null

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(true)

            // Cancelled, too many attempts, … A single wrong finger only calls onAuthenticationFailed
            // and the prompt stays open.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(false)
        },
    )

    private fun finish(success: Boolean) {
        appLock.authenticating = false
        val callback = pending
        pending = null
        callback?.invoke(success)
    }

    override fun availability(): AuthAvailability = when (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.READY
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NO_SCREEN_LOCK
        else -> AuthAvailability.UNAVAILABLE
    }

    override fun authenticate(title: String, subtitle: String?, onResult: (Boolean) -> Unit) {
        if (pending != null) return // a prompt is already showing
        pending = onResult
        appLock.authenticating = true
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    private companion object {
        /** Works on every Android version from 8.0 on; DEVICE_CREDENTIAL means no "Cancel" button text. */
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}

/**
 * Re-checks the user's identity before actions that can't be undone (questionnaire Q11). Does
 * nothing extra unless the app lock and "Check again before dangerous actions" are on.
 */
fun interface DangerousActionGuard {
    fun confirm(reason: String, onConfirmed: () -> Unit)
}

/** Provided by MainActivity; `null` in previews. */
val LocalAuthenticator = staticCompositionLocalOf<Authenticator?> { null }

val LocalDangerousActionGuard = staticCompositionLocalOf { DangerousActionGuard { _, onConfirmed -> onConfirmed() } }
