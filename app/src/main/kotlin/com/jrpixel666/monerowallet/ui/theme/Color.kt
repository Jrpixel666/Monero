package com.jrpixel666.monerowallet.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.jrpixel666.monerowallet.R

/**
 * One Omarchy theme's palette, taken verbatim from its `colors.toml`
 * (github.com/basecamp/omarchy/tree/master/themes/<id>) rather than approximated, plus one of
 * its own per-theme wallpapers (downscaled/recompressed for mobile; see
 * assets/licenses/omarchy_wallpapers_MIT.txt for provenance — Omarchy is MIT-licensed).
 */
data class OmarchyPalette(
  val id: String,
  val displayName: String,
  val background: Color,
  val darkBackground: Color,
  val darkerBackground: Color,
  val lighterBackground: Color,
  val foreground: Color,
  val darkForeground: Color,
  val brightForeground: Color,
  val accent: Color,
  val selection: Color,
  val muted: Color,
  val red: Color,
  val green: Color,
  val magenta: Color,
  @DrawableRes val wallpaper: Int,
)

/** Every Omarchy theme this app bundles a wallpaper and exact palette for. */
val OmarchyPalettes =
  listOf(
    OmarchyPalette(
      id = "tokyo_night",
      displayName = "Tokyo Night",
      background = Color(0xFF1A1B26),
      darkBackground = Color(0xFF13141C),
      darkerBackground = Color(0xFF0E0E14),
      lighterBackground = Color(0xFF24283B),
      foreground = Color(0xFFA9B1D6),
      darkForeground = Color(0xFF565F89),
      brightForeground = Color(0xFFC0CAF5),
      accent = Color(0xFF7AA2F7),
      selection = Color(0xFF292E42),
      muted = Color(0xFF414868),
      red = Color(0xFFF7768E),
      green = Color(0xFF9ECE6A),
      magenta = Color(0xFFAD8EE6),
      wallpaper = R.drawable.wallpaper_tokyo_night,
    ),
    OmarchyPalette(
      id = "gruvbox",
      displayName = "Gruvbox",
      background = Color(0xFF282828),
      darkBackground = Color(0xFF1E1E1E),
      darkerBackground = Color(0xFF161616),
      lighterBackground = Color(0xFF3C3836),
      foreground = Color(0xFFD4BE98),
      darkForeground = Color(0xFF7C6F64),
      brightForeground = Color(0xFFD4BE98),
      accent = Color(0xFF7DAEA3),
      selection = Color(0xFF504945),
      muted = Color(0xFF665C54),
      red = Color(0xFFEA6962),
      green = Color(0xFFA9B665),
      magenta = Color(0xFFD3869B),
      wallpaper = R.drawable.wallpaper_gruvbox,
    ),
    OmarchyPalette(
      id = "nord",
      displayName = "Nord",
      background = Color(0xFF2E3440),
      darkBackground = Color(0xFF222730),
      darkerBackground = Color(0xFF191C23),
      lighterBackground = Color(0xFF3B4252),
      foreground = Color(0xFFD8DEE9),
      darkForeground = Color(0xFF667080),
      brightForeground = Color(0xFFD8DEE9),
      accent = Color(0xFF81A1C1),
      selection = Color(0xFF434C5E),
      muted = Color(0xFF4C566A),
      red = Color(0xFFBF616A),
      green = Color(0xFFA3BE8C),
      magenta = Color(0xFFB48EAD),
      wallpaper = R.drawable.wallpaper_nord,
    ),
    OmarchyPalette(
      id = "catppuccin",
      displayName = "Catppuccin",
      background = Color(0xFF1E1E2E),
      darkBackground = Color(0xFF161622),
      darkerBackground = Color(0xFF101019),
      lighterBackground = Color(0xFF313244),
      foreground = Color(0xFFCDD6F4),
      darkForeground = Color(0xFF6C7086),
      brightForeground = Color(0xFFCDD6F4),
      accent = Color(0xFF89B4FA),
      selection = Color(0xFF45475A),
      muted = Color(0xFF585B70),
      red = Color(0xFFF38BA8),
      green = Color(0xFFA6E3A1),
      magenta = Color(0xFFF5C2E7),
      wallpaper = R.drawable.wallpaper_catppuccin,
    ),
    OmarchyPalette(
      id = "everforest",
      displayName = "Everforest",
      background = Color(0xFF2D353B),
      darkBackground = Color(0xFF21272C),
      darkerBackground = Color(0xFF181D20),
      lighterBackground = Color(0xFF343F44),
      foreground = Color(0xFFD3C6AA),
      darkForeground = Color(0xFF4F585E),
      brightForeground = Color(0xFFD3C6AA),
      accent = Color(0xFF7FBBB3),
      selection = Color(0xFF3D484D),
      muted = Color(0xFF475258),
      red = Color(0xFFE67E80),
      green = Color(0xFFA7C080),
      magenta = Color(0xFFD699B6),
      wallpaper = R.drawable.wallpaper_everforest,
    ),
    OmarchyPalette(
      id = "matte_black",
      displayName = "Matte Black",
      background = Color(0xFF121212),
      darkBackground = Color(0xFF0D0D0D),
      darkerBackground = Color(0xFF090909),
      lighterBackground = Color(0xFF1E1E1E),
      foreground = Color(0xFFBEBEBE),
      darkForeground = Color(0xFF555555),
      brightForeground = Color(0xFFBEBEBE),
      accent = Color(0xFFE68E0D),
      selection = Color(0xFF2A2A2A),
      muted = Color(0xFF333333),
      red = Color(0xFFD35F5F),
      green = Color(0xFFFFC107),
      magenta = Color(0xFFD35F5F),
      wallpaper = R.drawable.wallpaper_matte_black,
    ),
  )

/**
 * The active palette, Compose-observable the same way [com.jrpixel666.monerowallet.LockState] is
 * — every `Omarchy.X` reference throughout the app (there are many: `TerminalWidgets.kt`,
 * `MainActivity.kt`) stays as a simple property read, and switching [current] recomposes all of
 * them automatically, with no need to thread a palette parameter through every composable.
 */
object Omarchy {
  var current by mutableStateOf(OmarchyPalettes.first())
    private set

  fun select(palette: OmarchyPalette) {
    current = palette
  }

  val Background: Color get() = current.background
  val DarkBackground: Color get() = current.darkBackground
  val DarkerBackground: Color get() = current.darkerBackground
  val LighterBackground: Color get() = current.lighterBackground
  val Foreground: Color get() = current.foreground
  val DarkForeground: Color get() = current.darkForeground
  val BrightForeground: Color get() = current.brightForeground
  val Accent: Color get() = current.accent
  val Selection: Color get() = current.selection
  val Muted: Color get() = current.muted
  val Red: Color get() = current.red
  val Green: Color get() = current.green
  val Magenta: Color get() = current.magenta
}
