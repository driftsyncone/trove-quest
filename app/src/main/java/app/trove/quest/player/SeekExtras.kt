package app.trove.quest.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.trove.quest.api.Marker
import app.trove.quest.ui.theme.ChipRow
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.Size
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.fmtDuration
import kotlinx.coroutines.delay

// Additions to the control panel (markers on the seek bar, resume offer), kept out of ControlPanel.kt
// so changes to the panel itself merge easily.

/** Where marker [seconds] sits along a seek bar of [durationMs], 0..1; null when it can't be placed. */
fun markerFraction(seconds: Double, durationMs: Long): Float? {
  if (durationMs <= 0) return null
  val f = (seconds * 1000 / durationMs).toFloat()
  return if (f.isFinite() && f in 0f..1f) f else null
}

/**
 * A strip of marker ticks to put right over the seek bar (same width). Each tick is a small target:
 * trigger on it jumps there. [SLIDER_INSET] lines the ticks up with the Material 3 slider's track,
 * which starts half a thumb in from each end.
 */
@Composable
fun MarkerTicks(markers: List<Marker>, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
  val placed = markers.mapNotNull { m -> markerFraction(m.seconds, durationMs)?.let { m to it } }
  if (placed.isEmpty()) return
  BoxWithConstraints(modifier.fillMaxWidth().height(TICK_HIT)) {
    val track = maxWidth - SLIDER_INSET * 2
    for ((m, f) in placed) {
      Box(
          contentAlignment = Alignment.Center,
          modifier =
              Modifier.offset(x = SLIDER_INSET + track * f - TICK_HIT / 2)
                  .size(TICK_HIT)
                  .clip(RoundedCornerShape(4.dp))
                  .clickable { onSeek((m.seconds * 1000).toLong()) },
      ) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(Palette.text))
      }
    }
  }
}

/** The "Markers" chip row (the control panel's Markers mode): time + title, tap to jump. */
@Composable
fun MarkerChips(markers: List<Marker>, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
  ChipRow(
      options = markers,
      selected = null,
      label = { "${fmtDuration(it.seconds)}  ${it.title}" },
      onPick = { onSeek((it.seconds * 1000).toLong()) },
      modifier = modifier,
  )
}

/**
 * "Resume at mm:ss" for the primary row while [PlayerSession.resumeOfferMs] is set: tap = jump there,
 * ✕ = start from the beginning. Goes away on its own after [OFFER_MS].
 */
@Composable
fun ResumeOffer(session: PlayerSession) {
  val at = session.resumeOfferMs ?: return
  LaunchedEffect(session, at) {
    delay(OFFER_MS)
    session.resumeOfferMs = null
  }
  val shape = RoundedCornerShape(Radius.control)
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.clip(shape).border(Size.borderThin, Palette.accent, shape),
  ) {
    Text(
        "Resume at ${fmtDuration(at / 1000.0)}",
        style = MaterialTheme.typography.labelLarge,
        color = Palette.accent,
        maxLines = 1,
        modifier =
            Modifier.heightIn(min = Size.target)
                .clickable {
                  session.resumeOfferMs = null
                  session.seekTo(at)
                  session.play()
                }
                .padding(horizontal = Space.lg, vertical = Space.md),
    )
    Text(
        "✕",
        style = MaterialTheme.typography.labelLarge,
        color = Palette.muted,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.heightIn(min = Size.target)
                .widthIn(min = Size.target)
                .clickable { session.resumeOfferMs = null }
                .padding(horizontal = Space.md, vertical = Space.md),
    )
  }
}

/** Height of the marker strip above the seek bar. */
val TICK_STRIP = 28.dp
private val TICK_HIT = TICK_STRIP
private val SLIDER_INSET = 2.dp
private const val OFFER_MS = 20_000L
