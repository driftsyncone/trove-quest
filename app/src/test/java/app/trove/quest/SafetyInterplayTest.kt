package app.trove.quest

import app.trove.quest.sync.ToyResume
import app.trove.quest.sync.ToyResumeOffer
import app.trove.quest.sync.SyncBadge
import org.junit.Assert.assertNull
import app.trove.quest.player.RoomView
import app.trove.quest.sync.BlackoutState
import app.trove.quest.sync.DirectorContext
import app.trove.quest.sync.DirectorPlan
import app.trove.quest.sync.DirectorRules
import app.trove.quest.sync.EscapeRules
import app.trove.quest.sync.PersonStopEdge
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.SyncLink
import app.trove.quest.sync.SyncProtocol
import app.trove.quest.sync.SyncState
import app.trove.quest.sync.WearerEscapeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pure-state simulation of the headset's safety reactions: STOP ALL, wearer escape, Black Out,
 * link loss, reconnect while latched, Director while stopped. Server messages enter as wire text
 * (so they go through [SyncProtocol.decode]); the decisions come from the real pure rules
 * ([BlackoutState], [WearerEscapeState], [EscapeRules], [DirectorRules], [RoomView.passthroughOn]).
 *
 * The glue is MIRRORED from TroveActivity (obey / applyBlackout / wearerEscape / setStopPassthrough /
 * the sync.state collector) and SyncClient (message / statusArrived / lost / sendWearerStop), which
 * cannot be built on the JVM (Spatial SDK, OkHttp socket). If that glue changes, change [Headset] too;
 * the mirrored spots are marked "mirror of".
 */
private class Headset(var roomView: RoomView = RoomView.OFF) {
  var now = 0L
  val blackout = BlackoutState()
  val escape = WearerEscapeState()
  val personStop = PersonStopEdge()
  var sync = SyncState(link = SyncLink.CONNECTED)
  var connected = true

  /** Screens: sync id -> playing. */
  val screens = linkedMapOf("screen-1" to true, "screen-2" to true)
  var focused = "screen-1"

  /** Scene hidden (wall + screens) by Black Out. */
  var sceneHidden = false
  var stopOverride = false
  private var passthroughForStop = false
  var notice: String? = null
  var escapeQueuedAt: Long? = null
  val sent = mutableListOf<String>()
  val plans = mutableListOf<DirectorPlan>()

  val stopped get() = sync.byPartner || sync.byWearer || escape.active

  /** Mirror of Room.push: Black Out beats everything, then STOP ALL's room, then the wearer's choice. */
  val passthrough get() = !blackout.on && (stopOverride || RoomView.passthroughOn(roomView, screens.isNotEmpty(), sphereActive = false))
  fun volume(id: String) = blackout.volume(audible = id == focused)
  val anyPlaying get() = screens.values.any { it }

  // ---- server -> headset ----

  fun receive(wire: String) {
    val before = sync
    when (val m = SyncProtocol.decode(wire)) {
      is ServerMessage.Status -> {
        // mirror of SyncClient.message
        sync = sync.copy(
            stopped = m.stopped,
            byPartner = m.stopped && m.reason?.startsWith(SyncProtocol.REASON_STOP_ALL) == true,
            byWearer = m.stopped && m.reason == SyncProtocol.REASON_WEARER,
            reason = m.reason, device = m.device, armed = m.armed, resume = m.resume)
        statusArrived(m.stopped)
        collect(before)
      }
      is ServerMessage.Command -> obey(m)
      is ServerMessage.Director -> {
        val ctx = DirectorContext(screens.keys.toList(), stopped)
        val plan = DirectorRules.plan(m.command.action, ctx)
        plans += plan
        runPlan(plan)
        sent += SyncProtocol.encodeAck(m.command.seq, plan !is DirectorPlan.Reject, (plan as? DirectorPlan.Reject)?.error)
      }
      is ServerMessage.DirectorInvalid -> if (m.seq > 0) sent += SyncProtocol.encodeAck(m.seq, false, "Bad ${m.cmd} command")
      else -> Unit
    }
  }

  /** Mirror of the StateFlow collector in TroveActivity: only runs when the state value changed. */
  private fun collect(before: SyncState) {
    if (sync == before) return
    escape.onStatus(sync.stopped, sync.link == SyncLink.CONNECTED, escapeQueuedAt != null)
    if (personStop.rose(sync)) stopForPerson()
    releaseStopPassthrough()
  }

