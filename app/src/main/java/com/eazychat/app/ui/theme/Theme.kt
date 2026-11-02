package com.eazychat.app.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Modern color scheme for P2P-Chat
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6366F1),        // Indigo
    onPrimary = Color.White,
    secondary = Color(0xFFFF6B6B),      // Coral
    onSecondary = Color.Black,
    background = Color(0xFF121212),      // Dark background
    onBackground = Color(0xFFE0E0E0),   // Light text
    surface = Color(0xFF1E1E1E),        // Slightly lighter surface
    onSurface = Color(0xFFE0E0E0),      // Light text on surface
    error = Color(0xFFF44336),          // Red for errors
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4F46E5),        // Darker indigo
    onPrimary = Color.White,
    secondary = Color(0xFFEF4444),      // Red coral
    onSecondary = Color.White,
    background = Color(0xFFF9FAFB),      // Light background
    onBackground = Color(0xFF1F2937),   // Dark text
    surface = Color(0xFFFFFFFF),        // White surface
    onSurface = Color(0xFF1F2937),      // Dark text on surface
    error = Color(0xFFDC2626),          // Dark red for errors
    onError = Color.White
)

@Composable
fun P2PChatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
