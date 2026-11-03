package app.trove.quest

import app.trove.quest.gaze.GazeRule
import app.trove.quest.gaze.GazeTarget
import app.trove.quest.gaze.PointerGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GazeRuleTest {
  // Three screens like the default arc: 1.6 m wide at 2.6 m is +-17.1 degrees; 4 degrees apart.
  private val left = GazeTarget("L", -38f, 0f, 17f)
  private val mid = GazeTarget("M", 0f, 0f, 17f)
  private val right = GazeTarget("R", 38f, 0f, 17f)
  private val all = listOf(left, mid, right)

  private fun rule() = GazeRule(dwellMs = 800, cooldownMs = 1500)

  /** Looks at [yaw] every 100 ms from [from] for [ms]; returns the first pick and when. */
  private fun look(r: GazeRule, from: Long, ms: Long, yaw: Float, focused: String?, blocked: Boolean = false, pitch: Float = 0f): Pair<String, Long>? {
    var t = from
    while (t <= from + ms) {
      r.update(t, yaw, pitch, all, focused, blocked)?.let { return it to t }
      t += 100
    }
    return null
  }

  @Test
  fun focusesTheFacedScreenAfterTheDwell() {
    val r = rule()
    val (id, at) = look(r, 0, 2000, 38f, focused = "M")!!
    assertEquals("R", id)
    assertTrue("dwell, not a glance: $at", at >= 800)
    assertTrue(at <= 1000)
  }

  @Test
  fun aGlanceDoesNothing() {
    val r = rule()
    assertNull(look(r, 0, 600, 38f, "M")) // looks right for 0.6 s
    assertNull(look(r, 700, 500, 0f, "M")) // back on the focused one: the dwell starts over
    assertNull(look(r, 1300, 600, 38f, "M"))
  }

  @Test
  fun alreadyFocusedNeverFires() {
    val r = rule()
    assertNull(look(r, 0, 5000, 0f, "M"))
  }

  @Test
  fun sweepingAcrossTheWallPicksNothing() {
    val r = rule()
    var focused = "M"
    var t = 0L
    // Slow scan from far left to far right, 100 ms per 5 degrees: about 0.3 s over each screen.
    var yaw = -60f
    while (yaw <= 60f) {
      r.update(t, yaw, 0f, all, focused, false)?.let { focused = it }
      t += 100
      yaw += 5f
    }
    assertEquals("M", focused)
  }

  @Test
  fun theGapBetweenScreensFacesNothing() {
    val r = rule()
    // 19 degrees: 19 from M (ratio 1.1) and 19 from R: outside both.
    assertNull(look(r, 0, 3000, 19f, "M"))
    assertNull(GazeRule.faced(19f, 0f, all))
  }

  @Test
  fun hysteresisKeepsFocusOnABorderline() {
    // Two big overlapping screens: C at 0 and D at 20 degrees, each 40 degrees of radius.
    val two = listOf(GazeTarget("C", 0f, 0f, 40f), GazeTarget("D", 20f, 0f, 40f))
    // At 11 degrees D is a little more central (0.225 vs 0.275 radii) but not by the margin: nobody takes over.
    assertEquals("C", GazeRule.candidate(11f, 0f, two, focusedId = "C"))
    assertEquals("D", GazeRule.candidate(11f, 0f, two, focusedId = "D"))
    // Clearly more central wins even over the focused one.
    assertEquals("D", GazeRule.candidate(19f, 0f, two, focusedId = "C"))
    // Nothing focused yet: the most central.
    assertEquals("D", GazeRule.candidate(11f, 0f, two, focusedId = null))
  }

  @Test
  fun anotherScreenHasToStayCandidateForTheWholeDwell() {
    val r = rule()
    // Right for 0.5 s, then the left one for 0.5 s, then right again 0.5 s: never 0.8 s on one.
    assertNull(look(r, 0, 500, 38f, "M"))
    assertNull(look(r, 600, 500, -38f, "M"))
    assertNull(look(r, 1200, 500, 38f, "M"))
  }

  @Test
  fun blockedClearsTheDwell() {
    val r = rule()
    assertNull(look(r, 0, 700, 38f, "M"))
    assertNull(look(r, 800, 1000, 38f, "M", blocked = true))
    // The dwell starts over when the block ends.
    val (_, at) = look(r, 1900, 2000, 38f, "M")!!
    assertTrue("restarted: $at", at >= 1900 + 800)
  }

  @Test
  fun aSwitchWaitsOutTheCooldownBeforeTheNext() {
    val r = rule()
    val (id, at) = look(r, 0, 2000, 38f, "M")!!
    assertEquals("R", id)
    // Now R is focused; looking left right away must wait for the cooldown after the switch.
    val (id2, at2) = look(r, at + 100, 4000, -38f, "R")!!
    assertEquals("L", id2)
    assertTrue("cooldown: ${at2 - at}", at2 - at >= 1500)
  }

  @Test
  fun noScreensNoPick() {
    val r = rule()
    assertNull(r.update(0, 0f, 0f, emptyList(), null, false))
  }

  @Test
  fun pitchCounts() {
    val r = rule()
    // The same yaw but looking 40 degrees up is not looking at a level screen.
    assertNull(look(r, 0, 3000, 38f, "M", pitch = 40f))
    // An overhead screen is faced by looking up at it.
    val up = GazeTarget("U", 0f, 70f, 17f)
    assertEquals("U", GazeRule.faced(2f, 68f, listOf(mid, up)))
  }

  @Test
  fun facedPicksTheMostCentralOfOverlappingScreens() {
    val a = GazeTarget("A", 0f, 0f, 30f)
    val b = GazeTarget("B", 20f, 0f, 30f)
    assertEquals("B", GazeRule.faced(15f, 0f, listOf(a, b)))
    assertEquals("A", GazeRule.faced(5f, 0f, listOf(a, b)))
    assertNull(GazeRule.faced(120f, 0f, listOf(a, b)))
  }

  @Test
  fun angleBetweenLooks() {
    assertEquals(90f, GazeRule.angleDeg(0f, 0f, 90f, 0f), 0.01f)
    assertEquals(90f, GazeRule.angleDeg(0f, 0f, 0f, 90f), 0.01f)
    assertEquals(0f, GazeRule.angleDeg(30f, 20f, 30f, 20f), 0.3f)
    assertEquals(20f, GazeRule.angleDeg(170f, 0f, -170f, 0f), 0.01f)
  }

  @Test
  fun pointerGateStaysQuietForAWhile() {
    var now = 10_000L
    val g = PointerGate({ now }, windowMs = 1500)
    assertFalse(g.recent())
    g.touch()
    assertTrue(g.recent())
    now += 1499
    assertTrue(g.recent())
    now += 2
    assertFalse(g.recent())
  }
}
