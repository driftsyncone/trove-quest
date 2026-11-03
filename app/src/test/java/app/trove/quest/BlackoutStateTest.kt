package app.trove.quest

import app.trove.quest.sync.BlackoutState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackoutStateTest {
  @Test
  fun onOffReportsChangesOnly() {
    val b = BlackoutState()
    assertFalse(b.on)
    assertTrue(b.set(true, 3))
    assertTrue(b.on)
    assertEquals(3L, b.seq)
    assertFalse("same state again", b.set(true, 4))
    assertEquals(4L, b.seq)
    assertTrue(b.set(false))
    assertFalse(b.on)
    assertFalse(b.set(false))
  }

  @Test
  fun volumeIsSilentWhileBlackAndRestoredExactlyAfter() {
    val b = BlackoutState()
    assertEquals(1f, b.volume(audible = true), 0f)
    assertEquals(0f, b.volume(audible = false), 0f)
    b.set(true)
    assertEquals(0f, b.volume(audible = true), 0f)
    assertEquals(0f, b.volume(audible = false), 0f)
    b.set(false)
    assertEquals(1f, b.volume(audible = true), 0f)
    assertEquals(0f, b.volume(audible = false), 0f)
  }

  @Test
  fun wearerEscapeNeedsBothGripsForTwoSecondsWithoutABreak() {
    val b = BlackoutState()
    assertFalse(b.gripTick(0, true))
    assertFalse(b.gripTick(1000, true))
    assertFalse(b.gripTick(1999, true))
    assertTrue("2 s reached", b.gripTick(2000, true))
    assertFalse("fires once per hold", b.gripTick(2100, true))
    assertFalse(b.gripTick(9000, true))
  }

  @Test
  fun theEscapeWorksInEveryState() {
    // Not black, black, black again: the timer does not look at Black Out.
    val b = BlackoutState()
    assertTrue(b.gripTick(0, true).not() && b.gripTick(2000, true))
    b.gripTick(2100, false)
    b.set(true)
    b.gripTick(3000, true)
    assertTrue(b.gripTick(5000, true))
    assertTrue("the escape did not touch Black Out itself", b.on)
  }

  @Test
  fun aBreakRestartsTheHoldAndAReleaseArmsItAgain() {
    val b = BlackoutState()
    b.gripTick(0, true)
    b.gripTick(1500, true)
    assertFalse(b.gripTick(1600, false)) // released one grip
    assertFalse(b.gripTick(1700, true)) // counts again from here
    assertFalse(b.gripTick(3600, true))
    assertTrue(b.gripTick(3700, true))
    assertFalse(b.gripTick(3800, false))
    assertFalse(b.gripTick(3900, true))
    assertTrue("a second escape after releasing", b.gripTick(5900, true))
  }

  @Test
  fun aGripAlreadyHeldCountsFromTheFirstTickSeenNotFromBefore() {
    val b = BlackoutState()
    assertFalse(b.gripTick(5000, true))
    assertFalse(b.gripTick(6999, true))
    assertTrue(b.gripTick(7000, true))
  }

  @Test
  fun oneGripAloneNeverCounts() {
    val b = BlackoutState()
    for (t in 0..10_000 step 100) assertFalse(b.gripTick(t.toLong(), false))
  }

  @Test
  fun blackoutChangesDoNotInheritOrResetAHold() {
    val b = BlackoutState()
    b.gripTick(0, true)
    b.gripTick(1500, true)
    b.set(true)
    assertTrue("the hold carries on across a Black Out change", b.gripTick(2000, true))
  }

  @Test
  fun linkLossLifts() {
    val b = BlackoutState()
    assertFalse("nothing to lift", b.linkLost())
    b.set(true)
    assertTrue(b.linkLost())
    assertFalse(b.on)
  }

  @Test
  fun customHoldTime() {
    val b = BlackoutState(holdMs = 500)
    b.gripTick(0, true)
    assertTrue(b.gripTick(500, true))
  }
}
