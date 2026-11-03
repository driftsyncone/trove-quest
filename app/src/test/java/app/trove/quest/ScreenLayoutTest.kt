package app.trove.quest

import app.trove.quest.api.Projection
import app.trove.quest.player.ScreenLayout
import app.trove.quest.player.Shape
import app.trove.quest.player.Stereo
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLayoutTest {
  @Test
  fun mapsEveryServerProjection() {
    val expected =
        mapOf(
            "flat" to ScreenLayout(Shape.QUAD, Stereo.NONE),
            "sbs" to ScreenLayout(Shape.QUAD, Stereo.LEFT_RIGHT),
            "ou" to ScreenLayout(Shape.QUAD, Stereo.UP_DOWN),
            "sbs180" to ScreenLayout(Shape.EQUIRECT_180, Stereo.LEFT_RIGHT),
            "ou180" to ScreenLayout(Shape.EQUIRECT_180, Stereo.UP_DOWN),
            "mono180" to ScreenLayout(Shape.EQUIRECT_180, Stereo.NONE),
            "mono360" to ScreenLayout(Shape.EQUIRECT_360, Stereo.NONE),
            "ou360" to ScreenLayout(Shape.EQUIRECT_360, Stereo.UP_DOWN),
            "sbs360" to ScreenLayout(Shape.EQUIRECT_360, Stereo.LEFT_RIGHT),
            "fisheye190" to ScreenLayout(Shape.FISHEYE, Stereo.LEFT_RIGHT, 190f),
            "fisheye200" to ScreenLayout(Shape.FISHEYE, Stereo.LEFT_RIGHT, 200f),
            "fisheye220" to ScreenLayout(Shape.FISHEYE, Stereo.LEFT_RIGHT, 220f),
        )
    for ((id, layout) in expected) assertEquals(id, layout, ScreenLayout.of(Projection.fromId(id)))
  }

  @Test
  fun unknownIdFallsBackToFlat() {
    assertEquals(Projection.FLAT, Projection.fromId("cubemap"))
    assertEquals(Projection.FLAT, Projection.fromId(null))
  }

  @Test
  fun eyeAspectSplitsStereoFrames() {
    // Full SBS 7680x2160 and 5120x1440 are two 16:9 eyes.
    assertEquals(16f / 9f, ScreenLayout.of(Projection.SBS).eyeAspect(7680, 2160), 1e-3f)
    assertEquals(16f / 9f, ScreenLayout.of(Projection.SBS).eyeAspect(5120, 1440), 1e-3f)
    assertEquals(16f / 9f, ScreenLayout.of(Projection.OU).eyeAspect(1920, 2160), 1e-3f)
    assertEquals(16f / 9f, ScreenLayout.of(Projection.FLAT).eyeAspect(1920, 1080), 1e-3f)
    assertEquals(16f / 9f, ScreenLayout.of(Projection.FLAT).eyeAspect(0, 0), 1e-3f)
  }
}
