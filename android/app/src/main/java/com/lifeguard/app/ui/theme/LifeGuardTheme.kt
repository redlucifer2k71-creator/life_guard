package com.lifeguard.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Life Guard Brand Colors ──

object LifeGuardColors {
    // Primary brand — emergency red
    val RedPrimary        = Color(0xFFD32F2F)
    val RedLight          = Color(0xFFFF6659)
    val RedDark           = Color(0xFF9A0007)

    // Guard Mode active — warning orange
    val OrangeGuard       = Color(0xFFFF6D00)
    val OrangeLight       = Color(0xFFFF9E40)

    // Safe/on-route — green
    val GreenSafe         = Color(0xFF4CAF50)
    val GreenLight        = Color(0xFF80E27E)

    // Maps blue
    val BlueRoute         = Color(0xFF4285F4)

    // Dark background palette
    val BgPrimary         = Color(0xFF0D0D0D)   // Deepest background
    val BgSurface         = Color(0xFF141414)   // Card surface
    val BgElevated        = Color(0xFF1C1C1C)   // Elevated card
    val BgHighlight       = Color(0xFF252525)   // Selected/active highlight

    // Text
    val TextPrimary       = Color(0xFFFFFFFF)
    val TextSecondary     = Color(0xFFAAAAAA)
    val TextMuted         = Color(0xFF666666)
    val TextDisabled      = Color(0xFF444444)

    // Semantic
    val DangerBg          = Color(0x1AD32F2F)   // Red with 10% opacity
    val SuccessBg         = Color(0x1A4CAF50)   // Green with 10% opacity
    val WarningBg         = Color(0x1AFF6D00)   // Orange with 10% opacity
}

// ── Material3 Dark Color Scheme ──

private val LifeGuardColorScheme = darkColorScheme(
    primary          = LifeGuardColors.RedPrimary,
    onPrimary        = Color.White,
    primaryContainer = LifeGuardColors.RedDark,
    onPrimaryContainer = LifeGuardColors.RedLight,

    secondary        = LifeGuardColors.OrangeGuard,
    onSecondary      = Color.White,

    tertiary         = LifeGuardColors.GreenSafe,
    onTertiary       = Color.White,

    background       = LifeGuardColors.BgPrimary,
    onBackground     = LifeGuardColors.TextPrimary,

    surface          = LifeGuardColors.BgSurface,
    onSurface        = LifeGuardColors.TextPrimary,
    surfaceVariant   = LifeGuardColors.BgElevated,
    onSurfaceVariant = LifeGuardColors.TextSecondary,

    outline          = LifeGuardColors.TextMuted,
    error            = LifeGuardColors.RedLight,
    onError          = Color.White,
)

// ── Typography ──

private val LifeGuardTypography = Typography(
    // Large display — app name, splash
    displayLarge = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 48.sp,
        letterSpacing = (-1).sp,
        color = LifeGuardColors.TextPrimary
    ),
    // Screen titles
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 0.sp,
        color = LifeGuardColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = 0.sp,
        color = LifeGuardColors.TextPrimary
    ),
    // Card titles
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 0.sp,
        color = LifeGuardColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.sp,
        color = LifeGuardColors.TextPrimary
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.sp,
        color = LifeGuardColors.TextPrimary
    ),
    // Body text
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = LifeGuardColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = LifeGuardColors.TextSecondary
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = LifeGuardColors.TextMuted
    ),
    // Labels
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.5.sp,
        color = LifeGuardColors.TextPrimary
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = LifeGuardColors.TextMuted
    ),
)

// ── Theme Entry Point ──

@Composable
fun LifeGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LifeGuardColorScheme,
        typography = LifeGuardTypography,
        content = content
    )
}
