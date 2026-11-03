package app.trove.quest

import app.trove.quest.input.Action
import app.trove.quest.input.Button
import app.trove.quest.input.ControlScheme
import app.trove.quest.input.Press
import app.trove.quest.input.Row
import app.trove.quest.input.RowStack
import app.trove.quest.input.Snapshot
import app.trove.quest.sync.ResumeMode
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.SessionTimer
import app.trove.quest.sync.SyncProtocol
import app.trove.quest.sync.SyncState
import app.trove.quest.sync.TimerOp
import app.trove.quest.sync.TimerState
import app.trove.quest.sync.ToyResume
import app.trove.quest.ui.StatusTags
import app.trove.quest.ui.TagLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session timer's rules (sync/SessionTimer.kt), its tag, its rows and B. */
class SessionTimerTest {
  private val t0 = 1_790_000_000_000L
  private val timer = TimerState(t0, "wearer")

  @Test
  fun presetsAndTheRowsChips() {
    assertEquals(listOf(15, 30, 45, 60, 90), SessionTimer.PRESETS)
    assertEquals(listOf("15 min", "30 min", "45 min", "60 min", "90 min"), SessionTimer.choices(running = false).map { it.label })
    assertEquals(
        listOf("15 min", "30 min", "45 min", "60 min", "90 min", "+10 min", "Cancel"),
        SessionTimer.choices(running = true).map { it.label })
    assertEquals(TimerOp.Set(45), SessionTimer.choices(false)[2])
    assertEquals(listOf(TimerOp.Extend, TimerOp.Cancel), SessionTimer.choices(true).takeLast(2))
  }

  @Test
  fun formatRoundsSecondsUp() {
    assertEquals("30:00", SessionTimer.format(30 * 60_000L))
    assertEquals("30:00", SessionTimer.format(30 * 60_000L - 400))
    assertEquals("28:41", SessionTimer.format(28 * 60_000L + 41_000))
    assertEquals("1:02:00", SessionTimer.format(62 * 60_000L))
    assertEquals("1:30:00", SessionTimer.format(90 * 60_000L))
    assertEquals("0:42", SessionTimer.format(42_000))
    assertEquals("0:01", SessionTimer.format(1))
    assertEquals("0:00", SessionTimer.format(0))
    assertEquals("0:00", SessionTimer.format(-5000))
  }

  @Test
  fun leftTagAndWarning() {
    assertNull(SessionTimer.leftMs(null, t0))
    assertNull(SessionTimer.tagText(null, t0))
    assertEquals(600_000L, SessionTimer.leftMs(timer, t0 - 600_000))
    assertEquals(0L, SessionTimer.leftMs(timer, t0 + 5000))
    assertEquals("Timer 10:00", SessionTimer.tagText(timer, t0 - 600_000))
    assertFalse(SessionTimer.warning(timer, t0 - 300_001))
    assertTrue(SessionTimer.warning(timer, t0 - 300_000))
    assertFalse(SessionTimer.warning(null, t0))
  }

  @Test
  fun noticesAreNeverShownDuringBlackOut() {
    assertTrue(SessionTimer.showNotice(blackout = false))
    assertFalse(SessionTimer.showNotice(blackout = true))
    assertEquals("5 min left", SessionTimer.warnText(5))
    assertEquals("1 min left", SessionTimer.warnText(1))
    assertEquals("Timer ended", SessionTimer.ENDED_NOTICE)
  }

  @Test
  fun theTagSitsNextToTheSoundTagAndTurnsAmberAtTheEnd() {
    val tags = StatusTags.controls(SyncState(), null, null, true, true, false, "720p", timer = "Timer 4:59", timerWarn = true)
    assertEquals(listOf("Sound", "Timer 4:59", "720p"), tags.map { it.text })
    assertEquals(TagLevel.WARN, tags[1].level)
    val calm = StatusTags.controls(SyncState(), null, null, false, false, false, "", timer = "Timer 20:00")
    assertEquals(listOf("Muted", "Timer 20:00"), calm.map { it.text })
    assertEquals(TagLevel.NEUTRAL, calm[1].level)
    // A held panel's stick hint still replaces it.
    assertEquals(listOf("Stick: size"), StatusTags.controls(SyncState(), null, "Stick: size", true, true, false, "", timer = "Timer 1:00").map { it.text })
  }

