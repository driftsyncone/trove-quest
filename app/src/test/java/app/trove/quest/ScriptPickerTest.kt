package app.trove.quest

import app.trove.quest.sync.ScriptOption
import app.trove.quest.sync.ScriptPicker
import app.trove.quest.sync.ScriptRules
import app.trove.quest.sync.ScriptState
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.SyncIndicator
import app.trove.quest.sync.SyncLink
import app.trove.quest.sync.SyncProtocol
import app.trove.quest.sync.SyncState
import app.trove.quest.sync.VacuGlideControl
import app.trove.quest.sync.VgOut
import app.trove.quest.sync.ValveKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Script variants: the wire, and the picker shared by the VacuGlide panel and the scene details (server = source of truth). */
class ScriptPickerTest {
  private val opts = listOf(ScriptOption("Default", "Default", true), ScriptOption("FULL", "FULL"), ScriptOption("HALF", "HALF"))

  private class Rig {
    val sync = SyncIndicator().apply { state = SyncState(link = SyncLink.CONNECTED) }
    val sent = mutableListOf<Pair<String, String>>()
    var linkUp = true
    var now = 1000L
    val picker = ScriptPicker(sync, { s, id -> linkUp.also { if (it) sent += s to id } }, { now })

    fun status(s: ScriptState?) {
      sync.state = sync.state.copy(script = s)
      picker.onStatus()
    }
  }

  // ---- wire ----

  @Test
  fun encodesTheChoice() {
    assertEquals("""{"type":"vg","op":"script","sceneId":"12","id":"FULL 500ms"}""", SyncProtocol.encodeVgScript("12", "FULL 500ms"))
  }

  @Test
  fun decodesStatusScriptAndDropsBrokenParts() {
    val st =
        SyncProtocol.decode(
            """{"type":"status","stopped":false,"script":{"sceneId":"7","options":[{"id":"Default","label":"Default","default":true},{"id":"FULL","label":"FULL","default":false},{"label":"no id"},5],"chosen":"FULL","loading":true,"error":null}}""")
            as ServerMessage.Status
    assertEquals(ScriptState("7", listOf(ScriptOption("Default", "Default", true), ScriptOption("FULL", "FULL")), "FULL", true, null), st.script)
    assertNull("null script", (SyncProtocol.decode("""{"type":"status","stopped":false,"script":null}""") as ServerMessage.Status).script)
    assertNull("an older server has none", (SyncProtocol.decode("""{"type":"status","stopped":false}""") as ServerMessage.Status).script)
    assertNull("no scene id", (SyncProtocol.decode("""{"type":"status","stopped":false,"script":{"options":[{"id":"a"}]}}""") as ServerMessage.Status).script)
    assertNull("no options", (SyncProtocol.decode("""{"type":"status","stopped":false,"script":{"sceneId":"1","options":[]}}""") as ServerMessage.Status).script)
  }

  @Test
  fun theRowIsThereOnlyWithAChoice() {
    assertFalse(ScriptRules.hasChoice(null))
    assertFalse(ScriptRules.hasChoice(opts.take(1)))
    assertTrue(ScriptRules.hasChoice(opts.take(2)))
  }

  // ---- picker ----

  @Test
  fun aPickShowsAtOnceAndLoadingUntilTheServerHasItOnTheToy() {
    val r = Rig()
    r.status(ScriptState("7", opts, "Default"))
    assertEquals("Default", r.picker.chosen("7"))
    assertNull(r.picker.line("7"))

    assertTrue(r.picker.pick("7", "HALF"))
    assertEquals(listOf("7" to "HALF"), r.sent)
    assertEquals("the tapped chip is selected", "HALF", r.picker.chosen("7"))
    assertEquals(ScriptRules.LOADING, r.picker.line("7"))

    // The server's order: status loading, then the ack, then the status when the toy has it.
    r.status(ScriptState("7", opts, "HALF", loading = true))
    r.picker.onAck(ServerMessage.VgAck("script", true, null))
    assertTrue(r.picker.loading("7"))
    assertEquals(ScriptRules.LOADING, r.picker.line("7"))
    r.status(ScriptState("7", opts, "HALF"))
    assertNull(r.picker.pending)
    assertFalse(r.picker.loading("7"))
    assertEquals("HALF", r.picker.chosen("7"))
    assertNull(r.picker.line("7"))
  }

