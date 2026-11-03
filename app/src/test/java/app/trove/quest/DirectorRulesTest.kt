package app.trove.quest

import app.trove.quest.sync.DirectorAction
import app.trove.quest.sync.DirectorContext
import app.trove.quest.sync.DirectorPlan
import app.trove.quest.sync.DirectorRules
import app.trove.quest.sync.EscapeRules
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.StormQuery
import app.trove.quest.sync.SyncProtocol
import app.trove.quest.sync.WearerEscapeState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorRulesTest {
  private val two = DirectorContext(listOf("screen-1", "screen-2"), stopped = false)
  private val none = DirectorContext(emptyList(), stopped = false)
  private val latched = two.copy(stopped = true)

  private fun plan(a: DirectorAction, c: DirectorContext = two) = DirectorRules.plan(a, c)

  // ---- wire -> action ----

  private fun decode(json: String) = SyncProtocol.decode(json)

  @Test
  fun everyDirectorCommandDecodesToItsAction() {
    fun action(json: String) = (decode(json) as ServerMessage.Director).command.action
    assertEquals(DirectorAction.Open("5", true, null, 0), action("""{"type":"command","cmd":"open","seq":1,"sceneId":"5","mode":"new"}"""))
    assertEquals(DirectorAction.Open("5", true, null, 0), action("""{"type":"command","cmd":"open","seq":1,"sceneId":"5"}"""))
    assertEquals(
        DirectorAction.Open("5", false, "screen-2", 61000),
        action("""{"type":"command","cmd":"open","seq":2,"sceneId":"5","mode":"replace","screen":"screen-2","startMs":61000}"""))
    assertEquals(DirectorAction.Swap("screen-1", "8"), action("""{"type":"command","cmd":"swap","seq":3,"sceneId":"8","screen":"screen-1"}"""))
    assertEquals(DirectorAction.Close("screen-1"), action("""{"type":"command","cmd":"close","seq":4,"screen":"screen-1"}"""))
    assertEquals(DirectorAction.Focus("screen-2"), action("""{"type":"command","cmd":"focus","seq":5,"screen":"screen-2"}"""))
    assertEquals(DirectorAction.CloseAll, action("""{"type":"command","cmd":"close-all","seq":6}"""))
    assertEquals(DirectorAction.Random(false, "screen-1"), action("""{"type":"command","cmd":"random","seq":7,"mode":"replace","screen":"screen-1"}"""))
    assertEquals(DirectorAction.Random(true, null), action("""{"type":"command","cmd":"random","seq":8}"""))
    assertEquals(DirectorAction.Storm(false, null), action("""{"type":"command","cmd":"storm","seq":9,"on":false}"""))
    assertEquals(
        DirectorAction.Storm(true, StormQuery("big", listOf("3"), listOf("4", "6"), listOf("5"), "9")),
        action("""{"type":"command","cmd":"storm","seq":10,"on":true,"q":"big","tags":["3"],"performers":["4","6"],"studios":["5"],"filter":"9"}"""))
    assertEquals(DirectorAction.Storm(true, StormQuery()), action("""{"type":"command","cmd":"storm","seq":11,"on":true}"""))
    assertEquals(12L, (decode("""{"type":"command","cmd":"close-all","seq":12}""") as ServerMessage.Director).command.seq)
  }

  @Test
  fun brokenDirectorCommandsAreReportedNotGuessed() {
    assertEquals(ServerMessage.DirectorInvalid(3, "swap"), decode("""{"type":"command","cmd":"swap","seq":3,"sceneId":"5"}"""))
    assertEquals(ServerMessage.DirectorInvalid(4, "open"), decode("""{"type":"command","cmd":"open","seq":4}"""))
    assertEquals(ServerMessage.DirectorInvalid(5, "close"), decode("""{"type":"command","cmd":"close","seq":5}"""))
    assertEquals(ServerMessage.DirectorInvalid(6, "storm"), decode("""{"type":"command","cmd":"storm","seq":6}"""))
    assertEquals("no seq: nothing to answer", ServerMessage.DirectorInvalid(0, "close-all"), decode("""{"type":"command","cmd":"close-all"}"""))
  }

  @Test
  fun oldCommandsAreStillPlainCommands() {
    assertTrue(decode("""{"type":"command","cmd":"pause-all","reason":"STOP ALL"}""") is ServerMessage.Command)
    assertTrue(decode("""{"type":"command","cmd":"blackout","on":true,"seq":5}""") is ServerMessage.Command)
    assertTrue(decode("""{"type":"command","cmd":"seek","deltaMs":10000}""") is ServerMessage.Command)
  }

  @Test
  fun ackAndWearerStopEncode() {
    val ok = Json.parseToJsonElement(SyncProtocol.encodeAck(12, true)).jsonObject
    assertEquals("ack", ok["type"]!!.jsonPrimitive.content)
    assertEquals("12", ok["seq"]!!.jsonPrimitive.content)
    assertEquals("true", ok["ok"]!!.jsonPrimitive.content)
    assertFalse("no error on success", ok.containsKey("error"))
    val bad = Json.parseToJsonElement(SyncProtocol.encodeAck(13, false, "No such screen")).jsonObject
    assertEquals("false", bad["ok"]!!.jsonPrimitive.content)
    assertEquals("No such screen", bad["error"]!!.jsonPrimitive.content)
    assertEquals(200, Json.parseToJsonElement(SyncProtocol.encodeAck(1, false, "x".repeat(500))).jsonObject["error"]!!.jsonPrimitive.content.length)
    assertEquals("wearer-stop", Json.parseToJsonElement(SyncProtocol.encodeWearerStop()).jsonObject["type"]!!.jsonPrimitive.content)
  }

  // ---- action -> plan ----

  @Test
  fun openNewAlwaysAddsAScreen() {
    assertEquals(DirectorPlan.OpenScene("5", true, null, 0, false), plan(DirectorAction.Open("5", true, null, 0)))
    assertEquals(DirectorPlan.OpenScene("5", true, null, 9000, false), plan(DirectorAction.Open("5", true, null, 9000), none))
  }

  @Test
  fun replaceNamesTheScreenOrMeansTheFocusedOne() {
    assertEquals(DirectorPlan.OpenScene("5", false, "screen-2", 0, false), plan(DirectorAction.Open("5", false, "screen-2", 0)))
    assertEquals(DirectorPlan.OpenScene("5", false, null, 0, false), plan(DirectorAction.Open("5", false, null, 0)))
    assertEquals("replacing with nothing open is just opening", DirectorPlan.OpenScene("5", true, null, 0, false), plan(DirectorAction.Open("5", false, null, 0), none))
  }

  @Test
  fun aScreenThatIsNotOpenIsRefused() {
    val no = DirectorPlan.Reject("No such screen")
    assertEquals(no, plan(DirectorAction.Open("5", false, "screen-9", 0)))
    assertEquals(no, plan(DirectorAction.Swap("screen-9", "5")))
    assertEquals(no, plan(DirectorAction.Close("screen-9")))
    assertEquals(no, plan(DirectorAction.Focus("screen-9")))
    assertEquals(no, plan(DirectorAction.Random(false, "screen-9")))
    assertEquals(no, plan(DirectorAction.Close("screen-1"), none))
  }

  @Test
  fun swapCloseFocusAndCloseAll() {
    assertEquals(DirectorPlan.OpenScene("8", false, "screen-1", 0, false), plan(DirectorAction.Swap("screen-1", "8")))
    assertEquals(DirectorPlan.CloseScreen("screen-2"), plan(DirectorAction.Close("screen-2")))
    assertEquals(DirectorPlan.FocusScreen("screen-1"), plan(DirectorAction.Focus("screen-1")))
    assertEquals(DirectorPlan.CloseAll, plan(DirectorAction.CloseAll))
    assertEquals(DirectorPlan.CloseAll, plan(DirectorAction.CloseAll, none))
    assertEquals(DirectorPlan.CloseAll, plan(DirectorAction.CloseAll, latched))
  }

  @Test
  fun randomAndStorm() {
    assertEquals(DirectorPlan.RandomPick(true, null, false), plan(DirectorAction.Random(true, null)))
    assertEquals(DirectorPlan.RandomPick(false, "screen-1", false), plan(DirectorAction.Random(false, "screen-1")))
    assertEquals(DirectorPlan.RandomPick(false, null, false), plan(DirectorAction.Random(false, null)))
    assertEquals(DirectorPlan.RandomPick(true, null, false), plan(DirectorAction.Random(false, null), none))
    val q = StormQuery("x", tags = listOf("1"))
    assertEquals(DirectorPlan.StormStart(q, false), plan(DirectorAction.Storm(true, q)))
    assertEquals(DirectorPlan.StormStart(StormQuery(), false), plan(DirectorAction.Storm(true, null)))
    assertEquals(DirectorPlan.StormStop, plan(DirectorAction.Storm(false, null)))
  }

  @Test
  fun whileStoppedScenesOpenPausedAndNothingElseChanges() {
    assertEquals(DirectorPlan.OpenScene("5", true, null, 0, paused = true), plan(DirectorAction.Open("5", true, null, 0), latched))
    assertEquals(DirectorPlan.OpenScene("8", false, "screen-1", 0, paused = true), plan(DirectorAction.Swap("screen-1", "8"), latched))
    assertEquals(DirectorPlan.RandomPick(true, null, paused = true), plan(DirectorAction.Random(true, null), latched))
    assertEquals(DirectorPlan.StormStart(StormQuery(), paused = true), plan(DirectorAction.Storm(true, null), latched))
    // Closing and focusing are not "starting" anything.
    assertEquals(DirectorPlan.CloseScreen("screen-1"), plan(DirectorAction.Close("screen-1"), latched))
    assertEquals(DirectorPlan.FocusScreen("screen-1"), plan(DirectorAction.Focus("screen-1"), latched))
    assertEquals(DirectorPlan.StormStop, plan(DirectorAction.Storm(false, null), latched))
  }

  // ---- wearer escape rules ----

  @Test
  fun aQueuedStopIsSentOnlyIfTheServerIsNotStoppedAndItIsNotStale() {
    assertTrue(EscapeRules.stillRelevant(1000, 61_000, serverStopped = false))
    assertFalse("server already stopped", EscapeRules.stillRelevant(1000, 61_000, serverStopped = true))
    assertFalse("stale", EscapeRules.stillRelevant(1000, 1000 + EscapeRules.RELEVANT_MS + 1, serverStopped = false))
    assertTrue(EscapeRules.stillRelevant(1000, 1000 + EscapeRules.RELEVANT_MS, serverStopped = false))
    assertFalse("clock went backwards", EscapeRules.stillRelevant(5000, 1000, serverStopped = false))
  }

  @Test
  fun theEscapeStateEndsOnlyAfterTheServerWasStoppedAndThenReleased() {
    val e = WearerEscapeState()
    assertFalse(e.onStatus(stopped = false, connected = true, stopQueued = false))
    e.trigger()
    assertTrue(e.active)
    // A status that was already on its way: not stopped yet, must not end it.
    assertFalse(e.onStatus(false, true, false))
    assertTrue(e.active)
    assertFalse(e.onStatus(true, true, false))
    assertTrue("latched: still active", e.active)
    assertTrue("partner resumed", e.onStatus(false, true, false))
    assertFalse(e.active)
  }

  @Test
  fun theEscapeStateWaitsForTheLinkAndTheQueuedStop() {
    val e = WearerEscapeState()
    e.trigger()
    e.onStatus(true, true, false)
    assertFalse("link down", e.onStatus(false, false, false))
    assertFalse("stop still queued", e.onStatus(false, true, true))
    assertTrue(e.active)
    assertTrue(e.onStatus(false, true, false))
  }

  @Test
  fun aDroppedQueuedStopEndsTheEscapeState() {
    val e = WearerEscapeState()
    e.trigger()
    e.dropped()
    assertFalse(e.active)
  }

  @Test
  fun theWearersTagsShowTheConfirmationFirstThenStoppedByYou() {
    val st = app.trove.quest.sync.SyncState(link = app.trove.quest.sync.SyncLink.CONNECTED, stopped = true, byWearer = true, reason = SyncProtocol.REASON_WEARER)
    assertEquals("Stopped — resume from the console", app.trove.quest.sync.SyncBadge.of(st)?.text)
    assertEquals("Stopped — you're in control", app.trove.quest.sync.SyncBadge.of(st.copy(notice = "Stopped — you're in control"))?.text)
    assertEquals("a partner STOP wins", "Stopped by your partner — wait for them to resume", app.trove.quest.sync.SyncBadge.of(st.copy(byPartner = true))?.text)
    // Still shown with the link down: the wearer must know it worked even then.
    assertEquals("Stopped — you're in control", app.trove.quest.sync.SyncBadge.of(st.copy(link = app.trove.quest.sync.SyncLink.RECONNECTING, notice = "Stopped — you're in control"))?.text)
  }
}