  @Test
  fun decodingIsTolerant() {
    // An older server's status (no timer) and a timer without endsAt: no timer.
    val old = """{"type":"status","stopped":false,"reason":null,"device":"idle","armed":false,"resume":"no"}"""
    assertNull((SyncProtocol.decode(old) as ServerMessage.Status).timer)
    val bad = """{"type":"status","stopped":false,"timer":{"setBy":"wearer"}}"""
    assertNull((SyncProtocol.decode(bad) as ServerMessage.Status).timer)
    // Extra fields are fine; setBy defaults to the partner.
    val extra = """{"type":"status","stopped":false,"timer":{"endsAt":5,"minutes":30,"x":1}}"""
    assertEquals(TimerState(5, "partner"), (SyncProtocol.decode(extra) as ServerMessage.Status).timer)
    // Unknown timer events are ignored, not misread as a warning.
    assertNull(SyncProtocol.decode("""{"type":"timer","event":"end"}"""))
    assertNull(SyncProtocol.decode("""{"type":"timer","event":"warn"}"""))
    assertEquals(ServerMessage.TimerWarn(5), SyncProtocol.decode("""{"type":"timer","event":"warn","minutesLeft":5,"new":true}"""))
  }

  @Test
  fun theTimersLatchIsNotAPartnerStopAndOffersResumeWhenTheServerSaysSo() {
    val ended = SyncState(link = app.trove.quest.sync.SyncLink.CONNECTED, stopped = true, reason = SessionTimer.ENDED_REASON, resume = ResumeMode.NOW)
    assertFalse(ended.reason!!.startsWith(SyncProtocol.REASON_STOP_ALL))
    assertEquals("Resume toy", ToyResume.of(ended)?.label)
    assertNull(ToyResume.of(ended.copy(resume = ResumeMode.NO)))
  }

  @Test
  fun bClosesTheTimerRowOnTheControlsAndOnStormBeforeExitingStorm() {
    val video = Snapshot(screens = 1, libraryVisible = false, rows = listOf(Row(Row.Kind.TIMER, "screen-1")))
    assertEquals(Action.CLOSE_ROW, ControlScheme.onButton(video, Button.B, Press.SHORT))
    assertEquals(Row(Row.Kind.TIMER, "screen-1"), ControlScheme.rowToClose(video))
    val storm = Snapshot(storm = true, libraryVisible = false, rows = listOf(Row(Row.Kind.TIMER, Row.STORM)))
    assertEquals(Action.CLOSE_ROW, ControlScheme.onButton(storm, Button.B, Press.SHORT))
    assertFalse(Row(Row.Kind.TIMER, Row.STORM).onWall)
    // Closed: B exits Storm as before.
    assertEquals(Action.EXIT_STORM, ControlScheme.onButton(storm.copy(rows = emptyList()), Button.B, Press.SHORT))
    // Newest first with another row open.
    val stack = RowStack()
    stack.update(listOf(Row(Row.Kind.SORT, Row.WALL)))
    val both = stack.update(listOf(Row(Row.Kind.SORT, Row.WALL), Row(Row.Kind.TIMER, Row.STORM)))
    assertEquals(Row(Row.Kind.TIMER, Row.STORM), ControlScheme.rowToClose(storm.copy(libraryVisible = true, rows = both)))
  }

  @Test
  fun theTagCountsOnTheServersClockWhateverTheHeadsetsClockSays() {
    // The server says 1_790_000_000_000 when this Quest's clock reads 10 min earlier.
    val local = t0 - 600_000
    val offset = SessionTimer.clockOffsetMs(t0, local)
    assertEquals(600_000L, offset)
    val running = TimerState(t0 + 15 * 60_000L, "wearer")
    assertEquals("Timer 15:00", SessionTimer.tagText(running, SessionTimer.serverNow(local, offset)))
    // Without the offset the skewed clock would read 25:00.
    assertEquals("Timer 25:00", SessionTimer.tagText(running, local))
    assertFalse(SessionTimer.warning(running, SessionTimer.serverNow(local + 9 * 60_000L, offset)))
    assertTrue(SessionTimer.warning(running, SessionTimer.serverNow(local + 10 * 60_000L, offset)))
    // Clock ahead, and an older server without serverNow (no offset).
    assertEquals(-30_000L, SessionTimer.clockOffsetMs(t0, t0 + 30_000))
    assertEquals(0L, SessionTimer.clockOffsetMs(null, t0))
    assertEquals(0L, SyncState().clockOffsetMs)
  }

  @Test
  fun warningsGoToTheNoticePanelOnlyWhenNothingElseShowsThemAndNeverInBlackOut() {
    // Everything hidden: the panel.
    assertTrue(SessionTimer.noticePanel(blackout = false, libraryVisible = false, controlsShown = false, stormPanelShown = false))
    // Black Out: nothing.
    assertFalse(SessionTimer.noticePanel(blackout = true, libraryVisible = false, controlsShown = false, stormPanelShown = false))
    // The tag is in view somewhere: the sync tag carries it, no panel.
    assertFalse(SessionTimer.noticePanel(blackout = false, libraryVisible = true, controlsShown = false, stormPanelShown = false))
    assertFalse(SessionTimer.noticePanel(blackout = false, libraryVisible = false, controlsShown = true, stormPanelShown = false))
    assertFalse(SessionTimer.noticePanel(blackout = false, libraryVisible = false, controlsShown = false, stormPanelShown = true))
  }
}
