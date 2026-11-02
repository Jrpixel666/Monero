package com.jrpixel666.monerowallet.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Omarchy's "Tokyo Night" theme palette (github.com/basecamp/omarchy, themes/tokyo-night/colors.toml),
 * used verbatim rather than approximated — dark, flat, terminal-minimal, no gradients or heavy
 * shadows, which is why the layout below uses bordered flat cards instead of the soft
 * drop-shadowed ones in the reference mockup.
 */
object Omarchy {
  val Background = Color(0xFF1A1B26)
  val DarkBackground = Color(0xFF13141C)
  val DarkerBackground = Color(0xFF0E0E14)
  val LighterBackground = Color(0xFF24283B)

  val Foreground = Color(0xFFA9B1D6)
  val DarkForeground = Color(0xFF565F89)
  val BrightForeground = Color(0xFFC0CAF5)

  val Accent = Color(0xFF7AA2F7) // "blue" in the source palette; also the theme's `accent` key
  val Selection = Color(0xFF292E42)
  val Muted = Color(0xFF414868)

  val Red = Color(0xFFF7768E)
  val Yellow = Color(0xFFE0AF68)
  val Green = Color(0xFF9ECE6A)
  val Magenta = Color(0xFFAD8EE6)
}
