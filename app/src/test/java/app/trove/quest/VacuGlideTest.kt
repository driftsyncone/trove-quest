package app.trove.quest

import app.trove.quest.input.Action
import app.trove.quest.input.ControlScheme
import app.trove.quest.input.Dir
import app.trove.quest.input.Hand
import app.trove.quest.input.Row
import app.trove.quest.input.Snapshot
import app.trove.quest.input.Target
import app.trove.quest.sync.ResumeMode
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.SyncIndicator
import app.trove.quest.sync.SyncLink
import app.trove.quest.sync.SyncProtocol
import app.trove.quest.sync.SyncState
import app.trove.quest.sync.VacuGlideControl
import app.trove.quest.sync.VacuGlideRules
import app.trove.quest.sync.ValveHold
import app.trove.quest.sync.ValveKey
import app.trove.quest.sync.VgOut
import app.trove.quest.sync.VgPanel
import app.trove.quest.sync.VgState
import app.trove.quest.sync.VgUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VacuGlide sub-panel: wire, when it shows, the hold-to-use safety rules, settings, B / pointer interplay. */
class VacuGlideTest {
  // ---- protocol ----

  @Test
  fun encodesTheThreeMessages() {
    assertEquals("""{"type":"vg","op":"valve","name":"tighter","on":true}""", SyncProtocol.encodeVgValve(ValveKey.TIGHTER, true))
    assertEquals("""{"type":"vg","op":"valve","name":"looser","on":false}""", SyncProtocol.encodeVgValve(ValveKey.LOOSER, false))
    assertEquals("""{"type":"vg","op":"set","offsetMs":-50}""", SyncProtocol.encodeVgSet(offsetMs = -50))
    assertEquals(
        """{"type":"vg","op":"set","offsetMs":0,"intensity":120,"speedLimit":400,"suction":1}""",
        SyncProtocol.encodeVgSet(0, 120, 400, 1))
    assertEquals("""{"type":"vg","op":"reload"}""", SyncProtocol.encodeVgReload())
  }

  @Test
  fun decodesStatusVgAndAcks() {
    val st =
        SyncProtocol.decode(
            """{"type":"status","stopped":false,"reason":null,"device":"playing","armed":true,"resume":"no","vg":{"connected":true,"mode":"ONLINE_CONNECTED","targetSpeed":12,"firmware":"7","battery":null,"latencyMs":90,"valve":"looser","error":"x","offsetMs":-100,"intensity":70,"speedLimit":300,"suction":2}}""")
            as ServerMessage.Status
    assertEquals(VgState(true, "ONLINE_CONNECTED", 12, "7", null, 90, "looser", "x", -100, 70, 300, 2), st.vg)
    assertNull("an older server has no vg", (SyncProtocol.decode("""{"type":"status","stopped":false}""") as ServerMessage.Status).vg)
    // Missing or broken fields fall back to the plugin's defaults, never to something stronger.
    val d = (SyncProtocol.decode("""{"type":"status","stopped":false,"vg":{"connected":"yes","intensity":"high"}}""") as ServerMessage.Status).vg!!
    assertEquals(VgState(), d)
    assertEquals(ServerMessage.VgAck("set", false, "intensity: integer 20 to 200"), SyncProtocol.decode("""{"type":"vg-ack","op":"set","ok":false,"error":"intensity: integer 20 to 200"}"""))
    assertEquals(ServerMessage.VgAck("valve", true, null), SyncProtocol.decode("""{"type":"vg-ack","op":"valve","ok":true}"""))
  }

  // ---- when the panel is there ----

  private fun state(link: SyncLink = SyncLink.CONNECTED, armed: Boolean? = true, connected: Boolean = true, stopped: Boolean = false, byPartner: Boolean = false, byWearer: Boolean = false, vg: VgState = VgState(connected = connected)) =
      SyncState(link = link, stopped = stopped, byPartner = byPartner, byWearer = byWearer, armed = armed, vg = vg)

