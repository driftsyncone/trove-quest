package app.trove.quest

import app.trove.quest.drag.V3
import app.trove.quest.input.FingerJoints
import app.trove.quest.input.HandEscape
import app.trove.quest.input.HandFrame
import app.trove.quest.input.HandJoints
import app.trove.quest.input.HandPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hands-only wearer escape (input/HandEscape.kt), fed synthetic joints. */
class HandEscapeTest {
  private val head = V3(0f, 1.6f, 0f)
  private val up = V3(0f, 1f, 0f)
  private val towardFace = V3(0f, 0f, 1f) // the user looks down -Z
  private val away = V3(0f, 0f, -1f)

  private fun cross(a: V3, b: V3) = V3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

  private enum class Shape { OPEN, FIST, PINCH, THUMB_ON_TIP }

  /** A hand with its palm centre at [palm], fingers along [u], the palm facing [n]. */
  private fun hand(palm: V3, u: V3 = up, n: V3 = away, shape: Shape = Shape.OPEN): HandJoints {
    val l = cross(n, u) // across the knuckles
    val wrist = palm - u * 0.04f
    fun finger(across: Float, curled: Boolean): FingerJoints {
      val prox = palm + u * 0.05f + l * across
      return if (!curled) {
        val i = prox + u * 0.04f
        val d = i + u * 0.025f
        FingerJoints(prox, i, d, d + u * 0.02f)
      } else {
        val i = prox + n * 0.035f
        val d = i - u * 0.025f
        FingerJoints(prox, i, d, d - u * 0.015f)
      }
    }
    val curled = shape == Shape.FIST
    val index = finger(0.03f, curled || shape == Shape.PINCH)
    val thumb =
        when (shape) {
          Shape.PINCH, Shape.THUMB_ON_TIP -> index.tip + l * 0.005f
          Shape.FIST -> palm + n * 0.03f
          Shape.OPEN -> palm + l * 0.07f + u * 0.02f
        }
    return HandJoints(wrist, palm, thumb, index, finger(0.01f, curled), finger(-0.01f, curled), finger(-0.03f, curled))
  }

  private val leftPalm = V3(-0.15f, 1.55f, -0.35f)
  private val rightPalm = V3(0.15f, 1.55f, -0.35f)

  private fun stop(n: V3 = away) = HandFrame(hand(leftPalm, n = n), hand(rightPalm, n = n), head)

  /** Feed [frame] every 20 ms from [from] for [ms]; the times at which it fired. */
  private fun HandEscape.run(from: Long, ms: Long, frame: (Long) -> HandFrame?): List<Long> {
    val out = mutableListOf<Long>()
    var t = from
    while (t <= from + ms) {
      if (tick(t, frame(t))) out += t
      t += 20
    }
    return out
  }

  @Test
  fun stopSignHeldTwoSecondsFiresOnce() {
    val e = HandEscape()
    assertEquals(listOf(2000L), e.run(0, 5000) { stop() })
  }

  @Test
  fun palmsTowardTheFaceCountToo() {
    assertNull(HandPose.explain(stop(towardFace)))
    assertEquals(listOf(2000L), HandEscape().run(0, 3000) { stop(towardFace) })
  }

  @Test
  fun anyHeadingWorks() {
    // Looking down +X: hands in front along +X, palms across that line.
    val h = V3(1f, 1.6f, 2f)
    val f = HandFrame(hand(V3(1.35f, 1.55f, 1.85f), n = V3(1f, 0f, 0f)), hand(V3(1.35f, 1.55f, 2.15f), n = V3(-1f, 0f, 0f)), h)
    assertNull(HandPose.explain(f))
  }

  @Test
  fun releaseAndHoldAgainFiresAgain() {
    val e = HandEscape()
    assertEquals(listOf(2000L), e.run(0, 2500) { stop() })
    assertFalse(e.tick(2520, null))
    assertEquals(listOf(4540L), e.run(2540, 3000) { stop() })
  }

  @Test
  fun oneLostFrameRestartsTheCount() {
    val e = HandEscape()
    val fired = e.run(0, 5000) { t -> if (t == 1500L) HandFrame(null, hand(rightPalm), head) else stop() }
    assertEquals(listOf(3520L), fired)
  }

