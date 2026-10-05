package dev.snapbudget.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val lightColors = lightColorScheme(
    primary = Color(0xFF365C45), onPrimary = Color.White,
    secondary = Color(0xFF526455), onSecondary = Color.White,
    background = Color(0xFFF7F8F5), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EBE4),
)
private val darkColors = darkColorScheme(
    primary = Color(0xFFA4D2B0), onPrimary = Color(0xFF163723),
    secondary = Color(0xFFBACDBB), onSecondary = Color(0xFF25372A),
    background = Color(0xFF111612), surface = Color(0xFF1A211C),
    surfaceVariant = Color(0xFF303A32),
)

@Composable
fun SnapBudgetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors, content = content)
}