  /** Mirror of TroveActivity.releaseStopPassthrough (collector tail, and onEscapeDropped). */
  private fun releaseStopPassthrough() {
    if (passthroughForStop && !sync.stopped && !escape.active) setStopPassthrough(false)
  }

  /** Mirror of TroveActivity.stopForPerson (the pause-all(STOP ALL) command and the status rising edge). */
  private fun stopForPerson() {
    applyBlackout(false)
    for (k in screens.keys) screens[k] = false
    setStopPassthrough(true)
  }

  /** Mirror of TroveActivity.obey. */
  private fun obey(c: ServerMessage.Command) {
    when (c.cmd) {
      "blackout" -> {
        val was = blackout.on
        blackout.set(c.on, c.seq)
        applyBlackout(c.on)
        sent += SyncProtocol.encodeBlackout(c.on, c.seq)
        if (was && !c.on) notice = "Black Out lifted"
      }
      "pause-all" -> if (c.reason == SyncProtocol.REASON_STOP_ALL) stopForPerson() else for (k in screens.keys) screens[k] = false
      "play", "pause" -> {
        val id = screens.keys.firstOrNull { it == c.screen } ?: focused
        if (id in screens) screens[id] = c.cmd == "play"
      }
    }
  }

  /** Mirror of TroveActivity.applyBlackout. */
  private fun applyBlackout(on: Boolean) {
    blackout.set(on)
    sceneHidden = on
  }

  private fun setStopPassthrough(on: Boolean) {
    if (passthroughForStop == on) return
    passthroughForStop = on
    stopOverride = on
  }

  private fun runPlan(p: DirectorPlan) {
    when (p) {
      is DirectorPlan.OpenScene -> {
        val id = p.replaceScreen ?: generateSequence(1) { it + 1 }.map { "screen-$it" }.first { it !in screens }
        screens[id] = !p.paused
        if (focused !in screens || p.newScreen) focused = id
      }
      is DirectorPlan.CloseScreen -> screens.remove(p.screen)
      DirectorPlan.CloseAll -> screens.clear()
      is DirectorPlan.FocusScreen -> focused = p.screen
      else -> Unit
    }
  }

  // ---- wearer ----

  /** Both grips for [ms] in 50 ms frames; true if the escape fired. */
  fun squeeze(ms: Long, both: Boolean = true): Boolean {
    var fired = false
    var t = 0L
    while (t <= ms) {
      if (blackout.gripTick(now + t, both)) {
        wearerEscape()
        fired = true
      }
      t += 50
    }
    now += ms
    return fired
  }

  /** Mirror of TroveActivity.wearerEscape. */
  private fun wearerEscape() {
    escape.trigger()
    applyBlackout(false)
    for (k in screens.keys) screens[k] = false
    setStopPassthrough(true)
    sendWearerStop()
    notice = "escape"
  }

  /** Mirror of SyncClient.sendWearerStop. */
  private fun sendWearerStop() {
    if (connected) {
      sent += SyncProtocol.encodeWearerStop()
      escapeQueuedAt = null
    } else {
      escapeQueuedAt = now
    }
  }

  // ---- link ----

  /** Mirror of SyncClient.lost + the onLinkLost hook (applyBlackout(false)). */
  fun linkLost() {
    connected = false
    applyBlackout(false)
    val before = sync
    sync = sync.copy(link = SyncLink.RECONNECTING)
    collect(before)
  }

  /** Mirror of SyncClient.opened. */
  fun linkUp() {
    connected = true
    val before = sync
    sync = sync.copy(link = SyncLink.CONNECTED)
    collect(before)
  }

  /** Mirror of SyncClient.statusArrived. */
  private fun statusArrived(serverStopped: Boolean) {
    val at = escapeQueuedAt ?: return
    if (EscapeRules.stillRelevant(at, now, serverStopped)) sendWearerStop()
    else {
      escapeQueuedAt = null
      if (!serverStopped) {
        escape.dropped()
        releaseStopPassthrough() // mirror of onEscapeDropped
      }
    }
  }

