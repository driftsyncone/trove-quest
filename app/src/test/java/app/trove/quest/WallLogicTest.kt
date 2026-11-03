package app.trove.quest

import app.trove.quest.api.Card
import app.trove.quest.api.Ref
import app.trove.quest.ui.WallContent
import app.trove.quest.ui.WallLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallLogicTest {
  @Test
  fun contentStateFollowsDataThenLoadingThenError() {
    assertEquals(WallContent.GRID, WallLogic.content(loading = true, error = null, sceneCount = 12, filtered = false))
    assertEquals(WallContent.GRID, WallLogic.content(loading = false, error = "page 2 failed", sceneCount = 12, filtered = false))
    assertEquals(WallContent.LOADING, WallLogic.content(true, null, 0, false))
    assertEquals(WallContent.ERROR, WallLogic.content(false, "no route", 0, true))
    assertEquals(WallContent.EMPTY_FILTERED, WallLogic.content(false, null, 0, true))
    assertEquals(WallContent.EMPTY, WallLogic.content(false, null, 0, false))
  }

  @Test
  fun filteredMeansSearchFacetOrTab() {
    assertFalse(WallLogic.isFiltered("", 0, null))
    assertTrue(WallLogic.isFiltered("a", 0, null))
    assertTrue(WallLogic.isFiltered("", 1, null))
    assertTrue(WallLogic.isFiltered("", 0, "7"))
  }

  @Test
  fun sessionGroupDependsOnOpenScreens() {
    val open = WallLogic.session(openScreens = 2, savedScreens = 3)
    assertTrue(open.openIn)
    assertFalse(open.room)
    assertNull(open.restoreLabel)

    val none = WallLogic.session(openScreens = 0, savedScreens = 3)
    assertFalse(none.openIn)
    assertTrue(none.room)
    assertEquals("Restore last 3 screens", none.restoreLabel)
    assertEquals("Restore last screen", WallLogic.session(0, 1).restoreLabel)
    assertNull(WallLogic.session(0, null).restoreLabel)
  }

  @Test
  fun labels() {
    assertEquals("New screen 2/6", WallLogic.newScreenLabel(2, 6, full = false))
    assertEquals("New screen 6/6 (replaces)", WallLogic.newScreenLabel(6, 6, full = true))
    assertEquals("Loading…", WallLogic.countLabel(true, 40))
    assertEquals("1 scene", WallLogic.countLabel(false, 1))
    assertEquals("0 scenes", WallLogic.countLabel(false, 0))
    assertEquals("Sort: Title", WallLogic.sortSummary("Title"))
  }

  @Test
  fun resumeFractionIsClampedAndOptional() {
    assertNull(WallLogic.resumeFraction(null, 600.0))
    assertNull(WallLogic.resumeFraction(0, 600.0))
    assertNull(WallLogic.resumeFraction(5_000, 0.0))
    assertEquals(0.5f, WallLogic.resumeFraction(300_000, 600.0)!!, 0.001f)
    assertEquals(1f, WallLogic.resumeFraction(900_000, 600.0)!!, 0.001f)
  }

  @Test
  fun cardBadgeAndMeta() {
    val flat = Card(id = "1", title = "t", width = 1920, height = 1080)
    assertEquals("1080p", WallLogic.qualityBadge(flat))
    val sbs = flat.copy(width = 7680, height = 2160, projection = "sbs")
    assertEquals("8K · 3D SBS", WallLogic.qualityBadge(sbs))
    assertNull(WallLogic.qualityBadge(Card(id = "2", title = "t")))
    assertEquals("Studio · 2024-05-01", WallLogic.cardMeta(flat.copy(studio = Ref("3", "Studio"), date = "2024-05-01")))
    assertEquals("", WallLogic.cardMeta(flat))
  }
}
