package app.trove.quest

import app.trove.quest.api.SceneResponse
import app.trove.quest.api.TroveApi
import app.trove.quest.ui.detailMeta
import app.trove.quest.ui.fmtSize
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneDetailsTest {
  // Shape of GET /api/scenes/<id> (server/stash.ts getScene); made-up neutral values.
  private val json =
      """{"scene":{"id":"5","title":"Test scene","date":"2024-05-01","duration":3725.4,"width":7680,"height":3840,
        "codec":"hevc","interactive":false,"studio":{"id":"3","name":"Some Studio"},
        "performers":[{"id":"11","name":"Performer One"}],"thumbVersion":"1","projection":"sbs180",
        "detectedProjection":"sbs180","details":null,"tags":[{"id":"40","name":"Some Tag"}],
        "markers":[{"id":"1","title":"First","seconds":12.5},{"id":"2","title":"Second","seconds":600}],
        "file":{"name":"file.mp4","size":6979321856,"fps":59.94,"audio":"aac"},
        "streams":[{"label":"Direct stream","mime":"video/mp4","url":"/media/scene/5/stream"}],"playCount":3}}"""

  @Test
  fun sceneDetailsParseWithTagsPerformersAndMarkers() {
    val s = TroveApi.JSON_PARSER.decodeFromString<SceneResponse>(json).scene
    assertEquals(listOf("Some Tag"), s.tags.map { it.name })
    assertEquals("Some Studio", s.studio?.name)
    assertEquals(listOf(12.5, 600.0), s.markers.map { it.seconds })
    assertEquals("2024-05-01 · 1:02:05 · 8K HEVC 60 fps · 6.5 GB", detailMeta(s))
  }

  @Test
  fun sizesReadLikeTheWebUi() {
    assertEquals("512 MB", fmtSize(512L * 1024 * 1024))
    assertEquals("1.0 GB", fmtSize(1024L * 1024 * 1024))
  }
}