  fun types() = sent.map { Regex("\"type\":\"([\\w-]+)\"").find(it)!!.groupValues[1] }
}

private const val STATUS_NOT_STARTED = """{"type":"status","stopped":true,"reason":"Not started yet: press Resume to allow the device","device":"stopped","armed":false}"""
private const val STATUS_RESUMED = """{"type":"status","stopped":false,"reason":null,"device":"idle","armed":false,"resume":"no"}"""
private const val STATUS_STOP_ALL = """{"type":"status","stopped":true,"reason":"STOP ALL by synctest","device":"stopped","armed":false,"resume":"no"}"""
private const val STATUS_STOP_ALL_SOLO = """{"type":"status","stopped":true,"reason":"STOP ALL by synctest","device":"stopped","armed":false,"resume":"confirm"}"""
private const val STATUS_NOT_STARTED_SOLO = """{"type":"status","stopped":true,"reason":"Not started yet: press Resume to allow the device","device":"stopped","armed":false,"resume":"now"}"""
private const val STATUS_WEARER = """{"type":"status","stopped":true,"reason":"Stopped by the wearer","device":"stopped","armed":false,"resume":"now"}"""
private const val STATUS_DEADMAN = """{"type":"status","stopped":true,"reason":"Partner console lost (dead-man)","device":"lost","armed":false}"""
private const val PAUSE_ALL = """{"type":"command","cmd":"pause-all","reason":"STOP ALL"}"""
private const val PAUSE_SHUTDOWN = """{"type":"command","cmd":"pause-all","reason":"server shutting down"}"""
private fun blackoutCmd(on: Boolean, seq: Int, reason: String? = null) =
    """{"type":"command","cmd":"blackout","on":$on,"seq":$seq${if (reason != null) ""","reason":"$reason"""" else ""}}"""
private fun open(seq: Int, scene: String = "9") = """{"type":"command","cmd":"open","seq":$seq,"sceneId":"$scene","mode":"new"}"""

class SafetyInterplayTest {
  // ---- STOP ALL ----

  @Test
  fun partnerStopAllPausesEverythingShowsTheRoomAndIsShownAsByPartner() {
    val h = Headset(RoomView.OFF)
    h.receive(STATUS_RESUMED)
    assertFalse(h.passthrough)
    // Server order today: status first, then pause-all.
    h.receive(STATUS_STOP_ALL)
    h.receive(PAUSE_ALL)
    assertFalse(h.anyPlaying)
    assertTrue(h.sync.byPartner)
    assertFalse(h.sync.byWearer)
    assertTrue("room shown whatever View -> Behind says", h.passthrough)
    // Partner Resume: wearer's own choice back.
    h.receive(STATUS_RESUMED)
    assertFalse(h.passthrough)
    assertFalse(h.stopped)
  }

  @Test
  fun aStopAllStatusAloneStopsEverythingAndShowsTheRoom() {
    // Reconnect after a STOP ALL that came while the link was down: only the status arrives.
    val h = Headset(RoomView.OFF)
    h.receive(STATUS_RESUMED)
    h.receive(blackoutCmd(true, 1))
    h.receive(STATUS_STOP_ALL)
    assertFalse(h.anyPlaying)
    assertTrue(h.passthrough)
    assertFalse("Black Out lifted by the edge", h.blackout.on)
    h.receive(STATUS_RESUMED)
    assertFalse(h.passthrough)
  }

  @Test
  fun reconnectWhilePersonLatchedHelloStatusPauseAllIsIdempotentForWearerToo() {
    val h = Headset()
    h.receive(STATUS_WEARER)
    assertFalse(h.anyPlaying)
    assertTrue(h.sync.byWearer)
    assertTrue(h.passthrough)
    h.receive(PAUSE_ALL)
    assertFalse(h.anyPlaying)
    assertTrue(h.passthrough)
    assertTrue(h.sent.isEmpty())
  }

  @Test
  fun notStartedAndDeadManStatusesAreNotAPersonsStopAll() {
    for (st in listOf(STATUS_NOT_STARTED, STATUS_DEADMAN)) {
      val h = Headset()
      h.receive(st)
      assertTrue(h.screens.values.all { it })
      assertFalse(h.passthrough)
      assertFalse(h.sync.byPartner || h.sync.byWearer)
    }
  }

