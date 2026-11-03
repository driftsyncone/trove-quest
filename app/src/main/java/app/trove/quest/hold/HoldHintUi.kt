package app.trove.quest.hold

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/** The hint to show now, or null; each [HoldHint.show] keeps it up for [HoldHint.SHOW_MS]. */
@Composable
fun holdHintText(hint: HoldHint): String? {
  val seq = hint.seq
  LaunchedEffect(seq) {
    delay(HoldHint.SHOW_MS)
    hint.clear(seq)
  }
  return hint.text
}
