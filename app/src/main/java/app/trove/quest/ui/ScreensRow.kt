package app.trove.quest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.trove.quest.player.PlayerManager
import app.trove.quest.posture.Posture
import app.trove.quest.ui.theme.Chip
import app.trove.quest.ui.theme.RowLabel
import app.trove.quest.ui.theme.SecondaryButton
import app.trove.quest.ui.theme.Space

/**
 * The wall header's row for how the screens sit round you, shown while screens are open: the
 * posture presets (README "Posture and bringing screens to you"), Look to focus (the screen you face
 * for a moment gets sound and the original stream; trigger-click still works) and "Bring screens to
 * me" (turn the whole layout to where you face now), plus Face me (every screen kept square to your
 * eyes) and Tidy (pack the screens into a neat grid round the view).
 */
@Composable
fun ScreensRow(players: PlayerManager) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
      RowLabel("Posture")
      for (p in Posture.entries) Chip(p.label, selected = players.posture.current == p, onClick = { players.posture.apply(p) })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
      RowLabel("Screens")
      Chip("Face me", selected = players.arrange.faceMe.enabled, onClick = { players.arrange.faceMe.set(!players.arrange.faceMe.enabled) })
      Chip("Look to focus", selected = players.gaze.enabled, onClick = { players.gaze.toggle(!players.gaze.enabled) })
      Spacer(Modifier.weight(1f))
      SecondaryButton("Tidy", onClick = { players.arrange.tidy() })
      SecondaryButton("Bring screens to me", onClick = { players.posture.bringToMe() })
    }
  }
}
