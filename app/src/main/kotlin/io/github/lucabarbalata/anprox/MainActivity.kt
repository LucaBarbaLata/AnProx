package io.github.lucabarbalata.anprox

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassBackground
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.ThemeMode
import io.github.lucabarbalata.anprox.data.security.AppLock
import io.github.lucabarbalata.anprox.security.AuthAvailability
import io.github.lucabarbalata.anprox.security.BiometricAuthenticator
import io.github.lucabarbalata.anprox.security.DangerousActionGuard
import io.github.lucabarbalata.anprox.security.LocalAuthenticator
import io.github.lucabarbalata.anprox.security.LocalDangerousActionGuard
import io.github.lucabarbalata.anprox.security.LockScreen
import io.github.lucabarbalata.anprox.storage.AppSettings
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.ui.AnProxApp
import io.github.lucabarbalata.anprox.ui.navigation.AppLinks
import io.github.lucabarbalata.anprox.ui.navigation.Shortcuts
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The single activity. Everything else is Compose (see docs/architecture.md). It's a
 * FragmentActivity because the system's biometric prompt needs one (app lock, ADR 0013).
 */
class MainActivity : FragmentActivity() {

    private val settingsRepository: SettingsRepository by inject()
    private val appLock: AppLock by inject()
    private val links: AppLinks by inject()

    @Volatile
    private var settingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the splash screen until we know the theme and whether the app starts locked.
        installSplashScreen().setKeepOnScreenCondition { !settingsLoaded || !appLock.ready.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a rotation or process restore the launch intent's link was already opened.
        if (savedInstanceState == null) {
            links.open(intent)
            Shortcuts.publish(this)
        }
        val authenticator = BiometricAuthenticator(this, appLock)

        setContent {
            val settings: AppSettings? by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            settingsLoaded = true
            val locked by appLock.locked.collectAsStateWithLifecycle()

            val dark = when (s.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Keep status/navigation bar icons readable when the theme differs from the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }

            // Re-check before actions that can't be undone, when the user asked for it (Q11).
            val guard = remember(s.appLock, s.confirmDangerous) {
                DangerousActionGuard { reason, onConfirmed ->
                    if (s.appLock && s.confirmDangerous && authenticator.availability() == AuthAvailability.READY) {
                        authenticator.authenticate(reason, null) { ok -> if (ok) onConfirmed() }
                    } else {
                        onConfirmed()
                    }
                }
            }

            AnProxTheme(mode = s.themeMode, pureBlack = s.pureBlack) {
                CompositionLocalProvider(LocalAuthenticator provides authenticator, LocalDangerousActionGuard provides guard) {
                    Box(Modifier.fillMaxSize()) {
                        // While locked the app stays composed (navigation survives) but TalkBack can't reach it.
                        GlassBackground(if (locked) Modifier.clearAndSetSemantics {} else Modifier) { AnProxApp() }
                        if (locked) {
                            LockScreen(
                                authenticator = authenticator,
                                onUnlocked = appLock::unlock,
                                onUnavailable = {
                                    // The phone's screen lock was removed, so the app lock can't work: turn it off.
                                    appLock.unlock()
                                    lifecycleScope.launch { settingsRepository.setAppLock(false) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /** A notification, shortcut or widget tapped while the app was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        links.open(intent)
    }
}
