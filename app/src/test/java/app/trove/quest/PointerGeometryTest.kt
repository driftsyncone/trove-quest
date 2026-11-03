package app.trove.quest

import app.trove.quest.drag.PanelRect
import app.trove.quest.drag.V3
import app.trove.quest.input.PointerRegion
import app.trove.quest.input.PointerTargets
import app.trove.quest.input.Target
import app.trove.quest.input.rectFacing
import app.trove.quest.storm.StormLayout
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

/** Realistic poses: head at eye height, tiles 2.2 m ahead facing it, a controller 0.3 m below the eyes. */
class PointerGeometryTest {
  private val head = V3(0f, 1.6f, 0f)
  private val hand = V3(0.25f, 1.3f, 0.3f)

  private fun aim(from: V3, at: V3) = (at - from).normalized()

  private fun stormRegions(ring: Int, yawOffsetRad: Double = 0.0): List<PointerRegion> =
      StormLayout.focus(ring).mapIndexed { i, tp ->
        val a = yawOffsetRad + Math.toRadians(tp.yawDeg.toDouble())
        val c = V3(head.x + sin(a).toFloat() * tp.horizM, head.y + tp.dyM, head.z + cos(a).toFloat() * tp.horizM)
        PointerRegion(if (i == 0) Target.CENTRE else Target.TILE, rectFacing(c, head, tp.widthM / 2, tp.heightM / 2))
      }

  private fun centreOf(r: List<PointerRegion>) = r[0].rect.center

  @Test
  fun aRayAtTheStormCentreHitsTheCentre() {
    for (ring in listOf(6, 8, 12)) {
      val regions = stormRegions(ring)
      val c = centreOf(regions)
      assertEquals(Target.CENTRE, PointerTargets.resolve(hand, aim(hand, c), regions))
      // Towards the lower and upper parts and the sides of the centre video.
      for ((dx, dy) in listOf(0.6f to 0f, -0.6f to 0f, 0f to 0.3f, 0f to -0.3f, 0.6f to -0.3f)) {
        val target = V3(c.x + dx, c.y + dy, c.z)
        assertEquals("ring $ring at ($dx,$dy)", Target.CENTRE, PointerTargets.resolve(hand, aim(hand, target), regions))
      }
    }
  }

  @Test
  fun aRayPastTheCentresEdgeMissesIt() {
    val regions = stormRegions(6)
    val c = centreOf(regions)
    // 0.8 m to the side is beyond the 0.7 m half width and between ring tiles' columns: not the centre.
    assertEquals(Target.NOTHING, PointerTargets.resolve(hand, aim(hand, V3(c.x + 1.2f, c.y, c.z)), regions))
  }

  @Test
  fun aRayAtARingTileHitsTheTileNotTheCentre() {
    val regions = stormRegions(12)
    for (i in 1 until regions.size) {
      assertEquals("tile $i", Target.TILE, PointerTargets.resolve(hand, aim(hand, regions[i].rect.center), regions))
    }
  }

  @Test
  fun theHeadsTurnedWorldStillWorks() {
    // The layout built while looking 70 degrees to the side.
    val regions = stormRegions(12, Math.toRadians(70.0))
    assertEquals(Target.CENTRE, PointerTargets.resolve(hand, aim(hand, centreOf(regions)), regions))
  }

  @Test
  fun aRayFromBehindATileIsNotAHit() {
    val regions = stormRegions(6)
    val behind = V3(0f, 1.5f, 5f)
    assertEquals(Target.NOTHING, PointerTargets.resolve(behind, V3(0f, 0f, 1f), regions))
  }

  @Test
  fun tilesFaceTheHeadWhateverTheSignOfTheirNormal() {
    val r = rectFacing(V3(0f, 1.5f, 2.2f), head, 0.7f, 0.4f)
    assertEquals(-1f, r.normal.z, 0.0001f) // toward the head
    assertEquals(0f, r.normal.y, 0.0001f)
    assertEquals(1f, Math.abs(r.right.x), 0.0001f)
  }

  /** A quad screen with the normal either way round (the SDK's panel front is local -Z or +Z): the hit must not care. */
  @Test
  fun aScreenHitsWithEitherNormalSign() {
    for (sign in listOf(-1f, 1f)) {
      val screen = PanelRect(V3(0f, 1.5f, 2.2f), V3(sign, 0f, 0f), V3(0f, 1f, 0f), V3(0f, 0f, sign), 0.9f, 0.5f)
      val regions = listOf(PointerRegion(Target.VIDEO, screen))
      assertEquals(Target.VIDEO, PointerTargets.resolve(hand, aim(hand, screen.center), regions))
      assertEquals(Target.VIDEO, PointerTargets.resolve(hand, aim(hand, V3(0.8f, 1.2f, 2.2f)), regions))
      assertEquals(Target.NOTHING, PointerTargets.resolve(hand, aim(hand, V3(1.2f, 1.5f, 2.2f)), regions))
    }
  }
}
