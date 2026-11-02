package com.jrpixel666.monerowallet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jrpixel666.monerowallet.ui.theme.Omarchy
import com.jrpixel666.monerowallet.ui.theme.OmarchyPalette
import com.jrpixel666.monerowallet.ui.theme.OmarchyPalettes

/**
 * A from-scratch widget set standing in for stock Material3 (`Card`, filled `TextField`, rounded
 * `Button`) wherever this app renders something — a tiling-WM floating-terminal look (sharp
 * rectangles, a titled window chrome, bracket-tagged log lines) reads as deliberately Omarchy-
 * themed; reskinning Material's own rounded-card-with-soft-shadow defaults in Omarchy's colors
 * would not.
 */

/** A bordered, titled panel — the one "window" unit everything on this screen is built from. */
@Composable
fun TerminalPanel(
  title: String,
  modifier: Modifier = Modifier,
  accentColor: Color = Omarchy.Accent,
  content: @Composable () -> Unit,
) {
  Column(modifier = modifier.fillMaxWidth().border(BorderStroke(1.dp, Omarchy.Muted))) {
    Row(
      modifier = Modifier.fillMaxWidth().background(Omarchy.Selection).padding(horizontal = 10.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(7.dp).background(accentColor))
      Spacer(Modifier.width(8.dp))
      Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, color = Omarchy.BrightForeground)
    }
    HorizontalDivider(color = Omarchy.Muted, thickness = 1.dp)
    Column(
      // Opaque, unlike the scrim the wallpaper sits under: a panel is a solid "window" floating
      // over the desktop, not a pane the wallpaper shows through — matching how a real terminal
      // emulator's own background (as opposed to the compositor's gaps) behaves over a wallpaper.
      modifier = Modifier.fillMaxWidth().background(Omarchy.Background).padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      content()
    }
  }
}

/** The current [OmarchyPalette]'s wallpaper, filling the screen behind everything, dimmed by a
 * scrim so panel-less areas (gaps between windows, the Waybar strip) stay legible over whatever
 * busy image is selected — exactly how a real desktop compositor's bar sits over a wallpaper. */
@Composable
fun WallpaperBackground(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize()) {
    Image(
      painter = painterResource(Omarchy.current.wallpaper),
      contentDescription = null,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
    )
    Box(Modifier.fillMaxSize().background(Omarchy.DarkerBackground.copy(alpha = 0.82f)))
  }
}

/** A horizontal strip of theme swatches — tap one to switch [Omarchy.current] and persist it. */
@Composable
fun ThemePicker(onSelect: (OmarchyPalette) -> Unit, modifier: Modifier = Modifier) {
  LazyRow(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    items(OmarchyPalettes) { palette ->
      val selected = palette.id == Omarchy.current.id
      Column(
        modifier =
          Modifier.width(76.dp)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) palette.accent else Omarchy.Muted))
            .clickable { onSelect(palette) }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Box(
          modifier =
            Modifier.fillMaxWidth().height(36.dp).background(palette.background).border(BorderStroke(1.dp, Omarchy.Muted)),
        ) {
          Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxSize().background(palette.accent))
            Box(Modifier.weight(1f).fillMaxSize().background(palette.green))
            Box(Modifier.weight(1f).fillMaxSize().background(palette.magenta))
          }
        }
        Text(
          palette.displayName,
          style = MaterialTheme.typography.labelSmall,
          color = if (selected) palette.accent else Omarchy.DarkForeground,
        )
      }
    }
  }
}

/** A solid, filled action — the one thing on a panel the user is most likely to want to press. */
@Composable
fun TerminalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Button(
    onClick = onClick,
    modifier = modifier.height(46.dp),
    shape = RectangleShape,
    colors = ButtonDefaults.buttonColors(containerColor = Omarchy.Accent, contentColor = Omarchy.DarkerBackground),
    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    contentPadding = PaddingValues(horizontal = 14.dp),
  ) {
    Text("❯ ${label.uppercase()}", style = MaterialTheme.typography.labelLarge)
  }
}

