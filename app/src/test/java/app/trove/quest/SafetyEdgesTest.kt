package app.trove.quest

import app.trove.quest.sync.AwayGate
import app.trove.quest.sync.PersonStopEdge
import app.trove.quest.sync.SyncLink
import app.trove.quest.sync.SyncProtocol
import app.trove.quest.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AwayGate] (headset off / app paused / Meta menu) and [PersonStopEdge] (STOP ALL seen in the status). */
class SafetyEdgesTest {
  @Test
  fun startupResumeIsNoEdge() {
    val g = AwayGate()
    assertNull(g.update(activityResumed = true, menuOpen = false))
    assertFalse(g.away)
  }

  @Test
  fun headsetOffPausesOnceAndBackResumes() {
    val g = AwayGate()
    g.update(true, false)
    assertEquals(true, g.update(activityResumed = false, menuOpen = false)) // onPause: pause everything
    assertNull("a second report while away changes nothing", g.update(false, false))
    assertEquals(false, g.update(activityResumed = true, menuOpen = false)) // onResume: resume what we paused
  }

  @Test
  fun menuAndPauseTogetherAreOneAwayPeriod() {
    val g = AwayGate()
    g.update(true, false)
    assertEquals(true, g.update(activityResumed = true, menuOpen = true)) // Meta menu
    assertNull("headset taken off with the menu open: already paused", g.update(activityResumed = false, menuOpen = true))
    assertNull("menu closes while the headset is still off: nothing resumes", g.update(activityResumed = false, menuOpen = false))
    assertTrue(g.away)
    assertEquals(false, g.update(activityResumed = true, menuOpen = false))
  }

  @Test
  fun resumedWithTheMenuStillOpenWaitsForTheMenu() {
    val g = AwayGate()
    g.update(true, false)
    assertEquals(true, g.update(false, false))
    assertNull(g.update(activityResumed = true, menuOpen = true))
    assertEquals(false, g.update(activityResumed = true, menuOpen = false))
  }

  @Test
  fun sessionStateBeforeTheFirstResumeIsHarmless() {
    // The XR session may report before onResume: away first (nothing open to pause), then back.
    val g = AwayGate()
    assertEquals(true, g.update(activityResumed = false, menuOpen = false))
    assertEquals(false, g.update(activityResumed = true, menuOpen = false))
  }

  private val up = SyncState(link = SyncLink.CONNECTED)

  @Test
  fun stopAllEdgeFiresOncePerLatch() {
    val e = PersonStopEdge()
    assertFalse(e.rose(up))
    assertFalse("the server's own latches are not a person's STOP", e.rose(up.copy(stopped = true, reason = "Not started yet")))
    val partner = up.copy(stopped = true, byPartner = true, reason = "STOP ALL by alice")
    assertTrue(e.rose(partner))
    assertFalse("the same latch again (device state changes)", e.rose(partner.copy(device = "stopped")))
    assertFalse("link drops: the latch stays shown, no new edge", e.rose(partner.copy(link = SyncLink.RECONNECTING)))
    assertFalse(e.rose(up)) // Resume
    assertTrue("the wearer's escape counts too", e.rose(up.copy(stopped = true, byWearer = true, reason = SyncProtocol.REASON_WEARER)))
    assertFalse("partner STOP over the wearer's: still one latch", e.rose(partner))
  }

  @Test
  fun aStopAllMissedDuringTheGapFiresWhenTheStatusArrivesAfterTheReconnect() {
    val e = PersonStopEdge()
    assertFalse(e.rose(up))
    // The socket was switched off (onStop): state reset, then the reconnect's first status says STOP ALL.
    assertFalse(e.rose(SyncState()))
    assertFalse(e.rose(SyncState(link = SyncLink.CONNECTING)))
    assertTrue(e.rose(up.copy(stopped = true, byPartner = true, reason = "STOP ALL by alice")))
  }
}
