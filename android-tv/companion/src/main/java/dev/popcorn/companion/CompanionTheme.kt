package dev.popcorn.companion

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Cinnamon's phone palette mirrors the TV experience while keeping slightly
// brighter surfaces for touch controls in daylight.
val Bg = Color(0xFF050B0F)
val Surface1 = Color(0xFF0C141A)
val Surface2 = Color(0xFF111B22)
val Surface3 = Color(0xFF19262E)
val Line = Color(0xFF2B3942)
val TextColor = Color(0xFFF3F1EF)
val Muted = Color(0xFF99A5AD)
val Accent = Color(0xFFF47B35)
val AccentDim = Color(0xFFB95525)
val Teal = Color(0xFF42C7BD)
val Gold = Color(0xFFF28A2E)
val Blue = Teal
val NavSelected = Accent.copy(alpha = .15f)
val ErrorRed = Color(0xFFFF6B6B)

val PopcornColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentDim,
    onPrimaryContainer = Color.White,
    secondary = Teal,
    onSecondary = Color.Black,
    background = Bg,
    onBackground = TextColor,
    surface = Surface1,
    onSurface = TextColor,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Line,
    outlineVariant = Line.copy(alpha = .55f),
    error = ErrorRed,
)

val PopcornTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.35).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
)

val PopcornShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)
