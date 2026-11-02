package com.jrpixel666.monerowallet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jrpixel666.monerowallet.R

/**
 * Omarchy's actual default font — confirmed from the project's own manual
 * (learn.omacom.io/2/the-omarchy-manual/94/fonts): JetBrainsMono Nerd Font is both Omarchy's
 * terminal font and its system/UI font, not two different fonts for two different jobs. This
 * bundles plain JetBrains Mono (SIL OFL 1.1, see assets/licenses/) rather than the Nerd Font
 * patch, since the patch only adds icon glyphs this app's own text never references. The font
 * itself doesn't change with the selected [OmarchyPalette] — only the colors and wallpaper do,
 * matching how Omarchy's own theme switcher works.
 */
private val JetBrainsMono =
  FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
  )

private val OmarchyTypography =
  Typography(
    titleMedium =
      TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        letterSpacing = 0.5.sp,
      ),
    titleSmall =
      TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 1.2.sp,
      ),
    bodyLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge =
      TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
      ),
    labelSmall =
      TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp,
      ),
  )

// Every shape in this app is overridden to RectangleShape at the call site (see
// ui/components/TerminalWidgets.kt) — a tiling-WM/terminal aesthetic doesn't round its windows.
// This exists only so unstyled stock Material3 internals (e.g. ripple clipping) have *something*
// sane to fall back to, not because this theme actually uses rounded corners anywhere visible.
private val OmarchyShapes =
  Shapes(small = RoundedCornerShape(0.dp), medium = RoundedCornerShape(0.dp), large = RoundedCornerShape(0.dp))

@Composable
fun OmarchyTheme(content: @Composable () -> Unit) {
  // Read inside the composable body, not hoisted to a top-level val: Omarchy.current is
  // Compose-observable (see Color.kt), so this must recompute on every selection change for
  // theme switching to actually repaint Material3's internals (ripples, text-field indicators,
  // etc.) that read colorScheme rather than Omarchy.* directly.
  val colorScheme =
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
  MaterialTheme(colorScheme = colorScheme, typography = OmarchyTypography, shapes = OmarchyShapes, content = content)
}
