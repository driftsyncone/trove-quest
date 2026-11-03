package app.trove.quest

import app.trove.quest.storm.ClipKind
import app.trove.quest.storm.PreviewMontage
import app.trove.quest.storm.ScrubHover
import app.trove.quest.storm.ScrubRules
import app.trove.quest.storm.ScrubRules.SeekStep
import app.trove.quest.storm.StormCounts
import app.trove.quest.storm.StormLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubRulesTest {
  // ---- the bar sits inside the centre ----

  @Test
  fun barIsTwoThirdsOfTheCentreAndInsideItsBottom() {
    val cw = StormLayout.FOCUS_CENTRE_W_M
    val ch = cw * 9f / 16f
    assertEquals(cw * 2f / 3f, ScrubRules.barWidthM(cw), 0.0001f)
    val dy = ScrubRules.barOffsetY(ch)
    val barBottom = dy - ScrubRules.BAR_H_M / 2
    val barTop = dy + ScrubRules.BAR_H_M / 2
    assertTrue("inside the tile", barBottom > -ch / 2 && barTop < ch / 2)
    assertEquals("4 % margin", ch * 0.04f, barBottom - (-ch / 2), 0.0001f)
  }

  // ---- hover show / hide timing ----

  @Test
  fun barShowsWhileHoveringAndHidesAfterTheDelay() {
    val h = ScrubHover()
    assertFalse(h.update(0, false)) // never hovered
    assertTrue(h.update(1_000, true))
    assertTrue(h.update(1_500, true))
    assertTrue(h.update(1_501, false)) // just left
    assertTrue(h.update(1_500 + ScrubRules.HIDE_DELAY_MS - 1, false))
    assertFalse(h.update(1_500 + ScrubRules.HIDE_DELAY_MS, false))
    assertFalse(h.update(60_000, false))
  }

  @Test
  fun hoveringAgainRestartsTheDelay() {
    val h = ScrubHover()
    h.update(0, true)
    assertTrue(h.update(1_400, false))
    assertTrue(h.update(1_450, true))
    assertTrue(h.update(2_900, false)) // 1450 ms after the second hover
    assertFalse(h.update(2_950, false))
  }

  @Test
  fun resetHidesAtOnce() {
    val h = ScrubHover()
    h.update(100, true)
    h.reset()
    assertFalse(h.update(200, false))
  }

  @Test
  fun delayIsOneAndAHalfSeconds() = assertEquals(1_500L, ScrubRules.HIDE_DELAY_MS)

  // ---- seek mapping ----

  @Test
  fun fractionMapsToSceneTimeAndBack() {
    assertEquals(0L, ScrubRules.fractionToMs(0f, 600.0))
    assertEquals(300_000L, ScrubRules.fractionToMs(0.5f, 600.0))
    assertEquals(600_000L, ScrubRules.fractionToMs(1f, 600.0))
    assertEquals(600_000L, ScrubRules.fractionToMs(3f, 600.0))
    assertEquals(0L, ScrubRules.fractionToMs(-1f, 600.0))
    assertEquals(0L, ScrubRules.fractionToMs(0.5f, 0.0))
    assertEquals(0.25f, ScrubRules.fraction(150_000, 600.0), 0.0001f)
    assertEquals(1f, ScrubRules.fraction(900_000, 600.0), 0f)
    assertEquals(0f, ScrubRules.fraction(5, 0.0), 0f)
  }

  @Test
  fun stickStepsStayInsideTheScene() {
    assertEquals(70_000L, ScrubRules.stepTarget(60_000, 10_000, 600.0))
    assertEquals(0L, ScrubRules.stepTarget(4_000, -10_000, 600.0))
    assertEquals(600_000L, ScrubRules.stepTarget(595_000, 30_000, 600.0))
  }

  @Test
  fun aPreviewSeeksByUpgradingToTheOriginalAtTheTargetTime() {
    assertEquals(SeekStep.SEEK_ORIGINAL, ScrubRules.seekStep(ClipKind.SEEK, canUpgrade = true))
    assertEquals(SeekStep.SEEK_ORIGINAL, ScrubRules.seekStep(ClipKind.SEEK, canUpgrade = false))
    assertEquals(SeekStep.UPGRADE_AT, ScrubRules.seekStep(ClipKind.PREVIEW, canUpgrade = true))
    assertEquals(SeekStep.NONE, ScrubRules.seekStep(ClipKind.PREVIEW, canUpgrade = false))
  }

  @Test
  fun elapsedOfAnOriginalIsItsPlayerPosition() {
    assertEquals(123_456L, ScrubRules.elapsedMs(ClipKind.SEEK, 123_456, 0, 600.0, PreviewMontage.DEFAULT))
    assertEquals(0L, ScrubRules.elapsedMs(ClipKind.SEEK, -5, 0, 600.0, PreviewMontage.DEFAULT))
  }

  @Test
  fun elapsedOfAPreviewGoesThroughTheMontage() {
    // 12 clips of 0.75 s from a 1200 s scene: clip i starts at i * 100 s.
    val m = PreviewMontage.DEFAULT
    assertEquals(0L, ScrubRules.elapsedMs(ClipKind.PREVIEW, 0, 9_000, 1200.0, m))
    assertEquals(300_250L, ScrubRules.elapsedMs(ClipKind.PREVIEW, 2_500, 9_000, 1200.0, m)) // clip 3, 0.25 s in
    assertEquals(1_000_000L + 500L, ScrubRules.elapsedMs(ClipKind.PREVIEW, 8_000, 9_000, 1200.0, m)) // clip 10, 0.5 s in
    // And what the bar shows after the upgrade at that time is that time.
    val target = ScrubRules.elapsedMs(ClipKind.PREVIEW, 2_500, 9_000, 1200.0, m)
    assertEquals(target, ScrubRules.elapsedMs(ClipKind.SEEK, target, 0, 1200.0, m))
  }

  // ---- one scrub panel in the audit ----

  private val ok = StormCounts(true, 9, 9, 9, 9, 9, 1, 1)

  @Test
  fun auditExpectsExactlyOneScrubPanelInFocusOnly() {
    assertTrue(ok.problems().isEmpty()) // wall / surround: none, none expected
    assertTrue(ok.copy(scrubPanels = 1, scrubExpected = 1).problems().isEmpty())
    assertTrue(ok.copy(scrubPanels = 2, scrubExpected = 1).problems().isNotEmpty())
    assertTrue(ok.copy(scrubPanels = 0, scrubExpected = 1).problems().isNotEmpty())
    assertTrue(ok.copy(scrubPanels = 1, scrubExpected = 0).problems().isNotEmpty())
    val off = StormCounts(false, 0, 0, 0, 0, 0, 0, 0)
    assertTrue(off.problems().isEmpty())
    assertTrue(off.copy(scrubPanels = 1).problems().isNotEmpty())
  }
}