  @Test
  fun soloNotStartedOffersStartAndTheStatusAfterItClearsTheOffer() {
    val h = Headset()
    h.receive(STATUS_NOT_STARTED_SOLO)
    assertEquals(ToyResumeOffer("Start toy", false), ToyResume.of(h.sync))
    assertFalse("not a person's stop: nothing paused", h.stopped)
    assertTrue(h.screens.values.all { it })
    h.receive(STATUS_RESUMED)
    assertNull(ToyResume.of(h.sync))
  }

  @Test
  fun wearerEscapeCanBeResumedFromTheHeadsetAndTheHeadsetReleasesTheRoom() {
    val h = Headset(RoomView.OFF)
    h.receive(STATUS_RESUMED)
    h.receive(STATUS_WEARER)
    h.receive(PAUSE_ALL)
    assertEquals(ToyResumeOffer("Resume toy", false), ToyResume.of(h.sync))
    assertTrue(h.passthrough)
    h.receive(STATUS_RESUMED) // the server's answer to {"type":"resume"}
    assertFalse(h.stopped)
    assertFalse("room back to the wearer's choice", h.passthrough)
    assertFalse("resuming does not restart the video", h.anyPlaying)
  }

  @Test
  fun partnerStopAllIsAHoldOnlyWhenTheServerSaysConfirmAndNeverOtherwise() {
    val h = Headset()
    h.receive(STATUS_RESUMED)
    h.receive(STATUS_STOP_ALL) // partner required on: resume "no"
    h.receive(PAUSE_ALL)
    assertNull("no control while only the console can resume", ToyResume.of(h.sync))
    assertTrue(h.sync.byPartner)
    h.receive(STATUS_STOP_ALL_SOLO)
    assertEquals(ToyResumeOffer("Hold to resume toy", true), ToyResume.of(h.sync))
    assertEquals(SyncBadge.PARTNER_STOP_HOLD, SyncBadge.of(h.sync)?.text)
  }

  @Test
  fun armedIsKeptFromTheStatus() {
    val h = Headset()
    h.receive(STATUS_RESUMED.replace("\"armed\":false", "\"armed\":true"))
    assertEquals(true, h.sync.armed)
    h.receive(STATUS_RESUMED)
    assertEquals(false, h.sync.armed)
  }

  @Test
  fun stopAllDuringBlackOutLiftsItAndRestoresSoundAndRoom() {
    val h = Headset()
    h.receive(blackoutCmd(true, 1))
    assertTrue(h.sceneHidden)
    assertEquals(0f, h.volume("screen-1"))
    assertFalse(h.passthrough)
    h.receive(STATUS_STOP_ALL)
    h.receive(PAUSE_ALL)
    h.receive(blackoutCmd(false, 2, "stop-all"))
    assertFalse(h.blackout.on)
    assertFalse(h.sceneHidden)
    assertEquals("audible screen back to full", 1f, h.volume("screen-1"))
    assertEquals(0f, h.volume("screen-2"))
    assertTrue(h.passthrough)
    assertFalse(h.anyPlaying)
  }

  @Test
  fun pauseAllWithoutTheStopAllReasonIsAServerShutdownNotAnEscape() {
    val h = Headset()
    h.receive(blackoutCmd(true, 1))
    h.receive(PAUSE_SHUTDOWN)
    assertFalse(h.anyPlaying)
    assertTrue("a restart does not lift Black Out", h.blackout.on)
    assertFalse("and does not force the room on", h.passthrough)
    h.receive(STATUS_RESUMED)
    assertFalse(h.stopped)
  }

  @Test
  fun partnerCanBlackOutWhileStoppedAndBlackWinsOverTheRoom() {
    val h = Headset()
    h.receive(STATUS_STOP_ALL)
    h.receive(PAUSE_ALL)
    assertTrue(h.passthrough)
    h.receive(blackoutCmd(true, 3))
    assertTrue(h.blackout.on)
    assertFalse("pure black, STOP ALL's room included", h.passthrough)
    assertTrue(h.stopped)
    h.receive(blackoutCmd(false, 4, "console"))
    assertTrue("the room is back, still stopped", h.passthrough)
    assertEquals("Black Out lifted", h.notice)
  }

