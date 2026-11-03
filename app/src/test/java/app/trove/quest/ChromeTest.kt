package app.trove.quest

import app.trove.quest.player.ChromeRules
import app.trove.quest.player.ChromeState
import app.trove.quest.player.ScreenHandle
import app.trove.quest.player.ScreenSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which screen shows its control panel (focus x count x hidden flag) and the auto-hide clock. */
class ChromeTest {
  /** Applies the session's rule: audible = primary. */
  private class Screen(val hiddenFlag: () -> Boolean) : ScreenHandle {
    var audible = true
    var controls = true
    override val isSphere = false
    override val canDowngrade = false
    override val heavyNow = false

    override fun applyFocus(audible: Boolean, highlighted: Boolean) {
      this.audible = audible
      refresh()
    }

    fun refresh() {
      controls = ChromeRules.showsControls(audible, hiddenFlag())
    }

    override fun applyQuality(original: Boolean) {}

    override fun setSuspended(suspended: Boolean) {}
  }

  private var hidden = false
  private fun screen() = Screen { hidden }

  @Test
  fun aLoneScreenShowsItsControls() {
    val set = ScreenSet<Screen>(6) { 0L }
    val a = screen().also { set.add(it) }
    assertTrue(a.controls)
  }

  @Test
  fun onlyTheFocusedScreenShowsAndTheControlsFollowFocus() {
    val set = ScreenSet<Screen>(6) { 0L }
    val a = screen().also { set.add(it) }
    val b = screen().also { set.add(it) }
    val c = screen().also { set.add(it) }
    assertEquals(listOf(false, false, true), listOf(a, b, c).map { it.controls })
    set.focus(a)
    assertEquals(listOf(true, false, false), listOf(a, b, c).map { it.controls })
    set.remove(a) // closing the focused one hands focus (and the panel) to the previous
    assertTrue((set.focused as Screen).controls)
    assertEquals(1, listOf(b, c).count { it.controls })
  }

  @Test
  fun hiddenFlagHidesTheFocusedScreenAndFocusMovesKeepItHidden() {
    val set = ScreenSet<Screen>(6) { 0L }
    val a = screen().also { set.add(it) }
    val b = screen().also { set.add(it) }
    hidden = true
    a.refresh(); b.refresh()
    assertFalse(a.controls || b.controls)
    set.focus(a)
    assertFalse(a.controls || b.controls)
    hidden = false
    a.refresh(); b.refresh()
    assertTrue(a.controls)
    assertFalse(b.controls)
  }

  @Test
  fun autoHideRule() {
    assertFalse(ChromeRules.autoHideDue(hidden = false, lastTouchMs = 0, nowMs = 100_000, secs = 0)) // off
    assertFalse(ChromeRules.autoHideDue(false, 0, 14_999, 15))
    assertTrue(ChromeRules.autoHideDue(false, 0, 15_000, 15))
    assertFalse(ChromeRules.autoHideDue(hidden = true, lastTouchMs = 0, nowMs = 99_000, secs = 15)) // already hidden
  }

  @Test
  fun stateHidesOnTickAfterIdleAndTouchRestartsTheClock() {
    var changes = 0
    var saved = -1
    val st = ChromeState(15, { saved = it }, { changes++ })
    st.touch(1_000)
    assertFalse(st.tick(15_999))
    st.touch(10_000)
    assertFalse(st.tick(20_000))
    assertTrue(st.tick(25_000))
    assertTrue(st.hidden)
    assertEquals(1, changes)
    assertFalse(st.tick(60_000)) // nothing more to hide
    st.show(61_000)
    assertFalse(st.hidden)
    assertEquals(2, changes)
    st.show(62_000)
    assertEquals(2, changes) // no change, no callback
    assertEquals(15, saved.let { if (it == -1) 15 else it })
  }

  @Test
  fun autoHideIsOffByDefaultAndCycles() {
    val st = ChromeState(0, {}, {})
    assertEquals(0, st.autoHideSecs)
    assertFalse(st.tick(1_000_000))
    val seen = mutableListOf<Int>()
    repeat(5) {
      st.cycleAutoHide(0)
      seen += st.autoHideSecs
    }
    assertEquals(listOf(15, 30, 60, 0, 15), seen)
    assertEquals(0, ChromeState(7, {}, {}).autoHideSecs) // an unknown saved value falls back to off
  }

  @Test
  fun toggleFlipsTheFlag() {
    val st = ChromeState(0, {}, {})
    st.toggle(0)
    assertTrue(st.hidden)
    st.toggle(1)
    assertFalse(st.hidden)
  }
}
