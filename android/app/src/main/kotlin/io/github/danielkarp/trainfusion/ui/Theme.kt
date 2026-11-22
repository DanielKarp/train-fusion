package io.github.danielkarp.trainfusion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** The iOS app's system colours (light, dark), so the two apps look alike. */
object Palette {
    private val dark @Composable @ReadOnlyComposable get() = isSystemInDarkTheme()
    private fun pick(dark: Boolean, light: Long, night: Long) = Color(if (dark) night else light)

    val blue @Composable @ReadOnlyComposable get() = pick(dark, 0xFF007AFF, 0xFF0A84FF)
    val green @Composable @ReadOnlyComposable get() = pick(dark, 0xFF34C759, 0xFF30D158)
    val yellow @Composable @ReadOnlyComposable get() = pick(dark, 0xFFFFCC00, 0xFFFFD60A)
    val orange @Composable @ReadOnlyComposable get() = pick(dark, 0xFFFF9500, 0xFFFF9F0A)
    val red @Composable @ReadOnlyComposable get() = pick(dark, 0xFFFF3B30, 0xFFFF453A)
    val teal @Composable @ReadOnlyComposable get() = pick(dark, 0xFF30B0C7, 0xFF40C8E0)
    val pink @Composable @ReadOnlyComposable get() = pick(dark, 0xFFFF2D55, 0xFFFF375F)
    val gray @Composable @ReadOnlyComposable get() = pick(dark, 0xFF8E8E93, 0xFF8E8E93)
    val gray2 @Composable @ReadOnlyComposable get() = pick(dark, 0xFFAEAEB2, 0xFF636366)
    val gray4 @Composable @ReadOnlyComposable get() = pick(dark, 0xFFD1D1D6, 0xFF3A3A3C)
    val card @Composable @ReadOnlyComposable get() = pick(dark, 0xFFF2F2F7, 0xFF1C1C1E)
    val background @Composable @ReadOnlyComposable get() = pick(dark, 0xFFFFFFFF, 0xFF000000)
    val groupedBackground @Composable @ReadOnlyComposable get() = pick(dark, 0xFFF2F2F7, 0xFF000000)
    val secondary @Composable @ReadOnlyComposable get() = pick(dark, 0x993C3C43, 0x99EBEBF5)
    val separator @Composable @ReadOnlyComposable get() = pick(dark, 0x4A3C3C43, 0x99545458)
}

@Composable
fun TrainFusionTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF0A84FF), onPrimary = Color.White, background = Color.Black, surface = Color.Black,
            surfaceContainer = Color(0xFF1C1C1E), surfaceContainerLow = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E),
            secondaryContainer = Color(0xFF2C2C2E), error = Color(0xFFFF453A))
    } else {
        lightColorScheme(primary = Color(0xFF007AFF), onPrimary = Color.White, background = Color.White, surface = Color.White,
            surfaceContainer = Color(0xFFF2F2F7), surfaceContainerLow = Color(0xFFF7F7FA), surfaceContainerHigh = Color(0xFFEDEDF2),
            secondaryContainer = Color(0xFFE3E8F4), error = Color(0xFFFF3B30))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
