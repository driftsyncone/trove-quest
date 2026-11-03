package app.trove.quest

import app.trove.quest.layout.Facing
import app.trove.quest.layout.Shell
import app.trove.quest.layout.SphereLayout
import app.trove.quest.posture.PostureLayout
import app.trove.quest.posture.Vec
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereLayoutTest {
  private fun shell(yaw: Float, elev: Float = 0f, dist: Float = 2.6f, w: Float = 1.6f, h: Float = 0.9f, roll: Float = 0f) =
      Shell(PostureLayout.direction(yaw, elev), dist, w, h, roll)

  private fun angle(a: Vec, b: Vec): Float = Math.toDegrees(Math.acos((a.dot(b) / (a.length * b.length)).toDouble().coerceIn(-1.0, 1.0))).toFloat()

  // ---- Face me ----

  @Test
  fun facingPointsFromTheHeadToTheScreen() {
    assertEquals(0f, Facing.yawDeg(0f, 2f), 1e-3f)
    assertEquals(90f, Facing.yawDeg(2f, 0f), 1e-3f)
    assertEquals(0f, Facing.pitchDeg(0f, 0f, 2f), 1e-3f)
    // A screen above the head is tipped down at it: positive pitch, as in PostureLayout.
    assertEquals(45f, Facing.pitchDeg(0f, 2f, 2f), 1e-2f)
    assertEquals(-30f, Facing.pitchDeg(0f, -1f, 1.732f), 0.1f)
    // Straight up is clamped, not 90.
    assertEquals(Facing.MAX_PITCH_DEG, Facing.pitchDeg(0f, 3f, 0f), 1e-3f)
  }

  @Test
  fun facingAgreesWithTheLyingBackPosture() {
    val p = PostureLayout.placeOne(app.trove.quest.posture.Posture.LIE_BACK, Vec(0f, 1.5f, 0f), 0f, 0f, 2.2f)
    assertEquals(p.pitchDeg, Facing.pitchDeg(p.pos.x, p.pos.y - 1.5f, p.pos.z), 0.05f)
    assertEquals(p.yawDeg, Facing.yawDeg(p.pos.x, p.pos.z), 0.05f)
  }

  // ---- overlap ----

  @Test
  fun neighbouringScreensCollideAndSeparatedOnesDoNot() {
    assertTrue(SphereLayout.overlaps(shell(0f), shell(10f)))
    assertFalse(SphereLayout.overlaps(shell(0f), shell(60f)))
    assertFalse(SphereLayout.overlaps(shell(0f), shell(0f, 40f)))
  }

  @Test
  fun aMuchNearerScreenMaySitInFront() {
    val far = shell(0f, dist = 2.6f)
    assertFalse(SphereLayout.overlaps(far, shell(0f, dist = 2.0f)))
    assertTrue(SphereLayout.overlaps(far, shell(0f, dist = 2.3f)))
    assertTrue(SphereLayout.overlaps(far, shell(0f, dist = 2.6f + SphereLayout.DEPTH_M - 0.01f)))
    assertFalse(SphereLayout.overlaps(far, shell(0f, dist = 2.6f + SphereLayout.DEPTH_M)))
  }

  @Test
  fun aScreenOnItsSideCoversATallNarrowPatch() {
    val upright = shell(0f)
    val sideways = shell(0f, roll = 90f)
    assertTrue(sideways.halfHeightDeg > upright.halfHeightDeg)
    assertTrue(sideways.halfWidthDeg < upright.halfWidthDeg)
  }

  @Test
  fun wrapAroundBehindYouIsHandled() {
    assertTrue(SphereLayout.overlaps(shell(179f), shell(-179f)))
    assertFalse(SphereLayout.overlaps(shell(0f), shell(180f)))
  }

  // ---- relaxation ----

  @Test
  fun relaxationSeparatesOverlappingScreensWithAMinimalMove() {
    val a = shell(0f)
    val b = shell(20f) // side by side but overlapping a little
    val pen = SphereLayout.penetration(a, b)!!
    val out = SphereLayout.relax(listOf(a, b))
    assertTrue(SphereLayout.isClear(out))
    // Only the overlap (and the gap) was removed: the total movement is about the horizontal penetration.
    val moved = angle(a.dir, out[0].dir) + angle(b.dir, out[1].dir)
    assertEquals(pen.first, moved, 0.6f)
    // Both moved about the same (soft, symmetric push).
    assertEquals(angle(a.dir, out[0].dir), angle(b.dir, out[1].dir), 0.3f)
  }

  @Test
  fun relaxationLeavesClearLayoutsAlone() {
    val s = listOf(shell(-40f), shell(0f), shell(40f))
    val out = SphereLayout.relax(s)
    for (i in s.indices) assertEquals(0f, angle(s[i].dir, out[i].dir), 1e-3f)
  }

  @Test
  fun aHeldScreenNeverMovesAndTheOtherGivesWay() {
    val a = shell(0f)
    val b = shell(12f)
    val out = SphereLayout.relax(listOf(a, b), pinned = setOf(0))
    assertEquals(0f, angle(a.dir, out[0].dir), 1e-4f)
    assertTrue(SphereLayout.isClear(out))
    val out2 = SphereLayout.relax(listOf(a, b), pinned = setOf(1))
    assertEquals(0f, angle(b.dir, out2[1].dir), 1e-4f)
    assertTrue(SphereLayout.isClear(out2))
  }

  @Test
  fun aHeldScreenDraggedThroughPushesTheOthersAside() {
    // The held one sweeps across a row; every frame the others are relaxed from where they are.
    var row = listOf(shell(-40f), shell(0f), shell(40f))
    for (yaw in -60..60 step 5) {
      val held = shell(yaw.toFloat(), 0f, 2.6f, 1.6f, 0.9f)
      val out = SphereLayout.relax(listOf(held) + row, pinned = setOf(0))
      assertEquals(0f, angle(held.dir, out[0].dir), 1e-4f)
      assertTrue("clear at yaw $yaw", SphereLayout.isClear(out))
      row = out.drop(1)
    }
  }

  @Test
  fun aChainOfOverlapsResolves() {
    val s = List(5) { shell(it * 22f) }
    val out = SphereLayout.relax(s)
    assertTrue(SphereLayout.isClear(out))
    // They stay in order along the row.
    val yaws = out.map { it.yawDeg }
    assertEquals(yaws.sorted(), yaws)
  }

  @Test
  fun identicalScreensAreSplitDeterministically() {
    val out = SphereLayout.relax(listOf(shell(0f), shell(0f)))
    assertTrue(SphereLayout.isClear(out))
  }

  @Test
  fun relaxationKeepsDistancesAndStaysBelowThePole() {
    val a = shell(0f, 78f, dist = 2f)
    val b = shell(0f, 79f, dist = 2.1f)
    val out = SphereLayout.relax(listOf(a, b))
    assertEquals(2f, out[0].distM, 0f)
    assertEquals(2.1f, out[1].distM, 0f)
    for (o in out) assertTrue(abs(o.elevDeg) <= SphereLayout.MAX_ELEV_DEG + 0.1f)
  }

  @Test
  fun aNearerScreenIsNotPushedByAFarOne() {
    val far = shell(0f, dist = 3.2f)
    val near = shell(0f, dist = 2.0f)
    val out = SphereLayout.relax(listOf(far, near))
    assertEquals(0f, angle(far.dir, out[0].dir), 1e-4f)
    assertEquals(0f, angle(near.dir, out[1].dir), 1e-4f)
  }

  // ---- a free spot for a new screen ----

  @Test
  fun anEmptyRoomGivesExactlyWhereYouLook() {
    val look = PostureLayout.direction(15f, 5f)
    val d = SphereLayout.nearestFree(emptyList(), 1.6f, 0.9f, 2.6f, look)
    assertEquals(0f, angle(look, d), 0.01f)
  }

  @Test
  fun aNewScreenTakesTheNearestFreeSpotSideways() {
    val first = Shell(PostureLayout.direction(0f, 0f), 2.2f, 2.4f, 1.35f)
    val d = SphereLayout.nearestFree(listOf(first), 1.6f, 0.9f, 2.6f, PostureLayout.direction(0f, 0f))
    val me = Shell(d, 2.6f, 1.6f, 0.9f)
    assertFalse(SphereLayout.overlaps(me, first))
    // Beside, not above: elevation stays level and the yaw is the smallest that fits.
    assertTrue(abs(me.elevDeg) < 3f)
    val needed = first.halfWidthDeg + me.halfWidthDeg + SphereLayout.GAP_DEG
    assertEquals(needed, abs(me.yawDeg), 3f)
  }

  @Test
  fun aDropOnAScreenLandsNextToIt() {
    val others = listOf(shell(-30f), shell(0f), shell(30f))
    val d = SphereLayout.nearestFree(others, 1.6f, 0.9f, 2.6f, PostureLayout.direction(0f, 0f))
    val me = Shell(d, 2.6f, 1.6f, 0.9f)
    for (o in others) assertFalse(SphereLayout.overlaps(me, o))
  }

  @Test
  fun aFullRoomStillGivesAnAnswer() {
    val others = List(36) { shell(it * 10f - 180f) } + List(36) { shell(it * 10f - 180f, 20f) } + List(36) { shell(it * 10f - 180f, -20f) }
    val d = SphereLayout.nearestFree(others, 1.6f, 0.9f, 2.6f, PostureLayout.direction(0f, 0f))
    assertEquals(1f, d.length, 1e-3f)
  }

  // ---- tidy ----

  private fun gridOf(n: Int, centre: Int = 0): List<Shell> {
    val w = 2f * Math.toDegrees(Math.atan(1.6 / 2 / 2.6)).toFloat()
    val h = 2f * Math.toDegrees(Math.atan(0.9 / 2 / 2.6)).toFloat()
    val slots = SphereLayout.tidy(List(n) { w }, List(n) { h }, List(n) { it * 10f }, centre)
    return slots.map { Shell(PostureLayout.direction(it.yawDeg, it.elevDeg), 2.6f, 1.6f, 0.9f) }
  }

  @Test
  fun tidyGivesNoOverlapAtAnyCount() {
    for (n in 1..6) assertTrue("n=$n", SphereLayout.isClear(gridOf(n, centre = n / 2)))
  }

  @Test
  fun tidyPutsTheFocusedScreenStraightAhead() {
    for (n in 1..6) {
      val c = n - 1
      val g = gridOf(n, centre = c)
      assertEquals("n=$n", 0f, g[c].yawDeg, 0.5f)
      assertEquals(0f, g[c].elevDeg, 0.5f)
    }
  }

  @Test
  fun tidyKeepsTheCurrentLeftToRightOrder() {
    val slots = SphereLayout.tidy(List(3) { 30f }, List(3) { 20f }, listOf(50f, -50f, 0f), centre = 2)
    assertTrue(slots[1].yawDeg < slots[2].yawDeg)
    assertTrue(slots[2].yawDeg < slots[0].yawDeg)
  }

  @Test
  fun tidyUsesRowsWhenOneRowWouldWrapRoundTheRoom() {
    val slots = SphereLayout.tidy(List(6) { 34f }, List(6) { 20f }, List(6) { it.toFloat() }, centre = 0)
    assertTrue(slots.map { it.elevDeg }.toSet().size > 1)
    val span = slots.filter { it.elevDeg == 0f }.map { it.yawDeg }.let { it.max() - it.min() }
    assertTrue("row span $span", span <= 140f)
    assertEquals(emptyList<SphereLayout.Slot>(), SphereLayout.tidy(emptyList(), emptyList(), emptyList(), 0))
  }
}
