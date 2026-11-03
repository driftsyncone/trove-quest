package app.trove.quest

import app.trove.quest.hold.AxisGate
import app.trove.quest.hold.HoldRules
import app.trove.quest.hold.StickMode
import app.trove.quest.player.LayoutCodec
import app.trove.quest.player.SavedLayout
import app.trove.quest.player.SavedScreen
import app.trove.quest.api.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldRulesTest {
  private val eps = 1e-4f

  @Test
  fun axisFromDirectionBits() {
    assertEquals(1, HoldRules.axis(positive = true, negative = false))
    assertEquals(-1, HoldRules.axis(positive = false, negative = true))
    assertEquals(0, HoldRules.axis(positive = false, negative = false))
    assertEquals(0, HoldRules.axis(positive = true, negative = true))
  }

  @Test
  fun stickDownWinsWhenBothVerticalBitsAreSet() {
    // 0.2.1 bug: down never pulled. Decode like the SDK's own panel scrolling: D overrides U.
    assertEquals(1, HoldRules.vertical(up = true, down = false))
    assertEquals(-1, HoldRules.vertical(up = false, down = true))
    assertEquals(-1, HoldRules.vertical(up = true, down = true))
    assertEquals(0, HoldRules.vertical(up = false, down = false))
  }

  @Test
  fun stickClickTogglesOnlyWithTheStickCentred() {
    // 0.2.1 bug: the click bit came with every up/down push and flipped the mode each time.
    assertTrue(!HoldRules.acceptToggle(clickPressed = true, facePressed = false, deflected = true, msSinceDeflected = 0))
    assertTrue(!HoldRules.acceptToggle(clickPressed = true, facePressed = false, deflected = false, msSinceDeflected = 50))
    assertTrue(HoldRules.acceptToggle(clickPressed = true, facePressed = false, deflected = false, msSinceDeflected = 200))
    assertTrue(!HoldRules.acceptToggle(clickPressed = false, facePressed = false, deflected = false, msSinceDeflected = 5000))
    // B / Y always toggles, even while the stick is pushed.
    assertTrue(HoldRules.acceptToggle(clickPressed = false, facePressed = true, deflected = true, msSinceDeflected = 0))
  }

  @Test
  fun pullWorksFromEveryDistanceAboveTheMinimum() {
    for (d in listOf(0.61f, 1f, 1.6f, 3f, 9.9f, 10f, 15f)) {
      assertTrue("pull at $d", HoldRules.pushDelta(d, -1, 0.1f, 1f) < 0f)
    }
  }

  @Test
  fun rampEasesInToFullSpeed() {
    assertEquals(0f, HoldRules.ramp(0f), eps)
    assertEquals(0.5f, HoldRules.ramp(HoldRules.RAMP_S / 2), eps)
    assertEquals(1f, HoldRules.ramp(HoldRules.RAMP_S), eps)
    assertEquals(1f, HoldRules.ramp(10f), eps)
    assertTrue(HoldRules.ramp(0.05f) < 0.1f)
  }

  @Test
  fun stickModeToggles() {
    assertEquals(StickMode.SIZE, StickMode.DISTANCE.toggled())
    assertEquals(StickMode.DISTANCE, StickMode.SIZE.toggled())
    assertEquals("Stick: distance", StickMode.DISTANCE.hint)
    assertEquals("Stick: size", StickMode.SIZE.hint)
  }

  // ---- size ----

  @Test
  fun zoomGrowsHalfPerSecondAndShrinksBySameFactor() {
    val r = HoldRules.SCREEN_WIDTH_M
    assertEquals(2.4f, HoldRules.zoom(1.6f, 1, 1f, 1f, r), eps)
    assertEquals(1.6f, HoldRules.zoom(2.4f, -1, 1f, 1f, r), eps)
    // Steps add up the same however the frames fall.
    var w = 1.6f
    repeat(72) { w = HoldRules.zoom(w, 1, 1f / 72, 1f, r) }
    assertEquals(2.4f, w, 1e-3f)
    assertEquals(1.6f, HoldRules.zoom(1.6f, 0, 1f, 1f, r), eps)
    assertEquals(1.6f, HoldRules.zoom(1.6f, 1, 1f, 0f, r), eps) // not ramped in yet
  }

  @Test
  fun zoomStaysWithinLimits() {
    val r = HoldRules.SCREEN_WIDTH_M
    assertEquals(8f, HoldRules.zoom(7.9f, 1, 1f, 1f, r), eps)
    assertEquals(0.4f, HoldRules.zoom(0.45f, -1, 1f, 1f, r), eps)
    assertEquals(0.4f, HoldRules.zoom(0.2f, 1, 0.01f, 1f, r), eps) // outside: pulled in
    assertEquals(2.5f, HoldRules.zoom(2.4f, 1, 1f, 1f, HoldRules.PANEL_SCALE), eps)
    assertEquals(0.5f, HoldRules.zoom(0.6f, -1, 1f, 1f, HoldRules.PANEL_SCALE), eps)
  }

  // ---- distance ----

  @Test
  fun pushMovesOneMetrePerSecond() {
    assertEquals(0.5f, HoldRules.pushDelta(2f, 1, 0.5f, 1f), eps)
    assertEquals(-0.5f, HoldRules.pushDelta(2f, -1, 0.5f, 1f), eps)
    assertEquals(0.25f, HoldRules.pushDelta(2f, 1, 0.5f, 0.5f), eps)
    assertEquals(0f, HoldRules.pushDelta(2f, 0, 0.5f, 1f), eps)
  }

  @Test
  fun pushStopsAtTheDistanceLimits() {
    assertEquals(0.2f, HoldRules.pushDelta(9.8f, 1, 1f, 1f), eps)
    assertEquals(0f, HoldRules.pushDelta(10f, 1, 1f, 1f), eps)
    assertEquals(-0.1f, HoldRules.pushDelta(0.7f, -1, 1f, 1f), eps)
    assertEquals(0f, HoldRules.pushDelta(0.6f, -1, 1f, 1f), eps)
    // Already too close (grabbed close by hand): pulling does nothing, pushing works.
    assertEquals(0f, HoldRules.pushDelta(0.4f, -1, 0.1f, 1f), eps)
    assertEquals(0.1f, HoldRules.pushDelta(0.4f, 1, 0.1f, 1f), eps)
    assertEquals(-0.1f, HoldRules.pushDelta(12f, -1, 0.1f, 1f), eps)
  }

  // ---- tilt ----

  @Test
  fun rollTurnsThirtyDegreesPerSecond() {
    assertEquals(30f, HoldRules.roll(0f, 1, 1f, 1f), eps)
    assertEquals(-30f, HoldRules.roll(0f, -1, 1f, 1f), eps)
    assertEquals(75f, HoldRules.roll(45f, 1, 1f, 1f), eps)
    assertEquals(0f, HoldRules.roll(0f, 0, 1f, 1f), eps)
  }

  @Test
  fun rollWrapsRoundTheCircle() {
    assertEquals(-170f, HoldRules.roll(170f, 1, 20f / 30f, 1f), 1e-3f)
    assertEquals(180f, HoldRules.wrap(-180f), eps)
    assertEquals(10f, HoldRules.wrap(370f), eps)
    assertEquals(-90f, HoldRules.wrap(270f), eps)
  }

  /** Runs [frames] frames of 1/60 s with the stick at ([v], [hz]). */
  private fun AxisGate.hold(v: Int, hz: Int, frames: Int) = repeat(frames) { step(v, hz, 1f / 60f) }

  @Test
  fun leftRightTiltsOnlyAfterTheDelay() {
    val g = AxisGate()
    g.hold(0, 1, 10) // 0.17 s
    assertEquals(0, g.tilt)
    g.hold(0, 1, 4) // 0.23 s
    assertEquals(1, g.tilt)
    assertTrue(g.tiltS in 0f..0.05f)
  }

  /** What the Quest reports for a left push (logged on the device): L, then U + L. */
  @Test
  fun aSidewaysPushThatArcsUpStillTiltsAndNeverPushes() {
    val g = AxisGate()
    g.hold(0, -1, 3)
    g.hold(1, -1, 60)
    assertEquals(0, g.v)
    assertEquals(-1, g.tilt)
    // and a right push: R, then D + R
    g.hold(0, 0, 1)
    g.hold(0, 1, 2)
    g.hold(-1, 1, 60)
    assertEquals(0, g.v)
    assertEquals(1, g.tilt)
  }

  @Test
  fun aPushThatArcsSidewaysNeverTilts() {
    val g = AxisGate()
    g.hold(-1, 0, 10) // pulling
    g.hold(-1, 1, 30) // drifts diagonal
    g.hold(0, 1, 60) // and on to pure right without passing the centre
    assertEquals(0, g.tilt)
    g.hold(0, 0, 1) // centred
    g.hold(0, 1, 20)
    assertEquals(1, g.tilt)
  }

  @Test
  fun bothBitsInTheFirstFrameCountAsUpDown() {
    val g = AxisGate()
    g.hold(1, 1, 60)
    assertEquals(1, g.v)
    assertEquals(0, g.tilt)
  }

  @Test
  fun aQuickSidewaysFlickDoesNotTilt() {
    val g = AxisGate()
    g.hold(0, 1, 8)
    g.hold(0, 0, 1)
    g.hold(0, 1, 8)
    assertEquals(0, g.tilt)
  }

  @Test
  fun releaseNearLevelSnapsToLevel() {
    assertEquals(0f, HoldRules.snap(2.9f), eps)
    assertEquals(0f, HoldRules.snap(-3f), eps)
    assertEquals(0f, HoldRules.snap(359f), eps)
    assertEquals(3.5f, HoldRules.snap(3.5f), eps)
    assertEquals(-90f, HoldRules.snap(-90f), eps)
  }

  // ---- layout persistence ----

  private fun screen(width: Float, roll: Float) =
      SavedScreen(
          card = Card(id = "7", title = "Seven", width = 3840, height = 2160, projection = "flat"),
          pos = listOf(0f, 1.5f, 4.5f), // pushed out to 4.5 m: the distance is in the pose
          rot = listOf(1f, 0f, 0f, 0f),
          widthM = width,
          timeMs = 0,
          rollDeg = roll,
      )

  @Test
  fun sizeTiltAndDistanceSurviveTheLayout() {
    val layout = SavedLayout(listOf(screen(5.5f, -90f), screen(0.4f, 12.5f)))
    val back = LayoutCodec.decode(LayoutCodec.encode(layout))!!
    assertEquals(layout, back)
    assertEquals(-90f, back.screens[0].rollDeg, eps)
    assertEquals(5.5f, back.screens[0].widthM, eps)
    assertEquals(4.5f, back.screens[0].pos[2], eps)
  }

  @Test
  fun layoutsFrom020HaveNoTiltAndRestoreLevel() {
    val text = LayoutCodec.encode(SavedLayout(listOf(screen(1.6f, 0f)))).replace(",\"rollDeg\":0.0", "")
    assertTrue(!text.contains("rollDeg"))
    assertEquals(0f, LayoutCodec.decode(text)!!.screens.single().rollDeg, eps)
  }

  @Test
  fun aBrokenTiltDropsTheScreen() {
    assertNull(LayoutCodec.decode(LayoutCodec.encode(SavedLayout(listOf(screen(1.6f, Float.NaN))))))
  }

  @Test
  fun pitchFollowsTheStickAndStaysInRange() {
    assertEquals(15f, HoldRules.pitch(0f, 1, 1f, 0.5f), 1e-4f)
    assertEquals(-15f, HoldRules.pitch(0f, -1, 1f, 0.5f), 1e-4f)
    assertEquals(0f, HoldRules.pitch(0f, 0, 1f, 1f), 1e-4f)
    assertEquals(HoldRules.MAX_PITCH_DEG, HoldRules.pitch(84f, 1, 1f, 1f), 1e-4f)
    assertEquals(-HoldRules.MAX_PITCH_DEG, HoldRules.pitch(-84f, -1, 1f, 1f), 1e-4f)
  }
}
