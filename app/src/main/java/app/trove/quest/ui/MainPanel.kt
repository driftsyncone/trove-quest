package app.trove.quest.ui

import androidx.compose.foundation.background
import app.trove.quest.hold.HoldState
import app.trove.quest.hold.LocalPanelHeld
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import app.trove.quest.api.TroveApi
import app.trove.quest.drag.CardDrag
import app.trove.quest.hold.HoldHint
import app.trove.quest.hold.holdHintText
import app.trove.quest.player.PlayerManager
import app.trove.quest.ui.theme.GrabHint
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.Space
import app.trove.quest.ui.theme.StashTheme
import app.trove.quest.ui.theme.StateMessage
import app.trove.quest.update.UpdateModel

/**
 * The one UI panel: login form, or the wall once logged in. The login lives in LoginScreen.kt, the
 * wall (header, grid, states) in Wall.kt, the cards in SceneCard.kt.
 */
@Composable
fun MainPanel(
    model: AppModel,
    api: TroveApi,
    images: ImageLoader,
    players: PlayerManager,
    updates: UpdateModel,
    wallHint: HoldHint,
    drag: CardDrag,
    /** The wall is held with grip ([app.trove.quest.hold.HoldTarget.held]): no scrolling then. */
    held: () -> Boolean = { false },
) {
  CompositionLocalProvider(LocalPanelHeld provides (held() || HoldState.any)) {
  StashTheme {
    ProvidePanelIme {
      Box(
          modifier =
              Modifier.fillMaxSize()
                  .clip(RoundedCornerShape(Radius.wallPanel))
                  .background(Palette.panel)
                  // The bottom strip is reserved for the grab hint, so nothing is drawn under it.
                  .padding(start = Space.xl, end = Space.xl, top = Space.xl - Space.xs, bottom = GRAB_STRIP),
      ) {
        when (model.screen) {
          Screen.STARTING -> StateMessage("Connecting…", loading = true)
          Screen.LOGIN -> LoginScreen(model, Modifier.align(Alignment.Center))
          Screen.WALL -> Wall(model, api, images, players, updates, drag)
        }
        // Handle pill in the bottom strip: shows the panel can be moved (grip anywhere on it).
        GrabHint(Modifier.align(Alignment.BottomCenter).offset(y = GRAB_STRIP - Space.sm), hint = holdHintText(wallHint))
      }
    }
  }
  }
}

private val GRAB_STRIP = 44.dp
