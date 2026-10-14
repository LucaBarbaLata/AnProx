package io.github.lucabarbalata.anprox

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassBackground
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.ThemeMode
import io.github.lucabarbalata.anprox.storage.AppSettings
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.ui.AnProxApp
import org.koin.android.ext.android.inject

/** The single activity. Everything else is Compose (see docs/architecture.md). */
class MainActivity : ComponentActivity() {

    private val settingsRepository: SettingsRepository by inject()

    @Volatile
    private var settingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !settingsLoaded }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings: AppSettings? by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            settingsLoaded = true

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

            AnProxTheme(mode = s.themeMode, pureBlack = s.pureBlack) {
                GlassBackground { AnProxApp() }
            }
        }
    }
}
