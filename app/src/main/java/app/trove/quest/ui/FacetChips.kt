package app.trove.quest.ui

import androidx.compose.foundation.layout.Arrangement
import app.trove.quest.hold.LocalPanelHeld
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.trove.quest.api.Facet
import app.trove.quest.api.FacetType
import app.trove.quest.api.Suggestion
import app.trove.quest.ui.theme.Chip
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.QuietButton
import app.trove.quest.ui.theme.Space

// Facet search on the wall (web/src/main.ts setupSearch + chips). Chip rows instead of the web's
// dropdown list: popups are unreliable in Spatial panels (see ChipRow).

/** Colour of a facet's kind label, so performer / studio / tag read apart at a glance. */
fun facetColor(type: FacetType): Color =
    when (type) {
      FacetType.PERFORMER -> Palette.performer
      FacetType.STUDIO -> Palette.studio
      FacetType.TAG -> Palette.tag
    }

/** Suggestions for what is typed: tap one to filter by it. Shown only while there are some. */
@Composable
fun SuggestionRow(items: List<Suggestion>, onPick: (Suggestion) -> Unit, modifier: Modifier = Modifier) {
  LazyRow(modifier = modifier, userScrollEnabled = !LocalPanelHeld.current, horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
    items(items, key = { "${it.type}:${it.id}" }) { s ->
      val type = FacetType.fromId(s.type) ?: return@items
      Chip(s.name, selected = false, onClick = { onPick(s) }, kind = type.id, kindColor = facetColor(type), trailing = s.count.toString())
    }
  }
}

/** One performer / studio / tag chip (scene details): tap = filter the wall by it. */
@Composable
fun FacetChip(facet: Facet, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Chip(facet.name, selected = false, onClick = onClick, modifier = modifier, kind = facet.type.id, kindColor = facetColor(facet.type))
}

/**
 * What narrows the wall besides the saved-filter tab: the applied search text and the picked facets,
 * each removable with its ✕; "Clear all" when there are two or more.
 */
@Composable
fun ActiveChipRow(
    query: String,
    facets: List<Facet>,
    onClearQuery: () -> Unit,
    onRemove: (Facet) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val count = facets.size + if (query.isNotEmpty()) 1 else 0
  if (count == 0) return
  LazyRow(
      userScrollEnabled = !LocalPanelHeld.current,
      modifier = modifier,
      horizontalArrangement = Arrangement.spacedBy(Space.sm),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (query.isNotEmpty()) {
      item(key = "q") { Chip("“$query”", selected = true, onClick = onClearQuery, trailing = "✕") }
    }
    items(facets, key = { "${it.type}:${it.id}" }) { f ->
      Chip(f.name, selected = true, onClick = { onRemove(f) }, kind = f.type.id, trailing = "✕")
    }
    if (count > 1) item(key = "clear") { QuietButton("Clear all", onClick = onClearAll) }
  }
}