  @Test
  fun availableOnlyWhenArmedLinkedAndTheToyIsOnline() {
    assertTrue(VacuGlideRules.available(state()))
    assertFalse("unarmed server (fake driver)", VacuGlideRules.available(state(armed = false)))
    assertFalse("older server that does not say", VacuGlideRules.available(state(armed = null)))
    assertFalse("toy offline", VacuGlideRules.available(state(connected = false)))
    assertFalse("reconnecting", VacuGlideRules.available(state(link = SyncLink.RECONNECTING)))
    assertFalse("connecting", VacuGlideRules.available(state(link = SyncLink.CONNECTING)))
    assertFalse("sync off", VacuGlideRules.available(SyncState()))
    // Latched (not started / dead-man) is still there: suction needs no motion.
    assertTrue(VacuGlideRules.available(state(stopped = true)))
  }

  @Test
  fun panelFollowsAvailabilityControlsBlackoutAndTheWearersChoice() {
    fun p(avail: Boolean = true, ui: VgUi = VgUi.CHIP, controls: Boolean = true, black: Boolean = false) = VacuGlideRules.panel(avail, ui, controls, black)
    assertEquals(VgPanel.CHIP, p())
    assertEquals(VgPanel.FULL, p(ui = VgUi.FULL))
    assertEquals(VgPanel.NONE, p(ui = VgUi.HIDDEN))
    assertEquals("toy gone", VgPanel.NONE, p(avail = false, ui = VgUi.FULL))
    assertEquals("another screen's controls / Hide controls", VgPanel.NONE, p(controls = false, ui = VgUi.FULL))
    assertEquals("Black Out hides every panel", VgPanel.NONE, p(black = true, ui = VgUi.FULL))
  }

  @Test
  fun valvesGreyOutDuringAPersonsStopAllAndBlackOut() {
    assertTrue(VacuGlideRules.valvesAllowed(state(), blackout = false))
    assertTrue("not started yet / dead-man latches", VacuGlideRules.valvesAllowed(state(stopped = true), false))
    assertFalse(VacuGlideRules.valvesAllowed(state(stopped = true, byPartner = true), false))
    assertFalse(VacuGlideRules.valvesAllowed(state(stopped = true, byWearer = true), false))
    assertFalse(VacuGlideRules.valvesAllowed(state(), blackout = true))
  }

  @Test
  fun auditAllowsOneVacuGlidePanelOnTheControlsItHangsOn() {
    assertTrue(VacuGlideRules.auditProblems(0, 0, 0).isEmpty())
    assertTrue(VacuGlideRules.auditProblems(1, 0, 1).isEmpty())
    assertTrue(VacuGlideRules.auditProblems(0, 1, 1).isEmpty())
    assertFalse("two panels", VacuGlideRules.auditProblems(1, 1, 2).isEmpty())
    assertEquals("a panel with no controls under it", 1, VacuGlideRules.auditProblems(0, 1, 0).size)
  }

  // ---- hold-to-use: press = start, release = stop, never stuck ----

  private class Wire(var up: Boolean = true) : VgOut {
    val log = mutableListOf<String>()

    override fun valve(key: ValveKey, on: Boolean): Boolean {
      if (!up) return false
      log += "${key.wire}:${if (on) "on" else "off"}"
      return true
    }

    override fun set(offsetMs: Int?, intensity: Int?, speedLimit: Int?, suction: Int?): Boolean {
      if (!up) return false
      log += "set(${listOfNotNull(offsetMs?.let { "o=$it" }, intensity?.let { "i=$it" }, speedLimit?.let { "l=$it" }, suction?.let { "s=$it" }).joinToString(",")})"
      return true
    }

    override fun reload(): Boolean {
      if (!up) return false
      log += "reload"
      return true
    }
  }

  @Test
  fun pressStartsAndReleaseStopsOneValveAtATime() {
    val w = Wire()
    val h = ValveHold(w::valve)
    assertTrue(h.press(ValveKey.TIGHTER, 0))
    assertEquals(ValveKey.TIGHTER, h.held)
    assertTrue("the same button again is the same press", h.press(ValveKey.TIGHTER, 10))
    assertFalse("the other valve while one is held", h.press(ValveKey.LOOSER, 20))
    assertEquals(listOf("tighter:on"), w.log)
    assertTrue(h.release())
    assertNull(h.held)
    assertFalse("a second release is nothing", h.release())
    assertEquals(listOf("tighter:on", "tighter:off"), w.log)
    assertTrue(h.press(ValveKey.LOOSER, 30))
    h.release()
    assertEquals(listOf("tighter:on", "tighter:off", "looser:on", "looser:off"), w.log)
  }

