package app.trove.quest

import app.trove.quest.play.ActivityReport
import app.trove.quest.play.ActivityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTrackerTest {
  private val dur = 1_800_000L

  /** Plays [seconds] counting, sampled each second from [from]; returns the reports and the next time. */
  private fun play(t: ActivityTracker, from: Long, seconds: Int, pos0: Long = 100_000, counting: Boolean = true): Pair<List<ActivityReport>, Long> {
    val out = mutableListOf<ActivityReport>()
    var now = from
    for (i in 0..seconds) {
      t.sample(now, pos0 + i * 1000L, dur, counting)?.let(out::add)
      now += 1000
    }
    return out to now
  }

  @Test
  fun reportsEveryTenSecondsWithThePlayedTime() {
    val t = ActivityTracker()
    val (reports, _) = play(t, 0, 25)
    assertEquals(2, reports.size)
    assertEquals(10.0, reports[0].played, 0.01)
    assertEquals(110.0, reports[0].position, 0.01)
    assertEquals(10.0, reports[1].played, 0.01)
    assertFalse(reports[0].final)
    assertEquals(1800.0, reports[0].duration, 0.01)
  }

  @Test
  fun pausingSendsAFinalReportOnce() {
    val t = ActivityTracker()
    play(t, 0, 4)
    val r = t.sample(5000, 105_000, dur, counting = false)
    assertNotNull(r)
    assertTrue(r!!.final)
    assertEquals(5.0, r.played, 0.01)
    // still paused: nothing more
    assertNull(t.sample(6000, 105_000, dur, counting = false))
    assertNull(t.sample(7000, 105_000, dur, counting = false))
  }

  @Test
  fun aScreenThatNeverCountedReportsNothing() {
    val t = ActivityTracker()
    val (reports, now) = play(t, 0, 30, counting = false)
    assertTrue(reports.isEmpty())
    assertNull(t.flush(now, 50_000, dur))
    assertNull(t.closing(now, 50_000, dur))
  }

  @Test
  fun losingFocusIsAFinalAndTheUnfocusedTimeDoesNotCount() {
    val t = ActivityTracker()
    play(t, 0, 3)
    val lost = t.sample(4000, 104_000, dur, counting = false)
    assertTrue(lost!!.final)
    val (reports, now) = play(t, 5000, 30, pos0 = 104_000, counting = false)
    assertTrue(reports.isEmpty())
    val back = t.sample(now, 140_000, dur, counting = true)
    assertNull(back)
    val (next, _) = play(t, now + 1000, 10, pos0 = 141_000)
    assertEquals(10.0, next.first().played, 0.01)
  }

  @Test
  fun closingAfterSeekingWhilePausedStillReportsThePosition() {
    val t = ActivityTracker()
    play(t, 0, 4)
    t.sample(5000, 105_000, dur, counting = false)
    val r = t.closing(9000, 600_000, dur)
    assertTrue(r!!.final)
    assertEquals(600.0, r.position, 0.01)
    assertEquals(0.0, r.played, 0.01)
  }

  @Test
  fun closingWhilePlayingIncludesTheSecondsSinceTheLastSample() {
    val t = ActivityTracker()
    play(t, 0, 3)
    val r = t.closing(4000, 104_000, dur)
    assertEquals(4.0, r!!.played, 0.01)
    assertTrue(r.final)
  }

  @Test
  fun endedIsSentOnceNearTheEndAndRearmedBySeekingAway() {
    val t = ActivityTracker()
    t.sample(0, 100_000, dur, true)
    val end = t.sample(1000, dur - 1000, dur, true)
    assertTrue(end!!.ended)
    assertTrue(end.final)
    assertNull(t.sample(2000, dur, dur, false))
    assertNull(t.sample(3000, dur, dur, false))
    // seek back, play to the end again
    t.sample(4000, 100_000, dur, true)
    assertTrue(t.sample(5000, dur - 500, dur, true)!!.ended)
  }

  @Test
  fun closingAtTheEndMarksEndedUnlessAlreadySent() {
    val t = ActivityTracker()
    t.sample(0, 100_000, dur, true)
    assertTrue(t.closing(1000, dur, dur)!!.ended)
    val t2 = ActivityTracker()
    t2.sample(0, 100_000, dur, true)
    t2.sample(1000, dur - 500, dur, true)
    t2.sample(1500, dur - 500, dur, false)
    // only the half second of tail is left to tell
    assertEquals(0.5, t2.closing(2000, dur, dur)!!.played, 0.01)
    assertNull(t2.closing(3000, dur, dur))
  }

  @Test
  fun aFailedReportsSecondsGoOutWithTheNextOne() {
    val t = ActivityTracker()
    val (first, now) = play(t, 0, 10)
    assertEquals(1, first.size)
    t.restore(first[0].played)
    val (next, _) = play(t, now, 10, pos0 = 111_000)
    assertEquals(1, next.size)
    assertTrue(next[0].played >= 19.0)
  }

  @Test
  fun aLongStallCountsOnlyALittle() {
    val t = ActivityTracker()
    t.sample(0, 100_000, dur, true)
    val r = t.sample(60_000, 105_000, dur, true)
    assertEquals(5.0, r!!.played, 0.01)
  }

  @Test
  fun playedIsCappedPerReportAndKeptForTheNext() {
    val t = ActivityTracker()
    t.sample(0, 100_000, dur, true)
    t.restore(1000.0)
    val r = t.sample(10_000, 110_000, dur, true)
    assertEquals(600.0, r!!.played, 0.01)
    val f = t.closing(11_000, 111_000, dur)
    assertTrue(f!!.played > 400.0)
  }

  @Test
  fun flushIsFinalOnlyWhenSomethingIsUnreported() {
    val t = ActivityTracker()
    play(t, 0, 3)
    val r = t.flush(4000, 104_000, dur)
    assertTrue(r!!.final)
    assertEquals(4.0, r.played, 0.01)
    // the pause that follows (Meta menu) only adds the second it took to land
    val tail = t.sample(5000, 104_000, dur, counting = false)
    assertEquals(1.0, tail!!.played, 0.01)
    assertNull(t.flush(5500, 104_000, dur))
    assertNull(t.sample(6000, 104_000, dur, counting = false))
  }
}