  @Test
  fun lostHeadOrBothHandsNeverCount() {
    assertTrue(HandEscape().run(0, 4000) { HandFrame(hand(leftPalm), hand(rightPalm), null) }.isEmpty())
    assertTrue(HandEscape().run(0, 4000) { null }.isEmpty())
    assertTrue(HandEscape().run(0, 4000) { HandFrame(hand(leftPalm), null, head) }.isEmpty())
  }

  @Test
  fun aGapBetweenFramesRestartsTheCount() {
    val e = HandEscape()
    assertTrue(e.run(0, 1500) { stop() }.isEmpty())
    // No frames from 1500 to 1900 (the app stalled): start over at 1900.
    assertEquals(listOf(3900L), e.run(1900, 3000) { stop() })
  }

  @Test
  fun aPalmJumpIsAGlitchAndRestartsTheCount() {
    val e = HandEscape()
    val fired =
        e.run(0, 5000) { t ->
          // Still the pose, but 30 cm sideways in one frame.
          if (t == 1000L) HandFrame(hand(leftPalm + V3(0.3f, 0f, 0f)), hand(rightPalm + V3(0.3f, 0f, 0f)), head) else stop()
        }
    // 1000 jumped in (count from 1000), 1020 jumped back (count from 1020).
    assertEquals(listOf(3020L), fired)
  }

  @Test
  fun fistsPinchesAndThumbOnTheTipAreNotOpen() {
    for (s in listOf(Shape.FIST, Shape.PINCH, Shape.THUMB_ON_TIP)) {
      assertFalse("$s", HandPose.open(hand(leftPalm, shape = s)))
      val f = HandFrame(hand(leftPalm, shape = s), hand(rightPalm), head)
      assertNotNull("$s", HandPose.explain(f))
      assertTrue("$s", HandEscape().run(0, 4000) { f }.isEmpty())
    }
    assertTrue(HandPose.open(hand(leftPalm)))
  }

  @Test
  fun palmsFacingEachOtherDoNotCount() {
    val f = HandFrame(hand(leftPalm, n = V3(1f, 0f, 0f)), hand(rightPalm, n = V3(-1f, 0f, 0f)), head)
    assertEquals("left palm not facing", HandPose.explain(f))
  }

  @Test
  fun pointingAtPanelsDoesNotCount() {
    // The usual hand-tracking pose: hands at chest height, fingers forward, palms down.
    val fwd = V3(0f, 0f, -1f)
    val down = V3(0f, -1f, 0f)
    val f = HandFrame(hand(V3(-0.2f, 1.2f, -0.4f), u = fwd, n = down), hand(V3(0.2f, 1.2f, -0.4f), u = fwd, n = down), head)
    assertNotNull(HandPose.explain(f))
    assertTrue(HandEscape().run(0, 4000) { f }.isEmpty())
  }

  @Test
  fun wrongHeightOrPlaceDoesNotCount() {
    // Stretching overhead.
    val high = HandFrame(hand(V3(-0.15f, 2.0f, -0.25f)), hand(V3(0.15f, 2.0f, -0.25f)), head)
    assertEquals("left height 0.40 m", HandPose.explain(high))
    // Hands raised at chest height (below the chin).
    val low = HandFrame(hand(V3(-0.15f, 1.1f, -0.35f)), hand(V3(0.15f, 1.1f, -0.35f)), head)
    assertNotNull(HandPose.explain(low))
    // Beside the head, not in front.
    val beside = HandFrame(hand(V3(-0.3f, 1.6f, -0.05f)), hand(V3(0.3f, 1.6f, -0.05f)), head)
    assertNotNull(HandPose.explain(beside))
  }

  @Test
  fun handsCollapsedOnOneSpotAreAGlitch() {
    val f = HandFrame(hand(V3(0f, 1.55f, -0.35f)), hand(V3(0.02f, 1.55f, -0.35f)), head)
    assertTrue(HandPose.explain(f)!!.startsWith("palms apart"))
  }

  @Test
  fun fingersDownDoNotCount() {
    val f = HandFrame(hand(leftPalm, u = V3(0f, -1f, 0f)), hand(rightPalm, u = V3(0f, -1f, 0f)), head)
    assertEquals("left fingers not up", HandPose.explain(f))
  }
}
