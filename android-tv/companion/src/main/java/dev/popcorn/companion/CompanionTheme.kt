package dev.popcorn.companion

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme

val Bg = Color(0xFF090B10)
val Surface1 = Color(0xFF121722)
val Surface2 = Color(0xFF1A2030)
val Surface3 = Color(0xFF293548)
val Line = Color(0xFF344156)
val TextColor = Color(0xFFE8ECF2)
val Muted = Color(0xFF97A0B2)
val Accent = Color(0xFF4FD1A5)
val Blue = Color(0xFF63A8FF)
val NavSelected = Color(0xFF4A405F)
val ErrorRed = Color(0xFFFF8B8B)

val PopcornColorScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Color.Black,
    secondary = Accent,
    onSecondary = Color.Black,
    background = Bg,
    onBackground = TextColor,
    surface = Surface1,
    onSurface = TextColor,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    error = ErrorRed,
)
