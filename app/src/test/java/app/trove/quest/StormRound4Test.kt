package app.trove.quest

import app.trove.quest.api.Facet
import app.trove.quest.api.FacetType
import app.trove.quest.api.Ref
import app.trove.quest.api.Scene
import app.trove.quest.api.SceneQuery
import app.trove.quest.storm.CentreUpgrade
import app.trove.quest.storm.ClipKind
import app.trove.quest.storm.DecoderBudget
import app.trove.quest.storm.SceneCache
import app.trove.quest.storm.ScrubRules
import app.trove.quest.storm.Storm
import app.trove.quest.storm.StormCounts
import app.trove.quest.storm.StormInfo
import app.trove.quest.storm.StormLayout
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StormRound4Test {
  // ---- the scrub bar is 2/3 of the centre's RENDERED width, whatever tile became the centre ----

  /** What StormManager.placeScrub feeds [ScrubRules.placement]: the tile's registered size times its layout scale. */
  private fun barWorldWidthFor(tileBaseW: Float, layoutScale: Float): Float {
    val w = tileBaseW * layoutScale
    val h = (tileBaseW * 9f / 16f) * layoutScale
    return ScrubRules.placement(w, h).worldW
  }

  @Test
  fun barIsTwoThirdsOfTheCentreForAnyTileThatBecameTheCentre() {
    val cw = StormLayout.FOCUS_CENTRE_W_M
    for (ring in Storm.FOCUS_RING_COUNTS) {
      val ringW = StormLayout.focus(ring)[1].widthM
      // Never swapped: registered at the centre's size, scale 1.
      assertEquals(cw * 2f / 3f, barWorldWidthFor(cw, 1f), 0.0001f)
      // A ring tile swapped in: registered small, scaled up to the centre's width.
      assertEquals("ring $ring after a swap", cw * 2f / 3f, barWorldWidthFor(ringW, cw / ringW), 0.0001f)
      // The old bug: the bar inherited the tile's layout scale (cw / ringW) on top of its own metre width.
      val oldWidth = ScrubRules.barWidthM(cw) * (cw / ringW)
      assertTrue("ring $ring: old bar was ${oldWidth / cw} x the centre", oldWidth > cw * 1.05f)
    }
  }

  @Test
  fun barStaysInsideTheCentresBottomAtAnyScale() {
    val cw = StormLayout.FOCUS_CENTRE_W_M
    for (renderedW in listOf(cw, 1.0f, 1.8f)) {
      val h = renderedW * 9f / 16f
      val p = ScrubRules.placement(renderedW, h)
      assertEquals(renderedW * 2f / 3f, p.worldW, 0.0001f)
      val bottom = p.offsetY - p.worldH / 2
      val top = p.offsetY + p.worldH / 2
      assertTrue(bottom > -h / 2 && top < h / 2)
      assertEquals(h * ScrubRules.BAR_BOTTOM_MARGIN_FRACTION, bottom + h / 2, 0.0001f)
    }
  }

  // ---- the centre's upgrade ----

  private val perTile = DecoderBudget.PREVIEW_BLOCKS

  @Test
  fun originalCentreIsKept() {
    assertEquals(CentreUpgrade.Step.KEEP, CentreUpgrade.decide(ClipKind.SEEK, 1920, 1080, 9, 0, perTile).step)
  }

  @Test
  fun previewCentreUpgradesAtOnceWhenItFits() {
    val d = CentreUpgrade.decide(ClipKind.PREVIEW, 1920, 1080, 9, 0, perTile)
    assertEquals(CentreUpgrade.Step.UPGRADE, d.step)
    assertEquals(0, d.dropRing)
  }

  @Test
  fun withoutRoomRingTilesCloseRatherThanLeavingTheCentreOnThePreview() {
    val screens = 4_100_000L
    val d = CentreUpgrade.decide(ClipKind.PREVIEW, 3840, 2160, 13, screens, perTile)
    assertEquals(CentreUpgrade.Step.DROP_RING_THEN_UPGRADE, d.step)
    assertTrue(d.dropRing in 1..12)
    // Dropping exactly that many fits, one fewer does not.
    assertTrue(DecoderBudget.centreFits(3840, 2160, 13 - d.dropRing, screens, perTile))
    assertFalse(DecoderBudget.centreFits(3840, 2160, 13 - d.dropRing + 1, screens, perTile))
  }

  @Test
  fun whenScreensUseTheRoomAlonePreviewIsFrozenNotJumping() {
    val d = CentreUpgrade.decide(ClipKind.PREVIEW, 7680, 4320, 9, DecoderBudget.MAX_BLOCKS_PER_S, perTile)
    assertEquals(CentreUpgrade.Step.FREEZE_PREVIEW, d.step)
    assertNull(DecoderBudget.ringDropsNeeded(7680, 4320, 9, DecoderBudget.MAX_BLOCKS_PER_S, perTile))
  }

  @Test
  fun planKeepsTheCentresOriginalRoomSoARingTileIsNotReopened() {
    val screens = 4_100_000L
    val reserve = DecoderBudget.centreReserve(3840, 2160, perTile)
    val ringOnly = DecoderBudget.plan(13, 0, screens, perTile).tiles
    val withReserve = DecoderBudget.plan(13, 0, screens + reserve, perTile).tiles
    assertTrue(withReserve < ringOnly)
    // The planned count is exactly what lets the original fit.
    assertTrue(DecoderBudget.centreFits(3840, 2160, withReserve, screens, perTile))
    assertFalse(DecoderBudget.centreFits(3840, 2160, withReserve + 1, screens, perTile))
  }

  // ---- the info panel's place ----

  private fun xOf(horizM: Float, yawDeg: Float) = horizM * sin(Math.toRadians(yawDeg.toDouble())).toFloat()

  @Test
  fun infoPanelSitsRightOfTheCentreClearOfTheRingAndInsideTheView() {
    val cw = StormLayout.FOCUS_CENTRE_W_M
    val ch = cw * 9f / 16f
    val maxHalfW = StormLayout.FOCUS_RADIUS_M * tan(StormLayout.FOCUS_MAX_HALF_YAW_DEG * Math.PI / 180).toFloat()
    for (collapsed in listOf(false, true)) {
      val p = StormLayout.infoPose(collapsed)
      val x = xOf(p.horizM, p.yawDeg)
      assertEquals("clear of the centre by the gap", cw / 2 + StormLayout.FOCUS_GAP_M, x - p.widthM / 2, 0.001f)
      assertTrue("inside the +-34 degree view (collapsed=$collapsed)", x + p.widthM / 2 <= maxHalfW + 0.001f)
      assertEquals(ch, p.heightM, 0.0001f)
      // As tall as the centre, so no ring row (they start above / below the centre) is touched.
      for (ring in Storm.FOCUS_RING_COUNTS + listOf(1, 3, 5, 7, 11, 20)) {
        for (t in StormLayout.focus(ring).drop(1)) {
          val tx = xOf(t.horizM, t.yawDeg)
          val ty = t.dyM - StormLayout.FOCUS_DY_M
          val overlapX = abs(tx - x) < (t.widthM + p.widthM) / 2
          val overlapY = abs(ty) < (t.heightM + p.heightM) / 2
          assertFalse("ring $ring tile overlaps the info panel (collapsed=$collapsed)", overlapX && overlapY)
        }
      }
    }
    // The collapsed tab keeps the expanded panel's left edge.
    val e = StormLayout.infoPose(false)
    val c = StormLayout.infoPose(true)
    assertEquals(xOf(e.horizM, e.yawDeg) - e.widthM / 2, xOf(c.horizM, c.yawDeg) - c.widthM / 2, 0.001f)
  }

  // ---- the info panel's rules ----

  private fun scene() =
      Scene(
          id = "s1", title = "T",
          studio = Ref("st1", "Studio"),
          performers = listOf(Ref("p1", "A"), Ref("p2", "B")),
          tags = listOf(Ref("t1", "Tag")),
      )

  @Test
  fun facetsAreStudioThenPerformersThenTags() {
    val f = StormInfo.facets(scene())
    assertEquals(listOf(FacetType.STUDIO, FacetType.PERFORMER, FacetType.PERFORMER, FacetType.TAG), f.map { it.type })
    assertEquals(listOf("st1", "p1", "p2", "t1"), f.map { it.id })
    assertTrue(StormInfo.facets(Scene(id = "x", title = "x")).isEmpty())
  }

  @Test
  fun facetChipReStormsWithJustThatFacet() {
    val last = SceneQuery(filterId = "f", q = "text", facets = listOf(Facet(FacetType.TAG, "t0", "Old")), sort = "date", page = 3, perPage = 12)
    val chip = Facet(FacetType.PERFORMER, "p1", "A")
    val q = StormInfo.restormQuery(last, chip)
    assertEquals(listOf(chip), q.facets)
    assertEquals("", q.q)
    assertEquals(null, q.filterId)
    assertEquals(1, q.page)
    assertEquals("date", q.sort)
    assertEquals(listOf(chip), StormInfo.restormQuery(SceneQuery(), chip).facets)
  }

  @Test
  fun sceneCacheKeepsTheMostRecentlyUsed() {
    val c = SceneCache<String>(max = 2)
    c.put("a", "1")
    c.put("b", "2")
    assertEquals("1", c["a"]) // a is now the freshest
    c.put("c", "3")
    assertEquals(2, c.size)
    assertNull(c["b"])
    assertEquals("1", c["a"])
    assertEquals("3", c["c"])
  }

  @Test
  fun auditCountsTheInfoPanel() {
    val ok = StormCounts(true, 9, 9, 9, 9, 9, 1, 1, scrubPanels = 1, scrubExpected = 1, infoPanels = 1, infoExpected = 1)
    assertTrue(ok.problems().isEmpty())
    assertTrue(ok.copy(infoPanels = 2).problems().any { "info panels" in it })
    assertTrue(ok.copy(infoPanels = 0).problems().any { "info panels" in it })
    val off = StormCounts(false, 0, 0, 0, 0, 0, 0, 0, infoPanels = 1)
    assertTrue(off.problems().any { "info panel" in it })
    assertNotEquals(ok.toString(), ok.copy(infoPanels = 0).toString())
  }
}
