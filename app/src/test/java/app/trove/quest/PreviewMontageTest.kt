package app.trove.quest

import app.trove.quest.storm.PreviewMontage
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewMontageTest {
  private val d = PreviewMontage.DEFAULT // 12 x 0.75 s, no excludes
  private fun near(exp: Double, act: Double) = assertEquals(exp, act, 1e-6)

  @Test
  fun defaultsSampleEvenlyAcrossTheScene() {
    near(0.0, d.sceneTime(0.0, 600.0))
    near(0.375, d.sceneTime(0.375, 600.0)) // inside clip 0
    near(50.0, d.sceneTime(0.75, 600.0)) // clip 1 starts at one step (600 / 12)
    near(250.25, d.sceneTime(4.0, 600.0)) // clip 5, 0.25 s in
    near(550.74, d.sceneTime(8.99, 600.0)) // clip 11, 0.74 s in
  }

  @Test
  fun secondsExcludesShiftAndShrinkTheSpan() {
    val m = PreviewMontage(12, 0.75, "30", "30") // span 540, step 45
    near(30.0, m.sceneTime(0.0, 600.0))
    near(75.0 + 0.5, m.sceneTime(0.75 + 0.5, 600.0))
  }

  @Test
  fun percentageExcludes() {
    val m = PreviewMontage(12, 0.75, "2%", "10") // 12 s at the start, 10 s at the end
    val step = (600.0 - 12.0 - 10.0) / 12
    near(12.0, m.sceneTime(0.0, 600.0))
    near(12.0 + step, m.sceneTime(0.75, 600.0))
    near(12.0 + 11 * step + 0.1, m.sceneTime(11 * 0.75 + 0.1, 600.0))
    assertEquals(0.12, PreviewMontage.exclude("2%", 6.0), 1e-9)
    assertEquals(0.0, PreviewMontage.exclude("%", 600.0), 1e-9)
    assertEquals(0.0, PreviewMontage.exclude("junk", 600.0), 1e-9)
  }

  @Test
  fun excludesThatEatTheWholeSceneAreIgnored() {
    val m = PreviewMontage(12, 0.75, "400", "400") // like Stash: offset 0, whole duration
    near(50.0, m.sceneTime(0.75, 600.0))
  }

  @Test
  fun sceneShorterThanTheMontageIsOneClip() {
    near(3.0, d.sceneTime(3.0, 5.0)) // 5 s < 12 x 0.75 = 9 s: the preview is the whole scene
    near(5.0, d.sceneTime(7.0, 5.0)) // never past the end
    near(9.0, d.sceneTime(9.0, 9.0)) // exactly 9 s: montage, step 0.75, clip i = scene i x 0.75
  }

  @Test
  fun segmentLengthBelowTheMinimumIsGeneratedAt075() {
    val m = PreviewMontage(10, 0.5, "0", "0") // 0.5 s asked, 0.75 s made; scene 100 s, step 10
    near(10.0 + 0.1, m.sceneTime(0.85, 100.0))
  }

  @Test
  fun measuredPreviewLengthCorrectsSmallDrift() {
    val m = PreviewMontage(12, 0.75, "0", "0")
    // The preview came out 9.6 s (0.8 s clips): position 1.7 s is clip 2, 0.1 s in.
    near(100.0 + 0.1, m.sceneTime(1.7, 600.0, previewDurationSec = 9.6))
    // A length far from segments x clip is not trusted.
    near(50.0, m.sceneTime(0.75, 600.0, previewDurationSec = 30.0))
  }

  @Test
  fun positionsAndDurationsAreClamped() {
    near(0.0, d.sceneTime(-3.0, 600.0))
    near(0.0, d.sceneTime(5.0, 0.0))
    near(550.75 + 0.0, d.sceneTime(100.0, 600.0).coerceAtMost(550.75)) // past the end stays in the last clip
    assertEquals(12_500L, d.sceneTimeMs(0L, 25.0) + 12_500L) // ms wrapper starts at 0
    assertEquals(50_000L, d.sceneTimeMs(750L, 600.0))
  }
}
