package app.trove.quest.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.trove.quest.sync.SyncIndicator
import app.trove.quest.sync.ToyResume
import app.trove.quest.ui.theme.OutlineButton
import app.trove.quest.ui.theme.Palette
import app.trove.quest.ui.theme.Radius
import app.trove.quest.ui.theme.Size
import app.trove.quest.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * "Start toy" / "Resume toy" for the wearer: only there while [ToyResume.of] offers it (link up, the
 * server latched and willing to take a resume from the headset). A partner's STOP ALL (partner required
 * off) is a 2 s hold so it cannot happen by accident. Nothing changes locally: the server's next status
 * is the truth.
 */
@Composable
fun ResumeToyButton(sync: SyncIndicator, modifier: Modifier = Modifier) {
  val offer = ToyResume.of(sync.state) ?: return
  if (!offer.hold) {
    OutlineButton(offer.label, onClick = { sync.onResume(false) }, modifier = modifier)
    return
  }
  var heldMs by remember { mutableLongStateOf(0L) }
  var pressed by remember { mutableStateOf(false) }
  LaunchedEffect(pressed) {
    if (!pressed) {
      heldMs = 0
      return@LaunchedEffect
    }
    val t0 = System.nanoTime()
    while (true) {
      delay(50)
      heldMs = (System.nanoTime() - t0) / 1_000_000
      if (heldMs >= ToyResume.HOLD_MS) {
        sync.onResume(true)
        pressed = false
        return@LaunchedEffect
      }
    }
  }
  val shape = RoundedCornerShape(Radius.control)
  Box(
      modifier =
          modifier.heightIn(min = Size.target).widthIn(min = 200.dp).clip(shape).border(1.dp, Palette.accent, shape).pointerInput(Unit) {
            awaitEachGesture {
              awaitFirstDown()
              pressed = true
              waitForUpOrCancellation()
              pressed = false
            }
          },
      contentAlignment = Alignment.Center,
  ) {
    Text(
        if (pressed) "Keep holding… ${((ToyResume.HOLD_MS - heldMs).coerceAtLeast(0) + 999) / 1000} s" else offer.label,
        style = MaterialTheme.typography.labelLarge,
        color = Palette.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
    )
  }
}
