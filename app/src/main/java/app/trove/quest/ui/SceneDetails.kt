package app.trove.quest.ui

import androidx.compose.foundation.background
import app.trove.quest.hold.LocalPanelHeld
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import app.trove.quest.api.Facet
import app.trove.quest.api.FacetType
import app.trove.quest.api.Ref
import app.trove.quest.api.SceneScripts
import app.trove.quest.api.TroveApi
import app.trove.quest.sync.ScriptPicker
import app.trove.quest.sync.ScriptRules
import app.trove.quest.ui.theme.RowLabel
import app.trove.quest.ui.theme.Badge
import app.trove.quest.ui.theme.Chip
import app.trove.quest.ui.theme.OutlineButton
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.PrimaryButton
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Space

/**
 * A scene's details in place of the wall's grid (web: the detail dialog without the video): meta,
 * studio / performers / tags (tap one to filter the wall by it), description and markers (tap one to
 * play from there). Play offers "Resume at mm:ss" when the scene was left part way. [onPlay] gets the start time and whether to open a new screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SceneDetails(
    detail: SceneDetail,
    api: TroveApi,
    images: ImageLoader,
    canOpenNew: Boolean,
    /** Where this scene was left on this headset (player/ResumePoints.kt), if anywhere. */
    resumeMs: Long?,
    onPlay: (startMs: Long, newScreen: Boolean) -> Unit,
    onFacet: (Facet) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Script variants: the picker shared with the VacuGlide panel (null = none), and "ask the server again". */
    scripts: ScriptPicker? = null,
    onScriptsChanged: () -> Unit = {},
) {
  val card = detail.card
  val s = detail.scene
  Row(modifier = modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
    // Left: back, picture, and the ways to play it (stacked, full width: big ray targets).
    Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
      OutlineButton("‹ Back to library", onClick = onBack, modifier = Modifier.fillMaxWidth())
      Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(Radius.card)).background(Color.Black)) {
        AsyncImage(
            model = api.thumbUrl(card),
            imageLoader = images,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Badge(fmtDuration(card.duration), Modifier.align(Alignment.BottomEnd).padding(Space.sm))
      }
      if (resumeMs != null) {
        PrimaryButton("Resume at ${fmtDuration(resumeMs / 1000.0)}", onClick = { onPlay(resumeMs, false) }, modifier = Modifier.fillMaxWidth(), large = true)
        SecondaryButton("From start", onClick = { onPlay(0L, false) }, modifier = Modifier.fillMaxWidth())
      } else {
        PrimaryButton("Play", onClick = { onPlay(0L, false) }, modifier = Modifier.fillMaxWidth(), large = true)
      }
      if (canOpenNew) SecondaryButton("New screen", onClick = { onPlay(0L, true) }, modifier = Modifier.fillMaxWidth())
    }

    Column(
        Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState(), enabled = !LocalPanelHeld.current),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
      Text(card.title, style = MaterialTheme.typography.titleLarge)
      when {
        s != null -> Text(detailMeta(s), color = Palette.muted, style = MaterialTheme.typography.bodyLarge)
        detail.error != null -> {
          Text(detail.error, color = Palette.errorText, style = MaterialTheme.typography.bodyLarge)
          OutlineButton("Try again", onClick = onRetry)
        }
        else ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
              CircularProgressIndicator(Modifier.size(32.dp))
              Text("Loading details…", color = Palette.muted, style = MaterialTheme.typography.bodyLarge)
            }
      }
      if (s == null) return@Column

      val list = detail.scripts
      if (scripts != null && list != null) ScriptRow(card.id, list, scripts, onScriptsChanged)

      val people = listOfNotNull(s.studio?.let { FacetType.STUDIO to it }) + s.performers.map { FacetType.PERFORMER to it }
      if (people.isNotEmpty()) FacetFlow(people, onFacet)
      if (s.tags.isNotEmpty()) FacetFlow(s.tags.map { FacetType.TAG to it }, onFacet)

      s.details?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }

      if (s.markers.isNotEmpty()) {
        Text("Markers", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
          for (m in s.markers) {
            Chip(m.title, selected = false, onClick = { onPlay((m.seconds * 1000).toLong(), false) }, kind = fmtDuration(m.seconds), kindColor = Palette.accent)
          }
        }
      }
      s.file?.let {
        Text(
            it.name,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.muted,
            modifier = Modifier.padding(top = Space.sm),
        )
      }
    }
  }
}

/**
 * "Script" and a chip per funscript of the scene (when it has several): the server's choice is selected, a tap
 * sends a new one over the sync socket and shows "Loading script…" until the server has it on the toy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptRow(sceneId: String, list: SceneScripts, picker: ScriptPicker, refresh: () -> Unit) {
  val options = list.scripts.filter { it.hasContent || it.id == list.chosen }
  if (options.size < 2) return
  // Ask again once the server answered a pick, and every second while it still loads one onto the toy.
  LaunchedEffect(picker.answers) { if (picker.answers > 0) refresh() }
  LaunchedEffect(list) {
    picker.confirmed(sceneId, list.loading)
    if (list.loading) {
      kotlinx.coroutines.delay(1000)
      refresh()
    }
  }
  val chosen = picker.chosen(sceneId, list.chosen)
  Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
      RowLabel("Script")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        for (o in options) {
          Chip(o.label.ifBlank { o.id }, selected = o.id == chosen, onClick = { picker.pick(sceneId, o.id) })
        }
      }
    }
    picker.line(sceneId, list.loading)?.let {
      Text(it, color = if (it == ScriptRules.LOADING) Palette.accent else Palette.errorText, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetFlow(items: List<Pair<FacetType, Ref>>, onFacet: (Facet) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
    for ((type, ref) in items) {
      val facet = Facet(type, ref.id, ref.name)
      FacetChip(facet, onClick = { onFacet(facet) })
    }
  }
}