  @Test
  fun blackOutCommandsAreAnsweredWithTheirSeqAndRepeatsChangeNothing() {
    val h = Headset()
    h.receive(blackoutCmd(true, 5))
    h.receive(blackoutCmd(true, 5))
    assertEquals(listOf(SyncProtocol.encodeBlackout(true, 5), SyncProtocol.encodeBlackout(true, 5)), h.sent)
    assertEquals(5L, h.blackout.seq)
    assertTrue(h.blackout.on)
    h.receive(blackoutCmd(false, 6, "console"))
    assertFalse(h.blackout.on)
    assertEquals(3, h.sent.size)
  }

  // ---- wearer escape ----

  @Test
  fun lessThanTwoSecondsOfBothGripsDoesNothing() {
    val h = Headset()
    h.receive(blackoutCmd(true, 1))
    h.sent.clear()
    assertFalse("1.9 s is not enough", h.squeeze(1900))
    assertTrue(h.blackout.on)
    assertTrue("a release before 2 s starts over", !h.squeeze(100, both = false))
    assertTrue(h.blackout.on)
  }

  @Test
  fun wearerEscapeFiresOnceAtTwoSecondsAndDoesEverythingLocalInBlackOut() {
    val g = Headset()
    g.receive(blackoutCmd(true, 1))
    g.sent.clear()
    assertTrue(g.squeeze(2100))
    assertFalse(g.blackout.on)
    assertFalse(g.sceneHidden)
    assertFalse(g.anyPlaying)
    assertTrue(g.passthrough)
    assertTrue(g.escape.active)
    assertEquals(listOf("wearer-stop"), g.types())
    // One long squeeze is one escape.
    g.squeeze(5000)
    assertEquals(1, g.types().size)
  }

  @Test
  fun escapeWithTheLinkDownIsQueuedThenSentOnceIfTheServerIsNotAlreadyStopped() {
    val h = Headset()
    h.linkLost()
    assertTrue(h.squeeze(2100))
    assertTrue(h.sent.isEmpty())
    assertTrue(h.escape.active)
    assertTrue(h.passthrough)
    h.now += 30_000
    h.linkUp()
    h.receive(STATUS_RESUMED) // the server had not latched (headset reports had stopped, not yet dead-man)
    assertEquals(listOf("wearer-stop"), h.types())
    assertTrue("still treated as stopped until the partner resumed", h.escape.active)
    h.receive(STATUS_WEARER)
    h.receive(PAUSE_ALL)
    h.receive(STATUS_RESUMED) // partner Resume
    assertFalse(h.escape.active)
    assertFalse(h.passthrough)
  }

  @Test
  fun queuedEscapeIsDroppedWhenTheServerIsAlreadyLatched() {
    val h = Headset()
    h.linkLost()
    h.squeeze(2100)
    h.linkUp()
    h.receive(STATUS_DEADMAN)
    assertTrue("no second latch rewriting the reason", h.sent.isEmpty())
    assertTrue(h.escape.active)
    assertTrue(h.sync.stopped)
    h.receive(STATUS_RESUMED)
    assertFalse("the partner's Resume ends it", h.escape.active)
  }

  @Test
  fun aStaleQueuedEscapeIsDroppedAndTheHeadsetGoesBackToNormal() {
    val h = Headset()
    h.linkLost()
    h.squeeze(2100)
    h.now += EscapeRules.RELEVANT_MS + 1
    h.linkUp()
    h.receive(STATUS_RESUMED)
    assertTrue(h.sent.isEmpty())
    assertFalse(h.escape.active)
    assertFalse("new scenes open playing again", h.stopped)
    // The status above equals the previous state, so the StateFlow collector does not run; the
    // onEscapeDropped hook releases the room itself (was a quirk: the room stayed on).
    assertFalse("room released with the dropped escape", h.stopOverride)
    assertFalse(h.passthrough)
  }

  @Test
  fun escapeEndsOnlyAfterTheServerWasSeenLatchedThenReleased() {
    val h = Headset()
    h.receive(STATUS_RESUMED)
    h.squeeze(2100)
    assertEquals(listOf("wearer-stop"), h.types())
    // A status that was already on its way (not stopped) must not end the escape.
    h.receive(STATUS_RESUMED.replace("idle", "playing"))
    assertTrue(h.escape.active)
    h.receive(STATUS_WEARER)
    assertTrue(h.escape.active)
    h.receive(STATUS_RESUMED)
    assertFalse(h.escape.active)
    assertFalse(h.passthrough)
  }

