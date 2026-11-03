package app.trove.quest

import app.trove.quest.drag.DragEnd
import app.trove.quest.drag.DragRules
import app.trove.quest.drag.PanelRect
import app.trove.quest.drag.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragRulesTest {
  private val eps = 1e-3f

  /** A 1.8 x 1.2 m wall 1.6 m in front of a head at (0, 1.5, 0), facing the user (normal along z). */
  private val wall = PanelRect(V3(0f, 1.3f, 1.6f), V3(1f, 0f, 0f), V3(0f, 1f, 0f), V3(0f, 0f, 1f), 0.9f, 0.6f)
  private val hand = V3(0.2f, 1.2f, 0.3f)

  @Test
  fun rayHitsTheWallInsideItsEdges() {
    val hit = DragRules.rayHitsRect(hand, V3(0f, 0f, 1f), wall)
    assertNotNull(hit)
    assertEquals(1.3f, hit!!.t, eps)
    assertEquals(0.2f, hit.u, eps)
    assertEquals(-0.1f, hit.v, eps)
  }

  @Test
  fun rayMissesBesideAboveAndBehind() {
    assertNull(DragRules.rayHitsRect(hand, V3(1f, 0f, 0.2f).normalized(), wall)) // far right
    assertNull(DragRules.rayHitsRect(hand, V3(0f, 1f, 0.5f).normalized(), wall)) // too high
    assertNull(DragRules.rayHitsRect(hand, V3(0f, 0f, -1f), wall)) // away from it
    assertNull(DragRules.rayHitsRect(hand, V3(1f, 0f, 0f), wall)) // parallel
  }

  @Test
  fun marginGrowsTheRectangle() {
    // Ray lands 0.02 m outside the right edge (u = 0.92).
    val dir = V3(0.72f, 0f, 1.3f).normalized()
    assertNull(DragRules.rayHitsRect(hand, dir, wall))
    assertNotNull(DragRules.rayHitsRect(hand, dir, wall, margin = 0.03f))
  }

  @Test
  fun tiltedWallIsHitInItsOwnPlane() {
    // Wall turned 90 degrees about y: faces along x, 2 m to the right of the origin.
    val side = PanelRect(V3(2f, 1.3f, 0f), V3(0f, 0f, -1f), V3(0f, 1f, 0f), V3(1f, 0f, 0f), 0.9f, 0.6f)
    val hit = DragRules.rayHitsRect(V3(0f, 1.3f, 0f), V3(1f, 0f, -0.2f).normalized(), side)
    assertNotNull(hit)
    assertEquals(0.4f, hit!!.u, 0.01f)
  }

  @Test
  fun ghostKeepsTheWallDistanceFromTheHead() {
    val head = V3(0f, 1.5f, 0f)
    for (dir in listOf(V3(0f, 0f, 1f), V3(1f, 0.3f, 1f), V3(-1f, -0.2f, 0.4f), V3(0f, 1f, 0f))) {
      val p = DragRules.ghostPosition(head, hand, dir, 1.6f)
      assertEquals(1.6f, (p - head).length, eps)
    }
  }

  @Test
  fun ghostSitsOnTheRayWhenItReachesThatFar() {
    val head = V3(0f, 1.5f, 0f)
    val dir = V3(0.3f, 0.1f, 1f).normalized()
    val p = DragRules.ghostPosition(head, hand, dir, 1.6f)
    val along = (p - hand).normalized()
    assertEquals(dir.x, along.x, eps)
    assertEquals(dir.y, along.y, eps)
    assertEquals(dir.z, along.z, eps)
  }

  @Test
  fun ghostFacesAwayFromTheUserLevel() {
    val head = V3(0f, 1.5f, 0f)
    val f = DragRules.facing(head, V3(1f, 2.4f, 1f)) // well above eye level
    assertEquals(0f, f.y, 0f)
    assertEquals(1f, f.length, eps)
    assertEquals(f.x, f.z, eps)
    // Straight above the head: any level direction, never NaN.
    val up = DragRules.facing(head, V3(0f, 3f, 0f))
    assertEquals(1f, up.length, eps)
  }

  @Test
  fun releaseOffTheWallDrops() {
    assertEquals(DragEnd.DROP, DragRules.step(triggerHeld = false, cancelPressed = false, controllerLost = false, rayOnWall = false))
  }

  @Test
  fun releaseOverTheWallCancels() {
    assertEquals(DragEnd.CANCEL, DragRules.step(false, false, false, rayOnWall = true))
  }

  @Test
  fun holdingTheTriggerGoesOn() {
    assertEquals(DragEnd.CONTINUE, DragRules.step(true, false, false, rayOnWall = false))
    assertEquals(DragEnd.CONTINUE, DragRules.step(true, false, false, rayOnWall = true))
  }

  @Test
  fun gripFaceButtonOrLostControllerCancelEvenOffTheWall() {
    assertEquals(DragEnd.CANCEL, DragRules.step(true, cancelPressed = true, controllerLost = false, rayOnWall = false))
    assertEquals(DragEnd.CANCEL, DragRules.step(false, cancelPressed = true, controllerLost = false, rayOnWall = false))
    assertEquals(DragEnd.CANCEL, DragRules.step(false, false, controllerLost = true, rayOnWall = false))
  }

  @Test
  fun pressStartsADragByItselfOnceTheRayLeavesTheWall() {
    assertTrue(DragRules.startsOffWall(triggerHeld = true, rayOnWall = false, gripHeld = false))
    assertTrue(!DragRules.startsOffWall(true, true, false))
    assertTrue(!DragRules.startsOffWall(false, false, false))
    assertTrue(!DragRules.startsOffWall(true, false, gripHeld = true))
  }

  @Test
  fun ghostCaptionSaysWhatReleasingDoes() {
    assertTrue(DragRules.ghostHint(rayOnWall = true, replaces = false).contains("cancel"))
    assertTrue(DragRules.ghostHint(rayOnWall = false, replaces = false).contains("new screen"))
    assertTrue(DragRules.ghostHint(rayOnWall = false, replaces = true).contains("replaces"))
  }
}
