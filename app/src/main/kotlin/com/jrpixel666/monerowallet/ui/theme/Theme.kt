package com.jrpixel666.monerowallet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OmarchyColorScheme =
  darkColorScheme(
    primary = Omarchy.Accent,
    onPrimary = Omarchy.DarkerBackground,
    secondary = Omarchy.Magenta,
    onSecondary = Omarchy.DarkerBackground,
    error = Omarchy.Red,
    onError = Omarchy.DarkerBackground,
    background = Omarchy.DarkerBackground,
    onBackground = Omarchy.Foreground,
    surface = Omarchy.Background,
    onSurface = Omarchy.Foreground,
    surfaceVariant = Omarchy.LighterBackground,
    onSurfaceVariant = Omarchy.DarkForeground,
    outline = Omarchy.Muted,
  )

// Omarchy is a terminal-first, Hyprland-tiled environment — monospace, not a humanist sans, is
// the on-theme choice here. FontFamily.Monospace is the platform's built-in monospace font
// family so this doesn't need to bundle/license a font file to look the part.
private val OmarchyTypography =
  Typography(
    titleMedium =
      TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
    labelLarge =
      TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp),
  )

// Flat and sharp-ish rather than the soft, heavily-rounded cards in the reference mockup —
// Omarchy's whole aesthetic is flat terminal windows, not soft-shadowed glassmorphism.
private val OmarchyShapes =
  Shapes(small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(8.dp))

@Composable
fun OmarchyTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = OmarchyColorScheme, typography = OmarchyTypography, shapes = OmarchyShapes, content = content)
}