  @Test
  fun aPressThatCouldNotBeSentIsNotHeldAndAReleaseNeverGetsStuck() {
    val w = Wire(up = false)
    val h = ValveHold(w::valve)
    assertFalse(h.press(ValveKey.TIGHTER, 0))
    assertNull("link down: nothing held", h.held)
    w.up = true
    assertTrue(h.press(ValveKey.TIGHTER, 1))
    w.up = false // the link drops while held
    assertTrue(h.release())
    assertNull("the press is forgotten even though the off could not be sent", h.held)
    w.up = true
    assertTrue("a new press works again", h.press(ValveKey.LOOSER, 2))
  }

  @Test
  fun aPressLongerThanFiveSecondsEnds() {
    val w = Wire()
    val h = ValveHold(w::valve)
    h.press(ValveKey.LOOSER, 1000)
    assertFalse(h.tick(5999))
    assertEquals(ValveKey.LOOSER, h.held)
    assertTrue(h.tick(6000))
    assertNull(h.held)
    assertEquals(listOf("looser:on", "looser:off"), w.log)
    assertFalse(h.tick(9000))
  }

  // ---- the control: everything that must let go ----

  private class Rig(armed: Boolean = true, connected: Boolean = true) {
    val wire = Wire()
    val sync = SyncIndicator().also { it.state = SyncState(link = SyncLink.CONNECTED, armed = armed, vg = VgState(connected = connected)) }
    var now = 0L
    var black = false
    var saved: VgUi? = null
    var changes = 0
    val c = VacuGlideControl(sync, wire, { now }, VgUi.FULL, { saved = it }, { black }).also { it.onChange = { changes++ } }
  }

  @Test
  fun pressesOnlyWhileTheToyIsAvailableAndValvesAreAllowed() {
    val off = Rig(connected = false)
    assertFalse(off.c.press(ValveKey.TIGHTER))
    assertTrue(off.wire.log.isEmpty())
    val r = Rig()
    r.sync.state = r.sync.state.copy(stopped = true, byPartner = true)
    assertFalse("during the partner's STOP ALL", r.c.press(ValveKey.TIGHTER))
    assertTrue(r.wire.log.isEmpty())
    r.sync.state = r.sync.state.copy(stopped = false, byPartner = false)
    assertTrue(r.c.press(ValveKey.TIGHTER))
    assertEquals(ValveKey.TIGHTER, r.c.held)
  }

  @Test
  fun everyEndOfAPressReleasesIt() {
    // (label, what happens) -> after it the valve is closed and nothing is held.
    val causes: List<Pair<String, (Rig) -> Unit>> =
        listOf(
            "pointer up / exit / panel gone" to { r -> r.c.release("up") },
            "link lost" to { r -> r.wire.up = false; r.c.release("link lost") },
            "app paused / headset off" to { r -> r.c.release("away") },
            "wearer escape / STOP ALL" to { r -> r.c.release("STOP ALL") },
            "Black Out" to { r -> r.black = true; r.c.onBlackout(true) },
            "partner STOP ALL status" to { r -> r.sync.state = r.sync.state.copy(stopped = true, byPartner = true); r.c.onStatus() },
            "wearer stop status" to { r -> r.sync.state = r.sync.state.copy(stopped = true, byWearer = true); r.c.onStatus() },
            "toy went offline" to { r -> r.sync.state = r.sync.state.copy(vg = VgState(connected = false)); r.c.onStatus() },
            "sync link dropped" to { r -> r.sync.state = r.sync.state.copy(link = SyncLink.RECONNECTING); r.c.onStatus() },
            "panel collapsed (B)" to { r -> r.c.collapse() },
            "panel hidden" to { r -> r.c.hide() },
            "server refused the valve" to { r -> r.c.onAck(ServerMessage.VgAck("valve", false, "STOP ALL is on")) },
            "5 s without a release" to { r -> r.now = VacuGlideRules.MAX_HOLD_MS; r.c.tick() },
        )
    for ((label, cause) in causes) {
      val r = Rig()
      assertTrue(label, r.c.press(ValveKey.LOOSER))
      assertEquals(label, ValveKey.LOOSER, r.c.held)
      cause(r)
      assertNull("$label: nothing held", r.c.held)
      if (r.wire.up) assertEquals("$label: the valve was closed", listOf("looser:on", "looser:off"), r.wire.log)
    }
  }

