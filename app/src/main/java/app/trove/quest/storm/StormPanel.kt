package app.trove.quest.storm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.trove.quest.api.SceneQuery
import app.trove.quest.player.TimerChips
import app.trove.quest.player.VacuGlideToyRow
import app.trove.quest.player.VolumeSlider
import app.trove.quest.sync.SessionTimer
import kotlinx.coroutines.delay
import app.trove.quest.hold.HoldState
import app.trove.quest.hold.LocalPanelHeld
import app.trove.quest.hold.holdHintText
import app.trove.quest.player.RoomView
import app.trove.quest.player.Room
import app.trove.quest.ui.theme.ChipRow
import app.trove.quest.ui.StatusTag
import app.trove.quest.ui.TagLevel
import app.trove.quest.ui.theme.PrimaryButton
import app.trove.quest.ui.theme.QuietButton
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.RowLabel
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.theme.StatusPill
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.StashTheme

/**
 * The wall header's Storm button: builds the pool from the wall's current [query] and opens the
 * wall of tiles. While Storm is on it rebuilds from the (possibly changed) query. A pool with too
 * few usable scenes says so and offers to storm the whole library instead.
 */
@Composable
fun StormButton(storm: StormManager, query: SceneQuery) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
    storm.error?.let {
      Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.errorText, maxLines = 3, modifier = Modifier.widthIn(max = 320.dp))
      if (storm.canStormAll) QuietButton("Storm whole library", onClick = { storm.startAll() }, enabled = !storm.loading)
    }
    SecondaryButton(if (storm.loading) "Storm…" else if (storm.active) "Re-storm" else "Storm", onClick = { storm.start(query) }, enabled = !storm.loading)
  }
}

/** The compact storm control panel: chip rows like the screens' controls (no DropdownMenu, see ChipRow). */
@Composable
fun StormControls(storm: StormManager, room: Room? = null) {
  StashTheme {
   CompositionLocalProvider(LocalPanelHeld provides HoldState.any) {
    // Wall clock for the session timer's tag (the server's endsAt is epoch ms).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
      while (true) {
        now = System.currentTimeMillis()
        delay(500)
      }
    }
    Column(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.controlsPanel)).background(Palette.panel).padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
      // Header: what is on, the timer, and the ways out.
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("Storm", style = MaterialTheme.typography.titleMedium)
        Text(
            holdHintText(storm.volumeHint) ?: ("${storm.shownTiles} tiles · ${storm.poolSize} scenes" + if (storm.paused) " · paused" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Fewer tiles than asked for: said, not hidden (README "Storm", decoder budget).
        storm.budgetNote?.let { StatusPill(StatusTag(it, TagLevel.WARN)) }
        // A short notice (the timer's "5 min left", "Timer ended") and the session timer's time left.
        storm.sync?.state?.notice?.let { StatusPill(StatusTag(it, TagLevel.OK)) }
        val serverNow = SessionTimer.serverNow(now, storm.sync?.state?.clockOffsetMs ?: 0L)
        SessionTimer.tagText(storm.sync?.state?.timer, serverNow)?.let {
          StatusPill(StatusTag(it, if (SessionTimer.warning(storm.sync?.state?.timer, serverNow)) TagLevel.WARN else TagLevel.NEUTRAL))
        }
        QuietButton("Hide", onClick = { storm.hidePanel() }, color = Palette.muted)
        QuietButton("Close", onClick = { storm.close() }, color = Palette.muted)
      }
      Divider()
      // What the wall looks like.
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        RowLabel("Layout", Modifier.width(LABEL_W))
        ChipRow(StormLayoutMode.entries, storm.layout, { it.label }, { storm.pickLayout(it) }, Modifier.weight(1f))
        if (storm.layout == StormLayoutMode.FOCUS) {
          RowLabel("Around", Modifier.width(LABEL_W))
          ChipRow(Storm.FOCUS_RING_COUNTS, storm.focusRing, { it.toString() }, { storm.pickFocusRing(it) }, Modifier.weight(1f))
        } else {
          RowLabel("Tiles", Modifier.width(LABEL_W))
          ChipRow(Storm.TILE_COUNTS, storm.requestedTiles, { it.toString() }, { storm.setTiles(it) }, Modifier.weight(1f))
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        RowLabel("Source", Modifier.width(LABEL_W))
        ChipRow(SourceMode.entries, storm.sourceMode, { it.label }, { storm.setSource(it) }, Modifier.weight(1f))
        RowLabel("Sound", Modifier.width(LABEL_W))
        ChipRow(
            listOf(false, true), storm.soundOn,
            { if (it) (if (storm.layout == StormLayoutMode.FOCUS) "Centre" else "Selected") else "Mute" }, { storm.setSound(it) },
            Modifier.weight(1f),
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        RowLabel("Clip", Modifier.width(LABEL_W))
        Slider(
            value = storm.clipSeconds.toFloat(),
            onValueChange = { storm.previewClip(Storm.clipSecondsFromSlider(it)) },
            onValueChangeFinished = { storm.pickClip(storm.clipSeconds) },
            valueRange = Storm.MIN_CLIP_S.toFloat()..Storm.MAX_CLIP_S.toFloat(),
            steps = Storm.MAX_CLIP_S - Storm.MIN_CLIP_S - 1,
            modifier = Modifier.weight(1f),
        )
        Text("${storm.clipSeconds} s", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
        // The app volume (the selected tile / the centre plays at it; the stick sets the same level).
        VolumeSlider(storm.volumeLevel, onChange = { storm.setVolume(it) }, modifier = Modifier.weight(1f))
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        RowLabel("Now", Modifier.width(LABEL_W))
        Text(
            storm.selectedTitle ?: if (storm.layout == StormLayoutMode.FOCUS) "Trigger a small tile to swap" else "Point + trigger a tile",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (room != null) {
          RowLabel("Behind", Modifier.width(LABEL_W))
          ChipRow(RoomView.entries, room.view, { it.label }, { room.select(it) }, Modifier.weight(1f))
        }
      }
      val toy = storm.vacuGlide
      val sync = storm.sync
      if (toy != null && sync != null) VacuGlideToyRow(toy, sync, LABEL_W)
      Spacer(Modifier.weight(1f))
      Divider()
      if (storm.timerOpen && sync != null) {
        // The Timer row takes the actions' place (B or Done closes it).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
          RowLabel("Timer", Modifier.width(LABEL_W))
          TimerChips(sync, Modifier.weight(1f)) { storm.timerOpen = false }
          QuietButton("Done", onClick = { storm.timerOpen = false })
        }
      } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        PrimaryButton(if (storm.paused) "Play all" else "Pause all", onClick = { storm.holdAll(!storm.paused) })
        SecondaryButton("Shuffle now", onClick = { storm.shuffleNow() })
        SecondaryButton("Solo", onClick = { storm.solo() }, enabled = storm.selected != null)
        if (sync != null) SecondaryButton("Timer", onClick = { storm.timerOpen = true })
        Spacer(Modifier.weight(1f))
        SecondaryButton("Library", onClick = { storm.toggleLibrary() })
      }
    }
   }
  }
}

@Composable
private fun Divider() {
  HorizontalDivider(color = Palette.outline)
}

private val LABEL_W = 68.dp
