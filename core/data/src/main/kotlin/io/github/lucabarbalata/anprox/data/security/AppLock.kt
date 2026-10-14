package io.github.lucabarbalata.anprox.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the optional app lock asks for the user's fingerprint, face or screen lock again
 * (questionnaire Q11). Pure state, no Android: the app feeds it settings and lifecycle events
 * and shows a lock screen while [locked] is true.
 *
 * - With the lock enabled, a fresh start of the app is locked.
 * - Leaving the app for at least `lockAfterMillis` locks it (0 = every time).
 * - Time spent in the system's authentication prompt doesn't count as leaving: on older Android
 *   versions the PIN screen is a separate app, and returning from it must not lock again.
 * - Turning the lock on doesn't lock right away (the user just proved who they are); turning
 *   it off unlocks.
 */
class AppLock(private val clockMillis: () -> Long = System::currentTimeMillis) {

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** False until the settings have been applied once; the splash screen waits for it. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    var enabled: Boolean = false
        private set
    private var lockAfterMillis: Long = DEFAULT_LOCK_AFTER_MILLIS
    private var backgroundSince: Long? = null

    /** Set while the system authentication prompt is showing. */
    @Volatile
    var authenticating: Boolean = false

    /** Applies the user's settings. The first call (at app start) locks if the lock is enabled. */
    @Synchronized
    fun configure(enabled: Boolean, lockAfterMillis: Long) {
        val firstTime = !_ready.value
        this.enabled = enabled
        this.lockAfterMillis = lockAfterMillis.coerceAtLeast(0)
        when {
            !enabled -> _locked.value = false
            firstTime -> _locked.value = true
        }
        _ready.value = true
    }

    /** The whole app went to the background. */
    @Synchronized
    fun onBackground() {
        if (!authenticating) backgroundSince = clockMillis()
    }

    /** The app came back to the foreground. */
    @Synchronized
    fun onForeground() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (enabled && !authenticating && clockMillis() - since >= lockAfterMillis) _locked.value = true
    }

    /** The user proved who they are. */
    @Synchronized
    fun unlock() {
        _locked.value = false
    }

    companion object {
        const val DEFAULT_LOCK_AFTER_MILLIS = 60_000L
    }
}
