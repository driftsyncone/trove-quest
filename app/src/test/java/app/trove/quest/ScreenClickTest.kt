package app.trove.quest

import app.trove.quest.player.ScreenClick
import app.trove.quest.player.ScreenClick.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenClickTest {
  @Test
  fun unfocusedScreenOnlyTakesFocus() {
    assertEquals(Action.FOCUS, ScreenClick.decide(focused = false, gripHeld = false))
  }

  @Test
  fun focusedScreenTogglesPlayback() {
    // Also a lone screen, which is always focused.
    assertEquals(Action.TOGGLE, ScreenClick.decide(focused = true, gripHeld = false))
  }

  @Test
  fun gripGrabStartNeverClicks() {
    assertEquals(Action.NONE, ScreenClick.decide(focused = true, gripHeld = true))
    assertEquals(Action.NONE, ScreenClick.decide(focused = false, gripHeld = true))
  }

  @Test
  fun repeatedEventInsideTheGapIsIgnored() {
    assertEquals(Action.NONE, ScreenClick.decide(true, false, sinceLastMs = ScreenClick.MIN_GAP_MS - 1))
    // The click that just focused the screen does not also toggle it.
    assertEquals(Action.NONE, ScreenClick.decide(true, false, sinceLastMs = 10))
    assertEquals(Action.TOGGLE, ScreenClick.decide(true, false, sinceLastMs = ScreenClick.MIN_GAP_MS))
  }
}
