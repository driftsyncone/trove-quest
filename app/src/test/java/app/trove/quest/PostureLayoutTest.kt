package app.trove.quest

import app.trove.quest.api.Card
import app.trove.quest.player.LayoutCodec
import app.trove.quest.player.SavedLayout
import app.trove.quest.player.SavedScreen
import app.trove.quest.posture.Placed
import app.trove.quest.posture.Posture
import app.trove.quest.posture.PostureLayout
import app.trove.quest.posture.ScreenSlot
import app.trove.quest.posture.Vec
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureLayoutTest {
  private val eps = 1e-3f
  private val head = Vec(1f, 1.4f, -2f)

  private fun slots(n: Int) = List(n) { ScreenSlot(if (it == 0) 2.4f else 1.6f, if (it == 0) 2.2f else 2.6f) }

  private fun near(a: Float, b: Float, tol: Float = 0.05f) = assertEquals(b, a, tol)

  private fun angleDiff(a: Float, b: Float) = PostureLayout.wrap(a - b)

  /** The unit vector a screen faces away along: from its yaw and pitch. */
  private fun facing(p: Placed) = PostureLayout.direction(p.yawDeg, p.pitchDeg)

  @Test
  fun arrangementPutsTheCentreInTheMiddleAndAlternates() {
    assertEquals(listOf(0), PostureLayout.arrangement(1, 0))
    assertEquals(listOf(0, 1), PostureLayout.arrangement(2, 0))
    assertEquals(listOf(2, 0, 1), PostureLayout.arrangement(3, 0)) // 1 goes right, 2 left
    assertEquals(listOf(2, 1, 0, 3), PostureLayout.arrangement(4, 1)) // others in index order: 0 right, 2 left, 3 right
    assertTrue(PostureLayout.arrangement(0, 0).isEmpty())
  }

  @Test
  fun theFocusedScreenIsStraightAheadAndNeighboursDoNotOverlap() {
    val s = slots(4)
    val yaws = PostureLayout.slotYaws(s, 0)
    near(yaws[0], 0f, 1e-3f)
    // Sorted left to right, neighbours are at least half-width + half-width + gap apart.
    val order = PostureLayout.arrangement(4, 0)
    for (k in 1 until order.size) {
      val l = order[k - 1]
      val r = order[k]
      val need = PostureLayout.angularWidthDeg(s[l].widthM, s[l].distM) / 2 + PostureLayout.angularWidthDeg(s[r].widthM, s[r].distM) / 2 + PostureLayout.GAP_DEG
      assertTrue("gap $l-$r", yaws[r] - yaws[l] >= need - 0.01f)
    }
  }

  @Test
  fun sittingIsALevelArcAtEyeHeightFacingYou() {
    val placed = PostureLayout.place(Posture.SIT, head, 30f, slots(3), 0)
    for ((i, p) in placed.withIndex()) {
      near(p.pos.y, head.y)
      near(p.pitchDeg, 0f, 0.01f)
      near(p.rollDeg, 0f, 0.01f)
      near((p.pos - head).length, slots(3)[i].distM, 0.001f)
    }
    // The first (centre) one is straight ahead of a body facing 30 degrees.
    near(placed[0].yawDeg, 30f, 0.01f)
    near(placed[0].pos.x, head.x + 2.2f * PostureLayout.direction(30f, 0f).x, 0.001f)
  }

  @Test
  fun everyPostureKeepsEveryScreenFacingTheHeadAtItsDistance() {
    for (posture in Posture.entries) for (body in listOf(0f, 37f, -120f, 180f)) for (n in 1..6) {
      val s = slots(n)
      val placed = PostureLayout.place(posture, head, body, s, n / 2)
      for ((i, p) in placed.withIndex()) {
        val d = p.pos - head
        near(d.length, s[i].distM, 0.001f)
        // Forward (away from the viewer) is the direction from the head to the screen.
        val f = facing(p)
        val u = d * (1f / d.length)
        near(f.x, u.x, 0.002f)
        near(f.y, u.y, 0.002f)
        near(f.z, u.z, 0.002f)
      }
    }
  }

  @Test
  fun lyingBackPutsTheArcOverheadTippedDownAtTheFace() {
    val placed = PostureLayout.place(Posture.LIE_BACK, head, 0f, slots(3), 0)
    val centre = placed[0]
    near(centre.pitchDeg, PostureLayout.LIE_ELEVATION_DEG, 0.01f)
    near(centre.rollDeg, 0f, 0.01f)
    near(centre.pos.y, head.y + 2.2f * kotlin.math.sin(Math.toRadians(70.0)).toFloat(), 0.001f)
    // Every screen is above the head, tipped down at it. (The ones to the side twist a little: they
    // are the sitting arc turned about the head, not slid along a ceiling.)
    for (p in placed) {
      assertTrue(p.pos.y > head.y + 1.0f)
      assertTrue(p.pitchDeg > 25f)
    }
    // Side by side stays side by side: the screens spread along the head's right axis.
    assertTrue(abs(placed[1].pos.x - head.x) > 0.5f || abs(placed[2].pos.x - head.x) > 0.5f)
  }

  @Test
  fun lyingBackFollowsTheBodyYaw() {
    val a = PostureLayout.place(Posture.LIE_BACK, head, 0f, slots(1), 0)[0]
    val b = PostureLayout.place(Posture.LIE_BACK, head, 90f, slots(1), 0)[0]
    near(angleDiff(b.yawDeg, a.yawDeg), 90f, 0.01f)
    near(b.pitchDeg, a.pitchDeg, 0.01f)
    near(b.pos.y, a.pos.y, 0.001f)
  }

  @Test
  fun onYourSideRollsEveryScreenWithTheHead() {
    for ((posture, roll) in listOf(Posture.SIDE_LEFT to -90f, Posture.SIDE_RIGHT to 90f)) {
      val placed = PostureLayout.place(posture, head, 0f, slots(3), 0)
      // The centre one is still straight ahead, just rolled.
      near(placed[0].pitchDeg, 0f, 0.01f)
      near(placed[0].yawDeg, 0f, 0.01f)
      for (p in placed) near(p.rollDeg, roll, 0.01f)
    }
  }

  @Test
  fun onYourSideTheArcRunsUpAndDown() {
    val placed = PostureLayout.place(Posture.SIDE_LEFT, head, 0f, slots(3), 0)
    // The sitting arc's right-hand neighbour ends up above eye level, the left-hand one below it.
    val order = PostureLayout.arrangement(3, 0) // [2, 0, 1]: 2 is left of the centre, 1 right
    assertTrue(placed[1].pos.y > head.y + 0.5f)
    assertTrue(placed[2].pos.y < head.y - 0.5f)
    assertEquals(listOf(2, 0, 1), order)
    // Left side: the head's right ear is up, so what was right of the head is above it. Right side flips that.
    val right = PostureLayout.place(Posture.SIDE_RIGHT, head, 0f, slots(3), 0)
    assertTrue(right[1].pos.y < head.y - 0.5f)
    assertTrue(right[2].pos.y > head.y + 0.5f)
  }

  @Test
  fun bodyYawFromAnUprightHead() {
    near(PostureLayout.bodyYawDeg(Vec(0f, 0f, 1f), Vec(0f, 1f, 0f))!!, 0f, 0.01f)
    near(PostureLayout.bodyYawDeg(PostureLayout.direction(50f, 20f), Vec(0f, 1f, 0f))!!, 50f, 0.01f)
  }

  @Test
  fun bodyYawLyingOnTheBackComesFromTheTopOfTheHead() {
    // Face up, the top of the head pointing towards -z (head at the -z end): the feet are at +z, so ahead is +z (yaw 0).
    near(PostureLayout.bodyYawDeg(Vec(0f, 1f, 0.05f), Vec(0f, 0.05f, -1f))!!, 0f, 0.01f)
    // Same bed turned 90 degrees: the head's top towards -x, the feet towards +x: ahead is yaw 90.
    near(PostureLayout.bodyYawDeg(Vec(0.05f, 1f, 0f), Vec(-1f, 0.05f, 0f))!!, 90f, 0.01f)
    // Face down: the top of the head points the way the body is facing.
    near(PostureLayout.bodyYawDeg(Vec(0f, -1f, 0f), Vec(0f, 0f, 1f))!!, 0f, 0.01f)
    // Nothing to go on.
    assertNull(PostureLayout.bodyYawDeg(Vec(0f, 1f, 0f), Vec(0f, 1f, 0f)))
  }

  // ---- Bring screens to me ----

  private fun layout() = PostureLayout.place(Posture.SIT, head, 20f, slots(3), 0)

  @Test
  fun theLayoutCentreIsTheMeanDirectionOfTheScreens() {
    val c = PostureLayout.centreYawDeg(head, layout().map { it.pos })!!
    // The centre screen is at yaw 20; the others spread both sides of it.
    near(c, 20f, 8f)
    assertNull(PostureLayout.centreYawDeg(head, listOf(head + Vec(0f, 2f, 0f))))
    // All round the user cancels out.
    assertNull(PostureLayout.centreYawDeg(head, listOf(head + Vec(0f, 0f, 2f), head + Vec(0f, 0f, -2f))))
  }

  @Test
  fun bringingTurnsEverythingAboutTheHeadAndKeepsItsShape() {
    for (posture in Posture.entries) {
      val before = PostureLayout.place(posture, head, 20f, slots(4), 0)
      val centre = PostureLayout.centreYawDeg(head, before.map { it.pos })!!
      val delta = PostureLayout.bringDelta(centre, 150f)
      val after = PostureLayout.turnAll(head, before, delta)
      for (i in before.indices) {
        // Same distance and height, same pitch and tilt, yaw moved by the turn.
        near((after[i].pos - head).length, (before[i].pos - head).length, 0.001f)
        near(after[i].pos.y, before[i].pos.y, 0.001f)
        near(after[i].pitchDeg, before[i].pitchDeg, 0.001f)
        near(after[i].rollDeg, before[i].rollDeg, 0.001f)
        near(angleDiff(after[i].yawDeg, before[i].yawDeg), delta, 0.01f)
        // Still facing the head.
        val u = (after[i].pos - head) * (1f / (after[i].pos - head).length)
        val f = facing(after[i])
        near(f.x, u.x, 0.003f)
        near(f.z, u.z, 0.003f)
      }
      // And the centre is now where we asked.
      near(angleDiff(PostureLayout.centreYawDeg(head, after.map { it.pos })!!, 150f), 0f, 0.05f)
    }
  }

  @Test
  fun bringingBySittingBackIsTheSameAsLayingItOutFacingThatWay() {
    val a = PostureLayout.place(Posture.SIT, head, 0f, slots(1), 0)
    val turned = PostureLayout.turnAll(head, a, 90f)[0]
    val direct = PostureLayout.place(Posture.SIT, head, 90f, slots(1), 0)[0]
    near(turned.pos.x, direct.pos.x, 0.001f)
    near(turned.pos.z, direct.pos.z, 0.001f)
    near(angleDiff(turned.yawDeg, direct.yawDeg), 0f, 0.01f)
  }

  @Test
  fun bringDeltaTakesTheShortWay() {
    near(PostureLayout.bringDelta(170f, -170f), 20f, 0.001f)
    near(PostureLayout.bringDelta(-170f, 170f), -20f, 0.001f)
    near(PostureLayout.bringDelta(10f, 10f), 0f, 0.001f)
  }

  @Test
  fun rotateAboutTurnsTowardsPlusX() {
    val p = PostureLayout.rotateAbout(Vec(0f, 0f, 0f), Vec(0f, 1f, 2f), 90f)
    near(p.x, 2f, 0.001f)
    near(p.z, 0f, 0.001f)
    near(p.y, 1f, 0.001f)
  }

  // ---- held screens keep their pitch ----

  @Test
  fun aGrabKeepsAScreensPitchAndTilt() {
    val lying = PostureLayout.place(Posture.LIE_BACK, head, 0f, slots(2), 0)[1]
    // The grab writes a yaw-only facing (here: towards where the hand carried it) and nothing else.
    val moved = PostureLayout.afterGrab(lying, grabYawDeg = -40f, newPos = lying.pos + Vec(0.3f, 0f, 0f))
    near(moved.pitchDeg, lying.pitchDeg, 0.0001f)
    near(moved.rollDeg, lying.rollDeg, 0.0001f)
    near(moved.yawDeg, -40f, 0.0001f)
    // Still tipped up and away: its forward axis keeps the pitch.
    near(PostureLayout.pitchOf(facing(moved)), lying.pitchDeg, 0.01f)
  }

  @Test
  fun bearingGivesYawPitchAndDistance() {
    val (yaw, pitch, dist) = PostureLayout.bearing(head, head + Vec(0f, 2f, 2f))
    near(yaw, 0f, 0.001f)
    near(pitch, 45f, 0.01f)
    near(dist, 2.828f, 0.01f)
  }

  @Test
  fun postureIdsRoundTripAndUnknownIsSitting() {
    for (p in Posture.entries) assertEquals(p, Posture.of(p.id))
    assertEquals(Posture.SIT, Posture.of(null))
    assertEquals(Posture.SIT, Posture.of("flying"))
    assertNotNull(Posture.entries.firstOrNull { it == Posture.LIE_BACK })
  }

  // ---- layout persistence ----

  private fun saved(pitch: Float) =
      SavedScreen(
          card = Card(id = "7", title = "Seven", width = 3840, height = 2160, projection = "flat"),
          pos = listOf(0f, 3f, 1f),
          rot = listOf(1f, 0f, 0f, 0f),
          widthM = 1.6f,
          timeMs = 0,
          rollDeg = 0f,
          pitchDeg = pitch,
      )

  @Test
  fun pitchSurvivesTheSavedLayoutAndOldLayoutsRestoreUpright() {
    val back = LayoutCodec.decode(LayoutCodec.encode(SavedLayout(listOf(saved(70f)))))!!
    assertEquals(70f, back.screens.single().pitchDeg, eps)
    val old = LayoutCodec.encode(SavedLayout(listOf(saved(0f)))).replace(",\"pitchDeg\":0.0", "")
    assertTrue(!old.contains("pitchDeg"))
    assertEquals(0f, LayoutCodec.decode(old)!!.screens.single().pitchDeg, eps)
    assertNull(LayoutCodec.decode(LayoutCodec.encode(SavedLayout(listOf(saved(Float.NaN))))))
  }

  @Test
  fun aRowAboveEyeLevelIsTippedDownAtYouEvenSitting() {
    val p = PostureLayout.placeOne(Posture.SIT, head, 0f, 0f, 2.6f, slotElevDeg = 25f)
    near(p.pitchDeg, 25f, 0.1f)
    near(p.yawDeg, 0f, 0.1f)
    near(p.rollDeg, 0f, 0.1f)
    near(p.pos.y - head.y, 2.6f * Math.sin(Math.toRadians(25.0)).toFloat(), 0.01f)
    // A row on your side keeps the screen's roll with the head, elevation or not.
    near(Math.abs(PostureLayout.placeOne(Posture.SIDE_LEFT, head, 0f, 0f, 2.6f, slotElevDeg = 10f).rollDeg), 90f, 1f)
  }
}
