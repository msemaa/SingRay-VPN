package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Obsidian & Titanium Engineering Palette (Dark Defaults)
val TitaniumDarkBackground = Color(0xFF0B0F17)
val TitaniumSurface = Color(0xFF111827)
val TitaniumSurfaceVariant = Color(0xFF1F2937)
val TitaniumBorder = Color(0xFF374151)

// Light Engineering Palette
val LightArchitecturalBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightBorder = Color(0xFFE2E8F0)

// Technical Accents - Dark
val CyanNeon = Color(0xFF00E5FF)
val CyanGlow = Color(0x3300E5FF)
val MintTelemetry = Color(0xFF10B981)
val MintGlow = Color(0x3310B981)
val AmberWarning = Color(0xFFF59E0B)
val RubyCritical = Color(0xFFEF4444)
val SlateMuted = Color(0xFF94A3B8)
val SlateDim = Color(0xFF64748B)

// Technical Accents - Light (High Contrast)
val CyanLightAccent = Color(0xFF0284C7)
val MintLightAccent = Color(0xFF059669)
val AmberLightAccent = Color(0xFFD97706)
val RubyLightAccent = Color(0xFFDC2626)

val WhitePure = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val TextTertiary = Color(0xFF64748B)

val TextPrimaryLight = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF475569)
val TextTertiaryLight = Color(0xFF64748B)

data class CustomAppColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentCyan: Color,
    val accentMint: Color,
    val accentAmber: Color,
    val accentRuby: Color,
    val isDark: Boolean
)

val DarkCustomColors = CustomAppColors(
    background = TitaniumDarkBackground,
    surface = TitaniumSurface,
    surfaceVariant = TitaniumSurfaceVariant,
    border = TitaniumBorder,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    textMuted = SlateDim,
    accentCyan = CyanNeon,
    accentMint = MintTelemetry,
    accentAmber = AmberWarning,
    accentRuby = RubyCritical,
    isDark = true
)

val LightCustomColors = CustomAppColors(
    background = LightArchitecturalBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    border = LightBorder,
    textPrimary = TextPrimaryLight,
    textSecondary = TextSecondaryLight,
    textMuted = TextTertiaryLight,
    accentCyan = CyanLightAccent,
    accentMint = MintLightAccent,
    accentAmber = AmberLightAccent,
    accentRuby = RubyLightAccent,
    isDark = false
)

val LocalAppColors = staticCompositionLocalOf { DarkCustomColors }

object AppTheme {
    val colors: CustomAppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}
