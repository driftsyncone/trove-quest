package app.trove.quest.storm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.trove.quest.api.Facet
import app.trove.quest.api.FacetType
import app.trove.quest.hold.HoldState
import app.trove.quest.hold.LocalPanelHeld
import app.trove.quest.ui.FacetChip
import app.trove.quest.ui.detailMeta
import app.trove.quest.ui.fmtDuration
import app.trove.quest.ui.theme.Chip
import app.trove.quest.ui.theme.OutlineButton
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.QuietButton
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.RowLabel
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.theme.StashTheme

/**
 * The scene-info panel right of the Focus centre: the centre's title, meta (date, duration, ...),
 * studio / performers / tags and markers. A facet chip re-Storms with that facet added; the small
 * "Library" beside it opens the library filtered by it. A marker seeks the centre. Collapses to a
 * slim tab (remembered); Hide removes it (Menu / B bring it back with the Storm controls).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StormInfoPanel(storm: StormManager) {
  StashTheme {
    CompositionLocalProvider(LocalPanelHeld provides HoldState.any) {
      if (storm.infoCollapsed) {
        // The slim tab sits at the left edge of the (wider) quad; the rest is transparent.
        Row(Modifier.fillMaxSize()) {
          Column(
              Modifier.width(TAB_W).fillMaxHeight().clip(RoundedCornerShape(Radius.control)).background(Palette.panel).padding(vertical = Space.sm),
              verticalArrangement = Arrangement.spacedBy(Space.sm),
              horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            QuietButton("Info", onClick = { storm.collapseInfo(false) })
            QuietButton("Hide", onClick = { storm.hideInfo() }, color = Palette.muted)
          }
        }
        return@CompositionLocalProvider
      }
      val scene = storm.infoScene
      val card = storm.infoCard
      Column(
          Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.controlsPanel)).background(Palette.panel).padding(Space.md),
          verticalArrangement = Arrangement.spacedBy(Space.sm),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
          RowLabel("Scene", Modifier.weight(1f))
          SecondaryButton("Collapse ›", onClick = { storm.collapseInfo(true) })
          OutlineButton("Hide", onClick = { storm.hideInfo() })
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState(), enabled = !LocalPanelHeld.current),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
          if (card == null) {
            Text("No scene in the centre.", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            return@Column
          }
          Text(card.title, style = MaterialTheme.typography.titleMedium)
          if (scene != null) {
            Text(detailMeta(scene), color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
          } else {
            // The card already has date and duration; the rest follows with the details.
            val head = listOfNotNull(card.date?.takeIf { it.isNotEmpty() }, fmtDuration(card.duration)).joinToString(" · ")
            Text(head, color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            if (storm.infoError != null) {
              Text(storm.infoError ?: "", color = Palette.errorText, style = MaterialTheme.typography.bodyMedium)
              QuietButton("Try again", onClick = { storm.retryInfo() })
            } else {
              Text("Loading details…", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            }
          }
          if (scene != null) {
            val facets = StormInfo.facets(scene)
            for (type in listOf(FacetType.STUDIO, FacetType.PERFORMER, FacetType.TAG)) {
              val of = facets.filter { it.type == type }
              if (of.isEmpty()) continue
              FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                for (f in of) FacetItem(f, storm)
              }
            }
            if (scene.markers.isNotEmpty()) {
              RowLabel("Markers")
              FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                for (m in scene.markers) {
                  Chip(m.title, selected = false, onClick = { storm.seekCentre((m.seconds * 1000).toLong()) }, kind = fmtDuration(m.seconds), kindColor = Palette.accent)
                }
              }
            }
          }
        }
        storm.error?.let { Text(it, color = Palette.errorText, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
      }
    }
  }
}

/** A facet chip (tap = re-Storm with it) and its small secondary "Library" action. */
@Composable
private fun FacetItem(f: Facet, storm: StormManager) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    FacetChip(f, onClick = { storm.restormWith(f) })
    QuietButton("Library", onClick = { storm.showInLibrary(f) }, color = Palette.muted)
  }
}

private val TAB_W = 84.dp
