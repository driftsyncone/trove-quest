package app.trove.quest

import app.trove.quest.storm.PreviewMontage
import app.trove.quest.storm.Storm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Storm previews start at their random point straight away (StormTile.play), from the montage's expected length. */
class PreviewStartTest {
  @Test
  fun expectedPreviewLengthFollowsStashsMontage() {
    assertEquals(9_000L, PreviewMontage.DEFAULT.previewLengthMs(600.0)) // 12 x 0.75 s
    assertEquals(24_000L, PreviewMontage(segments = 12, segmentDuration = 2.0).previewLengthMs(600.0))
    // Segments under 0.75 s are generated as 0.75 s.
    assertEquals(4_500L, PreviewMontage(segments = 6, segmentDuration = 0.5).previewLengthMs(600.0))
    // A scene shorter than the montage is previewed whole.
    assertEquals(7_500L, PreviewMontage.DEFAULT.previewLengthMs(7.5))
    assertEquals(0L, PreviewMontage.DEFAULT.previewLengthMs(0.0))
  }

  @Test
  fun aRandomStartLeavesThePreviewSomethingToPlay() {
    assertEquals(0L, Storm.previewStartMs(9_000L, 0.0))
    assertEquals(6_300L, Storm.previewStartMs(9_000L, 1.0))
    val mid = Storm.previewStartMs(9_000L, 0.5)
    assertTrue(mid in 3_000L..3_300L)
    // Short or unknown previews start at the beginning (as before).
    assertEquals(0L, Storm.previewStartMs(4_000L, 0.9))
    assertEquals(0L, Storm.previewStartMs(0L, 0.9))
    // Out-of-range randoms are clamped.
    assertEquals(6_300L, Storm.previewStartMs(9_000L, 3.0))
  }
}