  @Test
  fun aRefusalShowsTheServersWordsAndTheServersChoiceAgain() {
    val r = Rig()
    r.status(ScriptState("7", opts, "FULL"))
    r.picker.pick("7", "HALF")
    r.picker.onAck(ServerMessage.VgAck("script", false, "This scene has no such script"))
    assertNull(r.picker.pending)
    assertEquals("FULL", r.picker.chosen("7"))
    assertEquals("This scene has no such script", r.picker.line("7"))
    // The next pick clears it.
    r.picker.pick("7", "Default")
    assertEquals(ScriptRules.LOADING, r.picker.line("7"))
  }

  @Test
  fun anUnreadableScriptIsTheServersErrorInTheStatus() {
    val r = Rig()
    r.status(ScriptState("7", opts, "FULL"))
    r.picker.pick("7", "HALF")
    r.status(ScriptState("7", opts, "HALF", loading = true))
    r.picker.onAck(ServerMessage.VgAck("script", true, null))
    r.status(ScriptState("7", opts, "FULL", loading = false, error = "Couldn't load the script \"HALF\""))
    assertNull(r.picker.pending)
    assertEquals("FULL", r.picker.chosen("7"))
    assertEquals("Couldn't load the script \"HALF\"", r.picker.line("7"))
  }

  @Test
  fun noLinkSaysSoAndChangesNothing() {
    val r = Rig()
    r.linkUp = false
    r.status(ScriptState("7", opts, "FULL"))
    assertFalse(r.picker.pick("7", "HALF"))
    assertNull(r.picker.pending)
    assertEquals("FULL", r.picker.chosen("7"))
    assertEquals(ScriptRules.NO_LINK, r.picker.line("7"))
  }

  @Test
  fun anUnansweredTapIsForgottenAndTheServersChoiceShowsAgain() {
    val r = Rig()
    r.status(ScriptState("7", opts, "FULL"))
    r.picker.pick("7", "HALF")
    r.now += ScriptRules.PENDING_MS + 1
    assertEquals("FULL", r.picker.chosen("7"))
    assertFalse(r.picker.loading("7"))
  }

  @Test
  fun sceneDetailsOfAnotherSceneUseTheirListUntilItShowsThePick() {
    val r = Rig()
    r.status(ScriptState("7", opts, "FULL")) // the toy follows scene 7
    assertEquals("the list's choice for scene 9", "Default", r.picker.chosen("9", serverChosen = "Default"))
    r.picker.pick("9", "HALF")
    r.picker.onAck(ServerMessage.VgAck("script", true, null))
    assertEquals(1, r.picker.answers)
    assertEquals("still the tap until the list is read again", "HALF", r.picker.chosen("9", serverChosen = "Default"))
    r.picker.confirmed("9", loading = false)
    assertNull(r.picker.pending)
    assertEquals("HALF", r.picker.chosen("9", serverChosen = "HALF"))
    assertEquals("scene 7 untouched", "FULL", r.picker.chosen("7"))
  }

  @Test
  fun vacuGlideControlRoutesScriptAcksToThePickerNotToItsOwnNotice() {
    val sync = SyncIndicator().apply { state = SyncState(link = SyncLink.CONNECTED, armed = true, script = ScriptState("7", opts, "FULL")) }
    val sent = mutableListOf<String>()
    val out =
        object : VgOut {
          override fun valve(key: ValveKey, on: Boolean) = true

          override fun set(offsetMs: Int?, intensity: Int?, speedLimit: Int?, suction: Int?) = true

          override fun reload() = true

          override fun script(sceneId: String, id: String) = sent.add("$sceneId:$id")
        }
    val c = VacuGlideControl(sync, out, clock = { 0L })
    assertTrue(c.scripts.pick("7", "HALF"))
    assertEquals(listOf("7:HALF"), sent)
    c.onAck(ServerMessage.VgAck("script", false, "This scene has no such script"))
    assertNull("not the panel's notice", c.notice)
    assertEquals("This scene has no such script", c.scripts.line("7"))
  }
}
