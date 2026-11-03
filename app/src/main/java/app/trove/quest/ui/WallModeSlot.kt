package app.trove.quest.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import app.trove.quest.api.SceneQuery
import app.trove.quest.storm.StormButton
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.player.PlayerManager

/**
 * The slot in the wall header's session group (right of the sort controls, after "Open in" / Room /
 * Restore) for buttons that start another way of watching: Random and the Storm wall. Add the button
 * here (a `SecondaryButton` or `Chip` from ui/theme) and nothing else in the header needs to change.
 * It is laid out inside a Row with 8 dp gaps; leave it empty when there is nothing to show.
 */
@Composable
fun WallModeSlot(players: PlayerManager, query: SceneQuery) {
  // Random draws from what the wall shows now (tab, search, facets); all scenes when unfiltered.
  SideEffect { players.randomSource = query }
  SecondaryButton(if (players.randomBusy) "Random…" else "Random", onClick = { players.openRandom() }, enabled = !players.randomBusy)
  // Storm: a wall of cycling clips from the same view (storm/StormPanel.kt).
  StormButton(players.storm, query)
}
