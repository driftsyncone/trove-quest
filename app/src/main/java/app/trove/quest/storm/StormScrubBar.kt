package app.trove.quest.storm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.trove.quest.player.MarkerTicks
import app.trove.quest.player.TICK_STRIP
import app.trove.quest.ui.fmtDuration
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.theme.StashTheme

/**
 * The scrub bar on the Focus centre's bottom edge: the screen controls' seek bar (marker ticks above,
 * elapsed, slider, total) in a small panel. Trigger on the slider or a tick seeks the centre; a seek
 * on a still-preview centre first upgrades it to the original (StormManager.seekCentre).
 */
@Composable
fun StormScrubBar(storm: StormManager) {
  StashTheme {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = storm.scrubDurationMs
    val position = storm.scrubPositionMs
    Box(Modifier.fillMaxSize()) {
      Column(
          modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.control)).background(Palette.panel).padding(horizontal = Space.sm),
          verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
      ) {
        Row(Modifier.height(TICK_STRIP), verticalAlignment = Alignment.CenterVertically) {
          Spacer(Modifier.width(PLAY_W + TIME_W))
          Box(Modifier.weight(1f)) { MarkerTicks(storm.scrubMarkers, duration, onSeek = { storm.seekCentre(it) }) }
          Spacer(Modifier.width(TIME_W))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          SecondaryButton(if (storm.scrubPlaying) "Pause" else "Play", onClick = { storm.toggleCentrePlay() }, modifier = Modifier.width(PLAY_W))
          TimeText(fmtDuration(position / 1000.0), TextAlign.End)
          Slider(
              value = if (dragging) dragValue else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
              onValueChange = {
                dragging = true
                dragValue = it
              },
              onValueChangeFinished = {
                if (duration > 0) storm.seekCentre((dragValue * duration).toLong())
                dragging = false
              },
              modifier = Modifier.weight(1f),
          )
          TimeText(fmtDuration(duration / 1000.0), TextAlign.Start)
        }
      }
    }
  }
}

@Composable
private fun TimeText(text: String, align: TextAlign) {
  Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.muted, textAlign = align, maxLines = 1, modifier = Modifier.width(TIME_W).padding(horizontal = Space.xs))
}

private val TIME_W = 72.dp
private val PLAY_W = 104.dp
