package app.trove.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import app.trove.quest.api.Entity
import app.trove.quest.api.Section
import app.trove.quest.api.TroveApi
import app.trove.quest.hold.LocalPanelHeld
import app.trove.quest.player.PlayerManager
import app.trove.quest.storm.StormButton
import app.trove.quest.storm.StormInfo
import app.trove.quest.ui.theme.Badge
import app.trove.quest.ui.theme.Chip
import app.trove.quest.ui.theme.OutlineButton
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.PrimaryButton
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Size
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.theme.StateMessage

// The wall's library sections, like Stash's own tabs: Scenes, Performers, Studios, Tags. Each entity
// section is a grid of cards with a picture, name and scene count; tapping one opens its page (the
// Scenes section filtered by it, under a header). State and rules: AppModel, SectionRules.

/** Scenes · Performers · Studios · Tags, right under the top bar. */
@Composable
fun SectionSwitcher(model: AppModel, modifier: Modifier = Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
    for (s in Section.entries) {
      Chip(s.label, selected = s == (model.page?.origin ?: model.section), onClick = { model.selectSection(s) })
    }
  }
}

/** One performer, studio or tag: picture (portrait for performers), name, "N scenes · ...". */
@Composable
fun EntityCard(section: Section, e: Entity, api: TroveApi, images: ImageLoader, onClick: () -> Unit) {
  val shape = RoundedCornerShape(Radius.card)
  Column(
      Modifier.clip(shape)
          .background(Palette.surface)
          .border(Size.borderThin, Palette.outline.copy(alpha = 0.4f), shape)
          .clickable(role = Role.Button, onClick = onClick),
  ) {
    Box(Modifier.fillMaxWidth().aspectRatio(SectionRules.imageAspect(section)).background(Palette.raised)) {
      val url = api.entityThumbUrl(e, if (section == Section.PERFORMERS) 320 else 480)
      if (url != null) {
        AsyncImage(
            model = url,
            imageLoader = images,
            contentDescription = null,
            // Studio logos are shown whole; portraits and tag pictures fill the frame.
            contentScale = if (section == Section.STUDIOS) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(if (section == Section.STUDIOS) Space.md else 0.dp),
        )
      } else {
        Initial(e.name, Modifier.fillMaxSize())
      }
      if (e.favorite) Badge("★", Modifier.align(Alignment.TopEnd).padding(Space.sm))
    }
    Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
      Text(e.name, minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      Text(
          SectionRules.cardMeta(section, e).ifEmpty { " " },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall,
          color = Palette.muted,
      )
    }
  }
}

/** Placeholder for an entity without a picture: its first letter. */
@Composable
private fun Initial(name: String, modifier: Modifier) {
  Box(modifier, contentAlignment = Alignment.Center) {
    Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.displaySmall, color = Palette.muted)
  }
}

/** The grid of the Performers / Studios / Tags section, with its loading, empty and error states. */
@Composable
fun EntityContent(model: AppModel, api: TroveApi, images: ImageLoader, modifier: Modifier) {
  val section = model.section
  val q = model.entityQuery
  val gridState = rememberLazyGridState()
  LaunchedEffect(model.entities) { gridState.scrollToItem(0) }
  when {
    model.entities.isEmpty() && model.entityLoading -> StateMessage("Loading ${section.label.lowercase()}…", modifier, loading = true)
    model.entities.isEmpty() && model.entityError != null ->
        StateMessage(
            "Couldn't load ${section.label.lowercase()}",
            modifier,
            message = model.entityError,
            error = true,
            action = { PrimaryButton("Try again", onClick = { model.reloadEntities() }) },
        )
    model.entities.isEmpty() ->
        StateMessage(
            SectionRules.emptyTitle(section),
            modifier,
            message = if (q.q.isNotEmpty() || q.favorite) "Try other words, or show everyone." else "Nothing here yet.",
            action = { if (q.q.isNotEmpty() || q.favorite) SecondaryButton("Show all", onClick = { model.clearQueryText() }) },
        )
    else ->
        Column(modifier) {
          model.entityError?.let { err ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
              Text(err, color = Palette.errorText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f))
              OutlineButton("Try again", onClick = { model.reloadEntities() })
            }
          }
          LazyVerticalGrid(
              columns = GridCells.Fixed(SectionRules.columns(section)),
              state = gridState,
              userScrollEnabled = !LocalPanelHeld.current,
              horizontalArrangement = Arrangement.spacedBy(Space.md),
              verticalArrangement = Arrangement.spacedBy(Space.md),
              modifier = Modifier.weight(1f).alpha(if (model.entityLoading) 0.5f else 1f),
          ) {
            items(model.entities, key = { it.id }) { e -> EntityCard(section, e, api, images) { model.openEntity(e) } }
          }
        }
  }
}

/**
 * Header of a performer / studio / tag page: picture, name, facts, then Play random, Storm (just
 * this one: [StormInfo.restormQuery]) and back to the grid it came from. The scenes grid follows.
 */
@Composable
fun EntityPageHeader(model: AppModel, page: EntityPage, api: TroveApi, images: ImageLoader, players: PlayerManager) {
  val e = page.entity
  val shape = RoundedCornerShape(Radius.card)
  Row(
      Modifier.fillMaxWidth().clip(shape).background(Palette.surface).border(Size.borderThin, Palette.outline.copy(alpha = 0.4f), shape).padding(Space.md),
      horizontalArrangement = Arrangement.spacedBy(Space.lg),
  ) {
    val wide = page.origin != Section.PERFORMERS
    Box(Modifier.size(width = if (wide) 192.dp else 96.dp, height = 108.dp + if (wide) 0.dp else 36.dp).clip(RoundedCornerShape(Radius.control)).background(Palette.raised)) {
      val url = api.entityThumbUrl(e, 320)
      if (url != null) {
        AsyncImage(
            model = url,
            imageLoader = images,
            contentDescription = null,
            contentScale = if (page.origin == Section.STUDIOS) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
      } else {
        Initial(e.name, Modifier.fillMaxSize())
      }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
      Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(e.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (e.favorite) Badge("★ Favorite")
      }
      Text(SectionRules.pageFacts(e), style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (e.aliases.isNotEmpty()) {
        Text("Also known as ${e.aliases.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      (e.details ?: e.description)?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
      Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(
            if (players.randomBusy) "Play random…" else "Play random",
            onClick = {
              players.randomSource = model.query
              players.openRandom()
            },
            enabled = !players.randomBusy,
        )
        // Just this performer / studio / tag, whatever the wall had before.
        StormButton(players.storm, StormInfo.restormQuery(model.query, page.facet))
        OutlineButton(SectionRules.backLabel(page.origin), onClick = { model.closePage() })
      }
    }
  }
}