  // ---- link loss / reconnect ----

  @Test
  fun linkLossLiftsBlackOutButKeepsTheLatchAndTheRoom() {
    val h = Headset()
    h.receive(STATUS_STOP_ALL)
    h.receive(PAUSE_ALL)
    h.receive(blackoutCmd(true, 2))
    h.linkLost()
    assertFalse(h.blackout.on)
    assertFalse(h.sceneHidden)
    assertTrue("still shown as stopped by the partner", h.sync.byPartner)
    assertTrue(h.passthrough)
    assertEquals(SyncLink.RECONNECTING, h.sync.link)
  }

  @Test
  fun reconnectWhileLatchedShowsTheLatchAgainAndKeepsNewScenesPaused() {
    val h = Headset()
    h.receive(STATUS_STOP_ALL)
    h.receive(PAUSE_ALL)
    h.linkLost()
    h.linkUp()
    h.receive(STATUS_STOP_ALL) // server sends the status right after hello (today no pause-all re-send)
    assertTrue(h.sync.byPartner)
    h.receive(open(1))
    assertFalse("a scene sent while STOPPED opens paused", h.screens["screen-3"]!!)
    assertTrue(h.passthrough)
  }

  @Test
  fun reconnectDuringBlackOutIsBlackedOutAgainByTheServersCommand() {
    val h = Headset()
    h.receive(blackoutCmd(true, 7))
    h.linkLost()
    assertFalse(h.blackout.on)
    h.linkUp()
    h.receive(STATUS_RESUMED)
    h.receive(blackoutCmd(true, 7))
    assertTrue(h.blackout.on)
    assertTrue(h.sceneHidden)
    assertEquals(7L, h.blackout.seq)
  }

  // ---- Director while stopped ----

  @Test
  fun directorOpenIsPausedOnlyForARealStopNotForTheNotStartedLatch() {
    val h = Headset()
    h.receive(STATUS_NOT_STARTED)
    h.receive(open(1))
    assertTrue("not-started latch: Send to headset works alone", h.screens["screen-3"]!!)
    val p = Headset()
    p.receive(STATUS_STOP_ALL)
    p.receive(open(1))
    assertFalse(p.screens["screen-3"]!!)
    val w = Headset()
    w.receive(STATUS_RESUMED)
    w.squeeze(2100)
    w.receive(open(1))
    assertFalse("wearer escape counts, even before the server says so", w.screens["screen-3"]!!)
    w.receive(STATUS_WEARER)
    w.receive(STATUS_RESUMED)
    w.receive(open(2))
    assertTrue("after the partner's Resume it plays", w.screens["screen-4"]!!)
  }

  @Test
  fun directorNeverLiftsTheLatchOrBlackOutAndAlwaysAcks() {
    val h = Headset()
    h.receive(STATUS_STOP_ALL)
    h.receive(PAUSE_ALL)
    h.receive(blackoutCmd(true, 2))
    h.sent.clear()
    h.receive(open(5))
    h.receive("""{"type":"command","cmd":"close","seq":6,"screen":"nope"}""")
    h.receive("""{"type":"command","cmd":"swap","seq":7,"screen":"screen-1"}""") // missing sceneId
    assertTrue(h.sync.byPartner)
    assertTrue(h.blackout.on)
    assertEquals(listOf(SyncProtocol.encodeAck(5, true), SyncProtocol.encodeAck(6, false, "No such screen"), SyncProtocol.encodeAck(7, false, "Bad swap command")), h.sent.filter { it.contains("\"ack\"") })
    assertTrue("opened screens are not playing", h.screens["screen-3"] == false)
    assertFalse(h.passthrough)
  }

  @Test
  fun closeAllWhileStoppedLeavesTheRoomOnAndStopped() {
    val h = Headset()
    h.receive(STATUS_STOP_ALL)
    h.receive(PAUSE_ALL)
    h.receive("""{"type":"command","cmd":"close-all","seq":1}""")
    assertTrue(h.screens.isEmpty())
    assertTrue(h.sync.byPartner)
    assertTrue(h.passthrough)
  }
}
