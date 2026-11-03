package app.trove.quest

import app.trove.quest.storm.DecoderBudget
import app.trove.quest.storm.LayerBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerBudgetTest {
  private val pv = DecoderBudget.PREVIEW_BLOCKS

  @Test
  fun usableIsMaxMinusMargin() {
    assertEquals(14, LayerBudget.USABLE)
    assertTrue(LayerBudget.USABLE < LayerBudget.MAX_LAYERS)
  }

  @Test
  fun nonTileLayersCountScreensAndWall() {
    assertEquals(0, LayerBudget.nonTileLayers(0, false))
    assertEquals(1, LayerBudget.nonTileLayers(0, true))
    assertEquals(3, LayerBudget.nonTileLayers(2, true))
    assertEquals(2, LayerBudget.nonTileLayers(2, false))
    assertEquals(5, LayerBudget.nonTileLayers(2, true, otherUiLayers = 2))
    assertEquals(0, LayerBudget.nonTileLayers(-1, false))
  }

  @Test
  fun eyeBufferPanelsAreNotCounted() {
    // Scrub bar, info panel, control panel, legend, ghost: no argument at all, they cost no layer.
    val withPanels = LayerBudget.nonTileLayers(screens = 0, libraryShown = false)
    assertEquals(0, withPanels)
    assertEquals(14, LayerBudget.tileRoom(withPanels))
  }

  @Test
  fun tileRoomAndOverBudget() {
    assertEquals(13, LayerBudget.tileRoom(1))
    assertEquals(0, LayerBudget.tileRoom(14))
    assertEquals(0, LayerBudget.tileRoom(20))
    assertFalse(LayerBudget.overBudget(LayerBudget.total(13, 1)))
    assertTrue(LayerBudget.overBudget(LayerBudget.total(14, 1)))
  }

  @Test
  fun stormNineTilesWithScrubBarFitsTheBugCase() {
    // 0.4.8: 9 tiles + centre + panels = 17 layers. Now panels are free: 9 tiles + wall = 10.
    val p = DecoderBudget.plan(9, 0, 0, pv, LayerBudget.tileRoom(LayerBudget.nonTileLayers(0, false)))
    assertEquals(9, p.tiles)
    assertNull(p.note())
  }

  @Test
  fun planCappedByLayersWithLibraryAndScreens() {
    fun tiles(screens: Int, wall: Boolean, req: Int = 25) =
        DecoderBudget.plan(req, screens, 0, pv, LayerBudget.tileRoom(LayerBudget.nonTileLayers(screens, wall)))
    // No library, no screens: the decoder (14 instances) and the layers (14) agree.
    assertEquals(14, tiles(0, false).tiles)
    // Library over Storm: one layer less, layers are the reason.
    val withWall = tiles(0, true)
    assertEquals(13, withWall.tiles)
    assertEquals(DecoderBudget.Limit.LAYERS, withWall.limit)
    assertEquals("13 of 25 tiles: display layer limit (14 layers with the screens and library).", withWall.note())
    // Screens: instances and layers both shrink by one each; the wall adds the extra layer.
    assertEquals(8, tiles(6, false).tiles)
    assertEquals(7, tiles(6, true).tiles)
    assertEquals(DecoderBudget.Limit.LAYERS, tiles(6, true).limit)
  }

  @Test
  fun planNeverExceedsLayerBudgetForAnyCombination() {
    for (screens in 0..6) for (wall in listOf(false, true)) for (req in 1..25) {
      val other = LayerBudget.nonTileLayers(screens, wall)
      val p = DecoderBudget.plan(req, screens, 0, pv, LayerBudget.tileRoom(other))
      assertFalse("screens=$screens wall=$wall req=$req", LayerBudget.overBudget(LayerBudget.total(p.tiles, other)))
      assertTrue(p.tiles + screens + DecoderBudget.SPARE_INSTANCES <= DecoderBudget.MAX_INSTANCES)
    }
  }

  @Test
  fun decoderLimitStillWinsWhenTighter() {
    // Blocks: a heavy screen leaves room for fewer tiles than layers allow.
    val heavy = DecoderBudget.screenBlocks(listOf(7680 to 2160), focused = 0)
    val p = DecoderBudget.plan(16, 1, heavy, DecoderBudget.SEEK_BLOCKS, LayerBudget.tileRoom(1))
    assertEquals(DecoderBudget.Limit.BLOCKS, p.limit)
  }

  @Test
  fun noRoomNoteWhenLayersAreFull() {
    val p = DecoderBudget.plan(9, 0, 0, pv, 0)
    assertEquals(0, p.tiles)
    assertEquals("No layer room for tiles: close some screens.", p.note())
  }

  @Test
  fun defaultPlanIgnoresLayers() {
    assertEquals(9, DecoderBudget.plan(9, 0, 0, pv).tiles)
  }
}