  @Test
  fun aReleaseWithNothingHeldSendsNothing() {
    val r = Rig()
    r.c.release("up")
    r.c.onStatus()
    r.c.onBlackout(true)
    assertTrue(r.wire.log.isEmpty())
  }

  // ---- settings (plugin steps and ranges) ----

  @Test
  fun settingsStepLikeThePlugin() {
    assertEquals(50, VacuGlideRules.stepOffset(0, 1))
    assertEquals(-50, VacuGlideRules.stepOffset(0, -1))
    assertEquals(5000, VacuGlideRules.stepOffset(5000, 1))
    assertEquals(-5000, VacuGlideRules.stepOffset(-5000, -1))
    assertEquals(110, VacuGlideRules.stepIntensity(100, 1))
    assertEquals(20, VacuGlideRules.stepIntensity(20, -1))
    assertEquals(200, VacuGlideRules.stepIntensity(200, 1))
    assertEquals(listOf(0, 500, 400, 300, 200, 0), generateSequence(0) { VacuGlideRules.nextSpeedLimit(it) }.take(6).toList())
    assertEquals("an unknown value goes to Off", 0, VacuGlideRules.nextSpeedLimit(123))
    assertEquals(listOf(0, 1, 2, 3, 0), generateSequence(0) { VacuGlideRules.nextSuction(it) }.take(5).toList())
    assertEquals("Off", VacuGlideRules.speedLimitLabel(0))
    assertEquals("500 /s", VacuGlideRules.speedLimitLabel(500))
    assertEquals(listOf("Off", "Low", "Medium", "High"), (0..3).map { VacuGlideRules.suctionLabel(it) })
    assertEquals("-50 ms", VacuGlideRules.offsetLabel(-50))
    assertEquals("100%", VacuGlideRules.intensityLabel(100))
  }

  @Test
  fun quickTapsAddUpAndTheServerStatusWins() {
    val r = Rig()
    r.c.stepOffset(1)
    r.c.stepOffset(1)
    assertEquals("two taps before any status", 100, r.c.shown.offsetMs)
    assertEquals(listOf("set(o=50)", "set(o=100)"), r.wire.log)
    // The server's status arrives: its values are the truth again.
    r.sync.state = r.sync.state.copy(vg = VgState(connected = true, offsetMs = 100))
    r.c.onStatus()
    assertEquals(100, r.c.shown.offsetMs)
    r.c.stepIntensity(-1)
    r.c.cycleSpeedLimit()
    r.c.cycleSuction()
    assertEquals(VgState(connected = true, offsetMs = 100, intensity = 90, speedLimit = 500, suction = 1), r.c.shown)
    assertEquals(listOf("set(o=50)", "set(o=100)", "set(i=90)", "set(l=500)", "set(s=1)"), r.wire.log)
    r.c.reload()
    assertEquals("reload", r.wire.log.last())
  }

  @Test
  fun aSettingAtItsLimitSendsNothingAndNoLinkShowsANotice() {
    val r = Rig()
    r.sync.state = r.sync.state.copy(vg = VgState(connected = true, intensity = 200))
    r.c.stepIntensity(1)
    assertTrue(r.wire.log.isEmpty())
    r.wire.up = false
    r.c.stepOffset(1)
    assertEquals("No link to the server", r.c.notice)
    assertEquals("the panel still shows the server's value", 0, r.c.shown.offsetMs)
    r.now = VacuGlideRules.NOTICE_MS
    r.c.tick()
    assertNull(r.c.notice)
  }

