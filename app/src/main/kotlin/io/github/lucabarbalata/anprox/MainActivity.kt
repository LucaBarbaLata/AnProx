package io.github.lucabarbalata.anprox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassBackground
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnProxTheme {
                GlassBackground { Text("AnProx") }
            }
        }
    }
}