/** A secondary (bordered, unfilled) action. */
@Composable
fun TerminalOutlineButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  danger: Boolean = false,
) {
  val tint = if (danger) Omarchy.Red else Omarchy.Foreground
  OutlinedButton(
    onClick = onClick,
    modifier = modifier.height(46.dp),
    shape = RectangleShape,
    border = BorderStroke(1.dp, if (danger) Omarchy.Red else Omarchy.Muted),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
    contentPadding = PaddingValues(horizontal = 14.dp),
  ) {
    Text("❯ ${label.uppercase()}", style = MaterialTheme.typography.labelLarge)
  }
}

/** A labeled input — the label is a static prompt line above the field, not a Material floating
 * label, since a floating label is one of the most recognizable stock-Material tells. */
@Composable
fun TerminalTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("${label.uppercase()}:", style = MaterialTheme.typography.labelSmall, color = Omarchy.DarkForeground)
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.fillMaxWidth(),
      textStyle = MaterialTheme.typography.bodyMedium.copy(color = Omarchy.BrightForeground),
      singleLine = true,
      shape = RectangleShape,
      colors =
        OutlinedTextFieldDefaults.colors(
          focusedContainerColor = Color.Transparent,
          unfocusedContainerColor = Color.Transparent,
          focusedBorderColor = Omarchy.Accent,
          unfocusedBorderColor = Omarchy.Muted,
          cursorColor = Omarchy.Accent,
        ),
    )
  }
}

/** The severity of a [TerminalStatusLine]. */
enum class StatusLevel { INFO, OK, ERR }

private val ERROR_HINTS = listOf("fail", "err", "didn't match", "too many", "could not", "no ", "not a")
private val SUCCESS_HINTS =
  listOf("unlocked", "generated", "saved", "imported", "copied", "parsed", "added", "locked.")

/** Infers a [StatusLevel] from free-text status messages, so call sites don't all need to be
 * rewritten to carry a level alongside their message just for this screen's color-coding. */
fun inferStatusLevel(text: String): StatusLevel {
  val lower = text.lowercase()
  if (ERROR_HINTS.any { lower.contains(it) }) return StatusLevel.ERR
  if (SUCCESS_HINTS.any { lower.contains(it) }) return StatusLevel.OK
  return StatusLevel.INFO
}

/** A single bracket-tagged log line, e.g. `[OK] Generated a new wallet.` — a systemd/dmesg-style
 * status readout fits this theme far better than a plain, unstyled paragraph of text. */
@Composable
fun TerminalStatusLine(text: String, modifier: Modifier = Modifier) {
  if (text.isEmpty()) return
  val level = inferStatusLevel(text)
  val (tag, color) =
    when (level) {
      StatusLevel.OK -> "OK" to Omarchy.Green
      StatusLevel.ERR -> "ERR" to Omarchy.Red
      StatusLevel.INFO -> "II" to Omarchy.Accent
    }
  Row(modifier = modifier) {
    Text("[$tag] ", style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.Bold)
    Text(text, style = MaterialTheme.typography.bodySmall, color = Omarchy.Foreground)
  }
}

/** A thin, always-on-top status strip — a Waybar/Polybar module row, not an Android `TopAppBar`. */
@Composable
fun WaybarTopBar(locked: Boolean, subtitle: String) {
  Row(
    modifier = Modifier.fillMaxWidth().background(Omarchy.DarkBackground).padding(horizontal = 14.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(8.dp).background(if (locked) Omarchy.Red else Omarchy.Green))
    Spacer(Modifier.width(8.dp))
    Text("monero-wallet", style = MaterialTheme.typography.titleSmall, color = Omarchy.BrightForeground)
    Text("  │  ", style = MaterialTheme.typography.titleSmall, color = Omarchy.Muted)
    Text(
      if (locked) "LOCKED" else "UNLOCKED",
      style = MaterialTheme.typography.titleSmall,
      color = if (locked) Omarchy.Red else Omarchy.Green,
    )
    Spacer(Modifier.weight(1f))
    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Omarchy.DarkForeground)
  }
  HorizontalDivider(color = Omarchy.Muted, thickness = 1.dp)
}

/** A `$ command`-style prompt line, for section intros. */
@Composable
fun TerminalPrompt(text: String, modifier: Modifier = Modifier) {
  Row(modifier = modifier) {
    Text("❯ ", style = MaterialTheme.typography.bodyMedium, color = Omarchy.Accent, fontWeight = FontWeight.Bold)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Omarchy.Foreground)
  }
}
