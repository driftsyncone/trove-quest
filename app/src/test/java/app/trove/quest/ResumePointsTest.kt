package app.trove.quest

import app.trove.quest.player.ResumeBook
import app.trove.quest.player.markerFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePointsTest {
  private val min = 60_000L
  private val hour = 60 * min

  @Test
  fun aPositionPartWayIsKept() {
    val b = ResumeBook()
    assertTrue(b.record("5", 12 * min, hour, now = 1))
    assertEquals(12 * min, b.get("5"))
  }

  @Test
  fun theFirstSecondsNeverCountSoOpeningAndClosingKeepsTheOldPoint() {
    val b = ResumeBook()
    b.record("5", 20 * min, hour, now = 1)
    assertFalse(b.record("5", 10_000, hour, now = 2))
    assertFalse(b.record("5", 0, hour, now = 3))
    assertEquals(20 * min, b.get("5"))
  }

  @Test
  fun nearTheEndCountsAsFinishedAndForgetsThePoint() {
    val b = ResumeBook()
    b.record("5", 20 * min, hour, now = 1)
    assertTrue(b.record("5", hour - 20_000, hour, now = 2))
    assertNull(b.get("5"))
    // 95 % of a long scene is the end too, even with more than 30 s left.
    b.record("6", 10 * min, 3 * hour, now = 3)
    b.record("6", (3 * hour * 0.96).toLong(), 3 * hour, now = 4)
    assertNull(b.get("6"))
  }

  @Test
  fun unknownDurationStillKeepsThePoint() {
    val b = ResumeBook()
    assertTrue(b.record("5", 5 * min, 0, now = 1))
    assertEquals(5 * min, b.get("5"))
  }

  @Test
  fun theSameSecondIsNotAChange() {
    val b = ResumeBook()
    assertTrue(b.record("5", 100_100, hour, now = 1))
    assertFalse(b.record("5", 100_900, hour, now = 2))
    assertTrue(b.record("5", 101_000, hour, now = 3))
  }

  @Test
  fun theOldestGoFirstPastTheLimit() {
    val b = ResumeBook()
    for (i in 0 until ResumeBook.MAX_ENTRIES + 5) b.record("s$i", min, hour, now = i.toLong())
    assertEquals(ResumeBook.MAX_ENTRIES, b.entries.size)
    assertNull(b.get("s0"))
    assertNull(b.get("s4"))
    assertEquals(min, b.get("s5"))
  }

  @Test
  fun encodeDecodeRoundTripsAndJunkIsAnEmptyBook() {
    val b = ResumeBook()
    b.record("5", 12 * min, hour, now = 42)
    val back = ResumeBook.decode(ResumeBook.encode(b))
    assertEquals(b.entries, back.entries)
    assertTrue(ResumeBook.decode("not json").entries.isEmpty())
    assertTrue(ResumeBook.decode(null).entries.isEmpty())
  }

  @Test
  fun markersArePlacedAlongTheBar() {
    assertEquals(0.25f, markerFraction(900.0, hour)!!, 1e-6f)
    assertEquals(0f, markerFraction(0.0, hour)!!, 0f)
    assertNull(markerFraction(10.0, 0)) // duration not known yet
    assertNull(markerFraction(3601.0, hour)) // past the end (bad marker or short transcode)
  }
}
