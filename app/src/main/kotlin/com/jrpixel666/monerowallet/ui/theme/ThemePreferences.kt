package com.jrpixel666.monerowallet.ui.theme

import android.content.Context

/**
 * Persists the selected [OmarchyPalette] across app restarts. Just a theme choice, not secret
 * material, so plain `SharedPreferences` is the right amount of ceremony — no reason to involve
 * `storage`'s Argon2id/XChaCha20-Poly1305 envelope for a value this low-stakes.
 */
object ThemePreferences {
  private const val PREFS = "theme_prefs"
  private const val KEY_SELECTED = "selected_theme_id"

  /** Load the saved palette (if any) and make it [Omarchy.current], before the first frame
   * renders — call this once, early in `onCreate`, before `setContent`. */
  fun restore(context: Context) {
    val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED, null)
    val palette = OmarchyPalettes.find { it.id == id } ?: return
    Omarchy.select(palette)
  }

  fun save(context: Context, palette: OmarchyPalette) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED, palette.id).apply()
  }
}
