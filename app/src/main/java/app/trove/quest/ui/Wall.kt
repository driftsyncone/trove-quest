package app.trove.quest.ui

import androidx.compose.foundation.layout.Arrangement
import app.trove.quest.hold.LocalPanelHeld
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import app.trove.quest.api.Section
import app.trove.quest.api.TroveApi
import app.trove.quest.drag.CardDrag
import app.trove.quest.player.MultiScreen
import app.trove.quest.player.PlayerManager
import app.trove.quest.ui.theme.Chip
import app.trove.quest.ui.theme.ChipRow
import app.trove.quest.ui.theme.OutlineButton
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.PrimaryButton
import app.trove.quest.ui.theme.QuietButton
import app.trove.quest.ui.theme.RowLabel
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.theme.StateMessage
import app.trove.quest.ui.theme.StatusPill
import app.trove.quest.update.AccountButton
import app.trove.quest.update.UpdateArea
import app.trove.quest.update.UpdateModel

/**
 * The wall. Header in three tiers (docs/ux-audit.md L1): the top bar (brand, search, Back to video,
 * sync, account), the browse bar (saved-filter tabs, then sort and the session group: how cards open,
 * Room, Restore, [WallModeSlot]), then the content, then a footer with the count and the pager.
 */
@Composable
fun Wall(
    model: AppModel,
    api: TroveApi,
    images: ImageLoader,
    players: PlayerManager,
    updates: UpdateModel,
    drag: CardDrag,
) {
  val q = model.query
  // Self-update: the wall opening is one of the (throttled, 6 h) check points.
  LaunchedEffect(Unit) { updates.checkIfDue() }
  val detail = model.detail
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
    TopBar(model, players, updates)
    SectionSwitcher(model, Modifier.fillMaxWidth())

    // As you type: performer / studio / tag chips; tapping one filters by it (with the tab and sort).
    if (model.suggestions.isNotEmpty()) {
      SuggestionRow(model.suggestions, onPick = { model.pickSuggestion(it) }, modifier = Modifier.fillMaxWidth())
    }
    ActiveChipRow(
        query = if (model.section == Section.SCENES) q.q else model.entityQuery.q,
        // A page's own chip is its header; the grids of the other sections have no chips.
        facets = if (model.section == Section.SCENES && model.page == null) q.facets else emptyList(),
        onClearQuery = { model.clearQueryText() },
        onRemove = { model.removeFacet(it) },
        onClearAll = { model.clearChips() },
        modifier = Modifier.fillMaxWidth(),
    )

    UpdateArea(updates, api.baseUrl.toString().trimEnd('/'), players.chrome, onLogout = { model.logout() }, sync = players.sync)

    // Saved filters from Stash; "All" = none. Hidden when the server has none, and outside the Scenes section.
    if (model.filters.isNotEmpty() && model.section == Section.SCENES && model.page == null) {
      ChipRow(
          options = listOf<String?>(null) + model.filters.map { it.id },
          selected = q.filterId,
          label = { id -> if (id == null) "All" else model.filters.firstOrNull { it.id == id }?.name ?: id },
          onPick = { model.selectFilter(it) },
          modifier = Modifier.fillMaxWidth(),
      )
    }

    ToolsRow(model, players)

    // With screens open: posture presets, Look to focus, Bring screens to me.
    if (players.sessions.isNotEmpty()) ScreensRow(players)

    // A performer / studio / tag page: its header over the scenes grid.
    model.page?.takeIf { detail == null }?.let { EntityPageHeader(model, it, api, images, players) }

    if (detail != null) {
      // The server's resume point (play tracking) joins the merged "Resume at".
      LaunchedEffect(detail.scene) { detail.scene?.let { players.resume.noteScene(it) } }
      SceneDetails(
          detail,
          api,
          images,
          canOpenNew = players.sessions.isNotEmpty(),
          resumeMs = players.resume.run { version.let { get(detail.card.id) } },
          // "Play" follows the header's Open in toggle, like tapping the card.
          onPlay = { startMs, newScreen -> players.open(detail.card, newScreen || players.openInNewScreen, startMs) },
          onFacet = { model.addFacet(it) },
          onBack = { model.closeDetails() },
          onRetry = { model.showDetails(detail.card) },
          modifier = Modifier.weight(1f),
          scripts = players.vacuGlide?.scripts,
          onScriptsChanged = { model.loadScripts(detail.card.id) },
      )
    } else {
      if (model.section == Section.SCENES) Content(model, api, images, players, drag, Modifier.weight(1f))
      else EntityContent(model, api, images, Modifier.weight(1f))
      Footer(model)
    }
  }
}

