package com.llama.asciicam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.llama.asciicam.ui.MainScreen

/**
 * Single-activity app. All real work happens in [MainScreen] /
 * [com.llama.asciicam.ui.AsciiViewModel] / the `pipeline` package.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AsciiCamTheme {
                MainScreen()
            }
        }
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5B8CFF),
    background = Color(0xFF0B0B0F),
    surface = Color(0xFF0B0B0F),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF3A62D8),
    background = Color(0xFFF6F6F8),
    surface = Color(0xFFFFFFFF),
)

@Composable
private fun AsciiCamTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
