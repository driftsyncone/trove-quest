package app.trove.quest.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.trove.quest.ui.StatusTag
import app.trove.quest.ui.TagLevel

// Shared building blocks. Every clickable thing here is at least Size.target (48 dp) tall.

private val buttonShape = RoundedCornerShape(Radius.control)
private val buttonPadding = PaddingValues(horizontal = Space.lg, vertical = Space.sm)

/** The one main action of a context (Play, Install, Log in, Back to video). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, large: Boolean = false) {
  Button(
      onClick = onClick,
      enabled = enabled,
      shape = buttonShape,
      contentPadding = buttonPadding,
      modifier = modifier.heightIn(min = if (large) Size.targetLarge else Size.target),
  ) {
    Text(text, maxLines = 1)
  }
}

/** A raised, filled button for the usual actions. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  FilledTonalButton(
      onClick = onClick,
      enabled = enabled,
      shape = buttonShape,
      contentPadding = buttonPadding,
      modifier = modifier.heightIn(min = Size.target),
  ) {
    Text(text, maxLines = 1)
  }
}

/** Neutral actions that should not draw the eye (Search, Prev / Next, Close). */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  OutlinedButton(
      onClick = onClick,
      enabled = enabled,
      shape = buttonShape,
      contentPadding = buttonPadding,
      modifier = modifier.heightIn(min = Size.target),
  ) {
    Text(text, maxLines = 1)
  }
}

/** Text-only, for dismissals (Later, Done) and the account button. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.accent) {
  TextButton(
      onClick = onClick,
      enabled = enabled,
      shape = buttonShape,
      contentPadding = buttonPadding,
      modifier = modifier.heightIn(min = Size.target),
  ) {
    Text(text, color = if (enabled) color else Color.Unspecified, maxLines = 1)
  }
}

/**
 * Pick-one / toggle / removable chip. Selected = accent fill, otherwise raised with an outline.
 * [kind] is the small coloured word before the label (performer / studio / tag); [trailing] a mark
 * after it (the ✕ of an applied filter).
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: String? = null,
    kindColor: Color = Palette.muted,
    trailing: String? = null,
) {
  val shape = RoundedCornerShape(Radius.control)
  val fg = if (selected) Palette.onAccent else Palette.text
  Row(
      modifier =
          modifier
              .heightIn(min = Size.target)
              .clip(shape)
              .background(if (selected) Palette.accent else Palette.raised)
              .then(if (selected) Modifier else Modifier.border(Size.borderThin, Palette.outline, shape))
              .clickable(role = Role.Button, onClick = onClick)
              .padding(horizontal = Space.lg),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Space.sm),
  ) {
    if (kind != null) {
      Text(kind, color = if (selected) fg else kindColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
    Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
    if (trailing != null) Text(trailing, color = fg, style = MaterialTheme.typography.labelLarge)
  }
}

/**
 * A scrolling row of chips, one per option. Used instead of DropdownMenu because menus open a
 * popup window, which is not reliable inside Spatial SDK panels.
 */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
  LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
    items(options) { o -> Chip(label(o), selected = o == selected, onClick = { onPick(o) }) }
  }
}

/** A small muted word before a row of controls ("View", "Behind", "Open in"). */
@Composable
fun RowLabel(text: String, modifier: Modifier = Modifier) {
  Text(text, color = Palette.muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = modifier)
}

/** Read-only status (never clickable). [maxWidth] stops a long message squeezing its neighbours. */
@Composable
fun StatusPill(tag: StatusTag, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(Radius.pill)
  val alert = tag.level == TagLevel.ALERT
  val color =
      when (tag.level) {
        TagLevel.NEUTRAL -> Palette.muted
        TagLevel.ACCENT -> Palette.accent
        TagLevel.OK -> Palette.ok
        TagLevel.WARN -> Palette.warn
        TagLevel.ALERT -> Palette.onAlert
      }
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          modifier
              .heightIn(min = Size.pillHeight)
              .then(if (alert) Modifier.background(Palette.alert, shape) else Modifier.border(Size.borderThin, color, shape))
              .padding(horizontal = Space.md),
  ) {
    Text(
        tag.text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (alert) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 420.dp),
    )
  }
}

/** Small label on a thumbnail (resolution, "Script", duration), on a dark scrim so it reads on any picture. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
  Text(
      text,
      color = Color.White,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      modifier = modifier.clip(RoundedCornerShape(Radius.badge)).background(Palette.scrim).padding(horizontal = Space.sm, vertical = Space.xs / 2),
  )
}

/** Empty / loading / error content of an area: message, optional spinner, optional action. */
@Composable
fun StateMessage(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    loading: Boolean = false,
    error: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
  Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
        modifier = Modifier.widthIn(max = 560.dp),
    ) {
      if (loading) CircularProgressIndicator(Modifier.size(Size.targetLarge))
      Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          color = if (error) Palette.errorText else Palette.text,
          textAlign = TextAlign.Center,
      )
      if (message != null) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
      }
      action?.invoke()
    }
  }
}

/**
 * The handle pill + "Grip to move" for the bottom edge of movable panels; while the panel is held
 * it shows [hint] instead (what the thumbstick does, README "Moving panels").
 */
@Composable
fun GrabHint(modifier: Modifier = Modifier, hint: String? = null) {
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
    Box(Modifier.size(width = 120.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.5f)))
    Text(
        hint ?: "Grip to move",
        style = MaterialTheme.typography.labelSmall,
        color = if (hint != null) Palette.accent else Color.White.copy(alpha = 0.75f),
        maxLines = 1,
    )
  }
}