@Composable
private fun TopBar(model: AppModel, players: PlayerManager, updates: UpdateModel) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
    Text("Trove", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = model.searchText,
        onValueChange = { model.typeSearch(it) },
        placeholder = { Text(if (model.section == Section.SCENES) "Search, or type a performer, studio or tag" else "Search ${model.section.label.lowercase()}") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { model.search() }),
        modifier = Modifier.weight(1f).panelIme(),
    )
    OutlineButton("Search", onClick = { model.search() })
    // Shown over a playing screen (Library button), the wall can cover the player's controls;
    // this hides it again from the wall itself.
    if (players.sessions.isNotEmpty() || players.storm.active) PrimaryButton(if (players.sessions.isNotEmpty()) "Back to video" else "Back to Storm", onClick = { players.library.toggle() })
    // Funscript sync link (sync/SyncClient): connected, reconnecting, or STOPPED by the partner.
    StatusTags.sync(players.sync.state)?.let { StatusPill(it) }
    // Start / Resume toy, here as well as under Account: the toy must be startable without opening a video.
    ResumeToyButton(players.sync)
    AccountButton(updates, model.user?.name.orEmpty())
  }
}

/** Sort (one chip that opens the full row), direction, shuffle; then the session group on the right. */
@Composable
private fun ToolsRow(model: AppModel, players: PlayerManager) {
  val q = model.query
  // No explicit sort = server default (the saved filter's sort, else date).
  val entities = model.section != Section.SCENES
  val sortKey = if (entities) model.entityQuery.sort ?: "name" else q.sort ?: if (q.filterId == null) "date" else null
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
    Chip(
        WallLogic.sortSummary(sortKey?.let { SORT_LABELS[it] ?: it } ?: "Filter default"),
        selected = model.sortOpen,
        onClick = { model.sortOpen = !model.sortOpen },
        trailing = if (model.sortOpen) "▴" else "▾",
    )
    Chip(if (model.currentDir == "DESC") "↓ Desc" else "↑ Asc", selected = false, onClick = { model.toggleDir() })
    if (sortKey == "random") Chip("Shuffle", selected = false, onClick = { model.shuffle() })
    if (entities) Chip("★ Favorites", selected = model.entityQuery.favorite, onClick = { model.toggleFavorites() })
    Spacer(Modifier.weight(1f))
    SessionGroup(players)
    // Random and Storm of a page are in its header; the entity grids have none.
    if (!entities && model.page == null) WallModeSlot(players, q)
  }
  if (model.sortOpen) {
    ChipRow(
        options = if (entities) model.entitySorts else model.sorts,
        selected = sortKey,
        label = { SORT_LABELS[it] ?: it },
        onPick = {
          model.selectSort(it)
          model.sortOpen = false
        },
        modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Multi-screen controls on the wall. With screens open: where a tapped card goes ("This screen"
 * replaces the focused one, "New screen" adds one, up to 6). With none open: the Room chip, and
 * "Restore last screens" when a layout was saved (never restored on its own: six videos at once is a
 * choice). What shows is [WallLogic.session].
 */
@Composable
private fun SessionGroup(players: PlayerManager) {
  val n = players.sessions.size
  val c = WallLogic.session(n, players.savedLayout?.screens?.size)
  if (c.openIn) {
    RowLabel("Open in")
    Chip("This screen", selected = !players.openInNewScreen, onClick = { players.openInNewScreen = false })
    Chip(
        WallLogic.newScreenLabel(n, MultiScreen.MAX_SCREENS, players.isFull),
        selected = players.openInNewScreen,
        onClick = { players.openInNewScreen = true },
    )
  }
  if (c.room) {
    // Optional room behind the library on its own (off by default; darkened like View → Behind).
    Chip("Room", selected = players.room.onWall, onClick = { players.room.toggleWall() })
  }
  c.restoreLabel?.let { SecondaryButton(it, onClick = { players.restoreLayout() }) }
}

@Composable
private fun Content(
    model: AppModel,
    api: TroveApi,
    images: ImageLoader,
    players: PlayerManager,
    drag: CardDrag,
    modifier: Modifier,
) {
  val q = model.query
  val filtered = WallLogic.isFiltered(q.q, q.facets.size, q.filterId)
  val gridState = rememberLazyGridState()
  // A new page (or search) starts at its top.
  LaunchedEffect(model.scenes) { gridState.scrollToItem(0) }
  when (WallLogic.content(model.loading, model.wallError, model.scenes.size, filtered)) {
    WallContent.LOADING -> StateMessage("Loading scenes…", modifier, loading = true)
    WallContent.ERROR ->
        StateMessage(
            "Couldn't load the library",
            modifier,
            message = model.wallError,
            error = true,
            action = { PrimaryButton("Try again", onClick = { model.reload() }) },
        )
    WallContent.EMPTY_FILTERED ->
        StateMessage(
            "No scenes match",
            modifier,
            message = "Try fewer words, or remove a filter.",
            action = { SecondaryButton("Show everything", onClick = { model.clearAllFilters() }) },
        )
    WallContent.EMPTY ->
        StateMessage("This library has no scenes yet", modifier, message = "Scenes you add in Stash show up here.")
    WallContent.GRID ->
        Column(modifier) {
          model.wallError?.let { err ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
              Text(err, color = Palette.errorText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f))
              OutlineButton("Try again", onClick = { model.reload() })
            }
          }
          LazyVerticalGrid(
              columns = GridCells.Fixed(4),
              state = gridState,
              // Held with grip, the stick moves the wall (HoldSystem), not the grid.
              userScrollEnabled = !LocalPanelHeld.current,
              horizontalArrangement = Arrangement.spacedBy(Space.md),
              verticalArrangement = Arrangement.spacedBy(Space.md),
              // The old page stays up, dimmed, while the next one loads.
              modifier = Modifier.weight(1f).alpha(if (model.loading) 0.5f else 1f),
          ) {
            items(model.scenes, key = { it.id }) { card ->
              val resumeMs = players.resume.run { version.let { get(card.id) } }
              // Long-press (hold the trigger) = always a new screen, whatever the header toggle says.
              SceneCard(
                  card,
                  api,
                  images,
                  resumeFraction = WallLogic.resumeFraction(resumeMs, card.duration),
                  onInfo = { model.showDetails(card) },
                  drag = drag,
                  onLongClick = {
                    drag.disarm() // a long-press is "new screen", never the start of a drag
                    players.open(card, newScreen = true)
                  },
              ) {
                model.play(card)
              }
            }
          }
        }
  }
}

/** Scene count on the left, the pager on the right. */
@Composable
private fun Footer(model: AppModel) {
  val page = model.currentPage
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
    Text(
        model.countLabel,
        color = Palette.muted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
    )
    OutlineButton("‹ Prev", onClick = { model.goToPage(page - 1) }, enabled = page > 1)
    Text("$page / ${model.pages}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = Space.xs))
    OutlineButton("Next ›", onClick = { model.goToPage(page + 1) }, enabled = page < model.pages)
  }
}