  @Test
  fun aRefusalShowsItsReason() {
    val r = Rig()
    r.c.onAck(ServerMessage.VgAck("set", false, "intensity: integer 20 to 200"))
    assertEquals("intensity: integer 20 to 200", r.c.notice)
    r.c.onAck(ServerMessage.VgAck("set", true, null))
    assertEquals("an ok ack leaves it alone", "intensity: integer 20 to 200", r.c.notice)
  }

  // ---- panel position and B ----

  @Test
  fun chipFullAndHiddenAreRememberedAndRestorable() {
    val r = Rig()
    assertEquals(VgPanel.FULL, r.c.panelFor(true))
    r.c.collapse()
    assertEquals(VgUi.CHIP, r.saved)
    assertEquals(VgPanel.CHIP, r.c.panelFor(true))
    r.c.expand()
    assertEquals(VgUi.FULL, r.saved)
    r.c.hide()
    assertEquals(VgPanel.NONE, r.c.panelFor(true))
    assertTrue("the controls offer a way back", r.c.canRestore)
    r.c.restore()
    assertEquals(VgPanel.CHIP, r.c.panelFor(true))
    assertFalse(r.c.canRestore)
    assertTrue("every change tells the screens", r.changes >= 4)
    assertEquals(VgUi.CHIP, VgUi.parse(null))
    assertEquals(VgUi.HIDDEN, VgUi.parse("hidden"))
    assertEquals(VgUi.CHIP, VgUi.parse("nonsense"))
    // Nothing to restore when the toy is not there.
    val gone = Rig(connected = false)
    gone.c.hide()
    assertFalse(gone.c.canRestore)
  }

  @Test
  fun collapseIfOpenOnlyFoldsTheWholePanel() {
    val r = Rig()
    assertTrue(r.c.collapseIfOpen())
    assertFalse("already a chip", r.c.collapseIfOpen())
  }

  private val video = Snapshot(screens = 1, libraryVisible = false)
  private val vgRow = Row(Row.Kind.VACUGLIDE, "screen-1")

  @Test
  fun backFoldsTheOpenPanelFirstThenWalksOnAsBefore() {
    val open = video.copy(rows = listOf(vgRow))
    assertEquals(Action.CLOSE_ROW, ControlScheme.back(open))
    assertEquals(vgRow, ControlScheme.rowToClose(open))
    // With the View row open too, the newest row goes first.
    val both = video.copy(rows = listOf(vgRow, Row(Row.Kind.VIEW, "screen-1")))
    assertEquals(Row(Row.Kind.VIEW, "screen-1"), ControlScheme.rowToClose(both))
    // Folded into the chip: B is the video's again.
    assertEquals(Action.CLOSE_SCREEN, ControlScheme.back(video))
    assertEquals("VacuGlide", Row.Kind.VACUGLIDE.label)
  }

  @Test
  fun theStickDoesNotSeekWhilePointingAtThePanel() {
    val onPanel = video.copy(pointer = mapOf(Hand.RIGHT to Target.PANEL))
    for (d in Dir.entries) assertEquals(Action.NONE, ControlScheme.onStick(onPanel, Hand.RIGHT, d))
    val onVideo = video.copy(pointer = mapOf(Hand.RIGHT to Target.VIDEO))
    assertEquals(Action.SEEK_FORWARD, ControlScheme.onStick(onVideo, Hand.RIGHT, Dir.RIGHT))
  }

  @Test
  fun resumeModeIsUntouched() {
    assertEquals(ResumeMode.NOW, ResumeMode.parse("now"))
  }

  // ---- "Stick: Volume / Suction" ----

  private class StickRig(initial: Boolean = true, connected: Boolean = true) {
    val wire = Wire()
    val sync = SyncIndicator().also { it.state = SyncState(link = SyncLink.CONNECTED, armed = true, vg = VgState(connected = connected)) }
    var now = 0L
    var black = false
    val saved = mutableListOf<Boolean>()
    val c = VacuGlideControl(sync, wire, { now }, VgUi.CHIP, {}, { black }, initialStickSuction = initial, saveStickSuction = { saved += it })
  }

