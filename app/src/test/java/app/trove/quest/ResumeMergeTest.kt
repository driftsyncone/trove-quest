package app.trove.quest

import app.trove.quest.play.ActivityReport
import app.trove.quest.play.RemoteResume
import app.trove.quest.play.ResumeMerge
import app.trove.quest.player.ResumePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeMergeTest {
  private val t0 = 1_000_000_000_000L

  @Test
  fun serverUnknownFallsBackToLocal() {
    assertEquals(90_000L, ResumeMerge.merge(null, ResumePoint(90_000, t0)))
    assertNull(ResumeMerge.merge(null, null))
  }

  @Test
  fun serverPointWinsOverAnOlderLocalOne() {
    assertEquals(700_000L, ResumeMerge.merge(RemoteResume(700_000, t0), ResumePoint(90_000, t0 - 1000)))
    assertEquals(700_000L, ResumeMerge.merge(RemoteResume(700_000, null), null))
  }

  @Test
  fun aClearlyNewerLocalPointWinsWatchedOffline() {
    assertEquals(90_000L, ResumeMerge.merge(RemoteResume(700_000, t0), ResumePoint(90_000, t0 + 5 * 60_000)))
    // within clock skew the server wins
    assertEquals(700_000L, ResumeMerge.merge(RemoteResume(700_000, t0), ResumePoint(90_000, t0 + 10_000)))
  }

  @Test
  fun serverZeroMeansFinishedElsewhereUnlessLocalIsNewer() {
    assertNull(ResumeMerge.merge(RemoteResume(0, t0), ResumePoint(90_000, t0 - 1000)))
    assertEquals(90_000L, ResumeMerge.merge(RemoteResume(0, t0), ResumePoint(90_000, t0 + 1000)))
    // never played on the server (pre-upgrade local point) keeps the local one
    assertEquals(90_000L, ResumeMerge.merge(RemoteResume(0, null), ResumePoint(90_000, t0)))
    assertNull(ResumeMerge.merge(RemoteResume(0, t0), null))
  }

  @Test
  fun serverPointsUnderFiveSecondsAreNone() {
    assertNull(ResumeMerge.merge(RemoteResume(3_000, t0), null))
  }

  @Test
  fun remoteOfDropsTheFinishedZoneAndParsesTheTime() {
    val r = ResumeMerge.remoteOf(1790.0, "2026-10-21T10:00:00Z", 1_800_000)
    assertEquals(0L, r.ms)
    assertEquals(java.time.Instant.parse("2026-10-21T10:00:00Z").toEpochMilli(), r.at)
    assertEquals(754_200L, ResumeMerge.remoteOf(754.2, null, 1_800_000).ms)
    assertNull(ResumeMerge.remoteOf(10.0, "garbage", 0).at)
  }

  @Test
  fun requestBodyHasTheServersFields() {
    val j = ActivityReport(754.2, 9.8, 1800.0, final = true, ended = false).toJson()
    assertEquals("""{"position":754.2,"played":9.8,"duration":1800.0,"final":true}""", j)
    assertEquals("""{"position":0.0,"played":0.0}""", ActivityReport(0.0, 0.0).toJson())
  }
}