  @Test
  fun stickToggleIsRememberedAndActiveOnlyWhileTheToyIsThere() {
    val r = StickRig(initial = false)
    assertEquals("Volume", VacuGlideRules.stickLabel(r.c.stickSuction))
    assertFalse(r.c.stickActive)
    r.c.toggleStick()
    assertTrue(r.c.stickSuction)
    assertEquals("Suction", VacuGlideRules.stickLabel(r.c.stickSuction))
    assertEquals(listOf(true), r.saved)
    assertTrue(r.c.stickActive)
    r.black = true
    assertFalse("Black Out: the stick is not the valves'", r.c.stickActive)
    r.black = false
    r.sync.state = r.sync.state.copy(vg = VgState(connected = false))
    assertFalse("toy offline", r.c.stickActive)
    r.sync.state = r.sync.state.copy(vg = VgState(connected = true), armed = false)
    assertFalse("not armed", r.c.stickActive)
  }

  @Test
  fun stickHoldsAValveUntilThatStickLetsGo() {
    val r = StickRig()
    assertTrue(r.c.stickPress(ValveKey.TIGHTER, "RIGHT"))
    assertEquals(ValveKey.TIGHTER, r.c.held)
    assertFalse("one valve at a time: the other stick is refused", r.c.stickPress(ValveKey.LOOSER, "LEFT"))
    assertFalse("the other stick pushing the same way is not a second owner", r.c.stickPress(ValveKey.TIGHTER, "LEFT"))
    r.c.stickRelease("LEFT")
    assertEquals("the other stick's release closes nothing", ValveKey.TIGHTER, r.c.held)
    r.c.releaseButton("pointer left the panel")
    assertEquals("a button / pointer release never ends the stick's press", ValveKey.TIGHTER, r.c.held)
    r.c.stickRelease("RIGHT")
    assertNull(r.c.held)
    assertEquals(listOf("tighter:on", "tighter:off"), r.wire.log)
  }

  @Test
  fun aStickReleaseNeverEndsAButtonsPress() {
    val r = StickRig()
    assertTrue(r.c.press(ValveKey.LOOSER))
    assertFalse("the stick waits while a button holds a valve", r.c.stickPress(ValveKey.TIGHTER, "RIGHT"))
    r.c.stickRelease("RIGHT")
    assertEquals(ValveKey.LOOSER, r.c.held)
    r.c.releaseButton("up")
    assertNull(r.c.held)
  }

  @Test
  fun everyEndOfAPressReleasesTheSticksPressToo() {
    val causes: List<Pair<String, (StickRig) -> Unit>> =
        listOf(
            "link lost" to { r -> r.c.release("link lost") },
            "app paused" to { r -> r.c.release("away") },
            "STOP ALL" to { r -> r.sync.state = r.sync.state.copy(stopped = true, byPartner = true); r.c.onStatus() },
            "Black Out" to { r -> r.black = true; r.c.onBlackout(true) },
            "toy offline" to { r -> r.sync.state = r.sync.state.copy(vg = VgState(connected = false)); r.c.onStatus() },
            "switched back to Volume" to { r -> r.c.toggleStick() },
            "5 s cap" to { r -> r.now = VacuGlideRules.MAX_HOLD_MS; r.c.tick() },
        )
    for ((label, cause) in causes) {
      val r = StickRig()
      assertTrue(label, r.c.stickPress(ValveKey.LOOSER, "LEFT"))
      cause(r)
      assertNull("$label: nothing held", r.c.held)
      assertEquals("$label: closed", listOf("looser:on", "looser:off"), r.wire.log)
      // Still pushed after the cap: the stick's own release later sends nothing more.
      r.c.stickRelease("LEFT")
      assertEquals(label, 2, r.wire.log.size)
    }
  }

  @Test
  fun stickPressIsRefusedWhenTheValvesAreNot() {
    val r = StickRig()
    r.sync.state = r.sync.state.copy(stopped = true, byWearer = true)
    assertFalse(r.c.stickPress(ValveKey.TIGHTER, "RIGHT"))
    assertTrue(r.wire.log.isEmpty())
    val off = StickRig(connected = false)
    assertFalse(off.c.stickPress(ValveKey.TIGHTER, "RIGHT"))
  }
}
