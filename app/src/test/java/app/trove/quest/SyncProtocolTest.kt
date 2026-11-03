package app.trove.quest

import app.trove.quest.sync.HeadView
import app.trove.quest.sync.HeadsetStatus
import app.trove.quest.sync.ResumeMode
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.ToyResume
import app.trove.quest.sync.ToyResumeOffer
import app.trove.quest.sync.SyncBadge
import app.trove.quest.sync.SyncLink
import app.trove.quest.sync.SyncProtocol
import app.trove.quest.sync.SyncReporter
import app.trove.quest.sync.SyncScreen
import app.trove.quest.sync.SyncState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolTest {
  private fun screen(
      id: String = "screen-1",
      pos: Long = 61_234,
      playing: Boolean = true,
      focused: Boolean = true,
      rate: Float = 1f,
      sceneId: String = "123",
  ) = SyncScreen(id, sceneId, "Title", pos, 1_800_000, playing, rate, focused, "sbs180")

  private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun stateCarriesTheFieldsTheServerReads() {
    val o =
        parse(
            SyncProtocol.encodeState(
                listOf(screen(), screen(id = "screen-2", focused = false, playing = false).copy(durationMs = null, title = null)),
                HeadsetStatus(inOurApp = true, battery = 81, charging = false, tempC = 31.26f, app = "0.2.3").toMap(),
            ))
    assertEquals("state", o["type"]!!.jsonPrimitive.content)
    val screens = o["screens"]!!.jsonArray
    assertEquals(2, screens.size)
    val s = screens[0].jsonObject
    assertEquals("screen-1", s["id"]!!.jsonPrimitive.content)
    // sceneId as a string: the server checks /^\d{1,12}$/ on String(sceneId).
    assertTrue(s["sceneId"]!!.jsonPrimitive.isString)
    assertEquals("123", s["sceneId"]!!.jsonPrimitive.content)
    assertEquals("Title", s["title"]!!.jsonPrimitive.content)
    assertEquals(61_234L, s["positionMs"]!!.jsonPrimitive.long)
    assertEquals(1_800_000L, s["durationMs"]!!.jsonPrimitive.long)
    assertTrue(s["playing"]!!.jsonPrimitive.boolean)
    assertEquals(1.0, s["rate"]!!.jsonPrimitive.double, 0.0)
    assertTrue(s["focused"]!!.jsonPrimitive.boolean)
    assertEquals("sbs180", s["projection"]!!.jsonPrimitive.content)
    val s2 = screens[1].jsonObject
    assertEquals(JsonNull, s2["durationMs"])
    assertFalse("title" in s2)
    assertFalse(s2["focused"]!!.jsonPrimitive.boolean)

    val h = o["headset"]!!.jsonObject
    assertTrue(h["inOurApp"]!!.jsonPrimitive.boolean)
    assertEquals(81, h["battery"]!!.jsonPrimitive.int)
    assertFalse(h["charging"]!!.jsonPrimitive.boolean)
    assertEquals(31.3, h["tempC"]!!.jsonPrimitive.double, 0.0)
    assertEquals("0.2.3", h["app"]!!.jsonPrimitive.content)
  }

  @Test
  fun headsetLeavesOutWhatIsUnknownAndLimitsSizes() {
    assertEquals(mapOf<String, Any>("inOurApp" to false), HeadsetStatus(inOurApp = false).toMap())
    val many = (1..12).map { screen(id = "s$it", focused = it == 1) }
    val big = (1..30).associate { "k$it" to it }
    val o = parse(SyncProtocol.encodeState(many, big))
    assertEquals(SyncProtocol.MAX_SCREENS, (o["screens"] as JsonArray).size)
    assertEquals(SyncProtocol.MAX_HEADSET_KEYS, (o["headset"] as JsonObject).size)
  }

  @Test
  fun negativePositionIsSentAsZero() {
    val o = parse(SyncProtocol.encodeState(listOf(screen(pos = -50)), emptyMap()))
    assertEquals(0L, o["screens"]!!.jsonArray[0].jsonObject["positionMs"]!!.jsonPrimitive.long)
  }

  @Test
  fun decodesEveryServerMessage() {
    assertEquals(
        ServerMessage.Hello("abc", 3000, 1000),
        SyncProtocol.decode("""{"type":"hello","id":"abc","deadmanMs":3000,"heartbeatMs":1000}"""))
    assertEquals(
        ServerMessage.Command("pause-all", null, 0, "STOP ALL"),
        SyncProtocol.decode("""{"type":"command","cmd":"pause-all","reason":"STOP ALL"}"""))
    assertEquals(
        ServerMessage.Command("seek", "screen-7", -10_000, null),
        SyncProtocol.decode("""{"type":"command","cmd":"seek","deltaMs":-10000,"screen":"screen-7"}"""))
    assertEquals(ServerMessage.Command("play", null, 0, null), SyncProtocol.decode("""{"type":"command","cmd":"play"}"""))
    assertEquals(
        ServerMessage.Status(true, "STOP ALL by alice", "stopped"),
        SyncProtocol.decode("""{"type":"status","stopped":true,"reason":"STOP ALL by alice","device":"stopped"}"""))
    assertEquals(
        ServerMessage.Status(false, null, "idle"),
        SyncProtocol.decode("""{"type":"status","stopped":false,"reason":null,"device":"idle"}"""))
    // `armed` (0.4.x servers): whether a real toy is driven; absent or broken = unknown, never "armed".
    assertEquals(
        ServerMessage.Status(false, null, "playing", armed = true),
        SyncProtocol.decode("""{"type":"status","stopped":false,"reason":null,"device":"playing","armed":true}"""))
    assertEquals(false, (SyncProtocol.decode("""{"type":"status","stopped":false,"armed":false}""") as ServerMessage.Status).armed)
    assertNull((SyncProtocol.decode("""{"type":"status","stopped":false,"armed":"yes"}""") as ServerMessage.Status).armed)
  }

  @Test
  fun resumeMessageAndStatusField() {
    assertEquals("""{"type":"resume"}""", SyncProtocol.encodeResume())
    assertEquals("""{"type":"resume","confirm":true}""", SyncProtocol.encodeResume(true))
    fun mode(f: String) = (SyncProtocol.decode("""{"type":"status","stopped":true$f}""") as ServerMessage.Status).resume
    assertEquals(ResumeMode.NOW, mode(""","resume":"now""""))
    assertEquals(ResumeMode.CONFIRM, mode(""","resume":"confirm""""))
    assertEquals(ResumeMode.NO, mode(""","resume":"no""""))
    assertEquals("older server: only a console resumes", ResumeMode.NO, mode(""))
    assertEquals(ResumeMode.NO, mode(""","resume":"banana""""))
  }

  @Test
  fun resumeControlShowsOnlyWhenTheServerOffersIt() {
    val up = SyncState(link = SyncLink.CONNECTED, stopped = true, reason = "Not started yet: press Resume to allow the device", device = "stopped")
    assertNull("partner required / older server", ToyResume.of(up))
    assertEquals(ToyResumeOffer("Start toy", false), ToyResume.of(up.copy(resume = ResumeMode.NOW)))
    assertEquals(ToyResumeOffer("Resume toy", false), ToyResume.of(up.copy(resume = ResumeMode.NOW, reason = "Stopped by the wearer", byWearer = true)))
    assertEquals(ToyResumeOffer("Resume toy", false), ToyResume.of(up.copy(resume = ResumeMode.NOW, reason = "Headset disconnected (dead-man)")))
    assertEquals(ToyResumeOffer("Hold to resume toy", true), ToyResume.of(up.copy(resume = ResumeMode.CONFIRM, reason = "STOP ALL by x", byPartner = true)))
    assertNull("not latched", ToyResume.of(up.copy(stopped = false, resume = ResumeMode.NOW)))
    assertNull("no link", ToyResume.of(up.copy(link = SyncLink.RECONNECTING, resume = ResumeMode.NOW)))
    assertNull("link off", ToyResume.of(up.copy(link = SyncLink.OFF, resume = ResumeMode.NOW)))
    assertEquals(2000L, ToyResume.HOLD_MS)
  }

  @Test
  fun badgeTellsTheWearerWhatToPress() {
    val up = SyncState(link = SyncLink.CONNECTED, stopped = true, device = "stopped", armed = false)
    assertEquals(SyncBadge(SyncBadge.TOY_NOT_STARTED, SyncBadge.Level.WARN), SyncBadge.of(up.copy(resume = ResumeMode.NOW, reason = "Not started yet: press Resume"), quiet = true))
    assertEquals("Toy stopped · press Resume toy", SyncBadge.TOY_STOPPED)
    assertEquals(SyncBadge(SyncBadge.TOY_STOPPED, SyncBadge.Level.ALERT), SyncBadge.of(up.copy(resume = ResumeMode.NOW, byWearer = true, reason = "Stopped by the wearer"), quiet = true))
    assertEquals(SyncBadge(SyncBadge.PARTNER_STOP_HOLD, SyncBadge.Level.ALERT), SyncBadge.of(up.copy(resume = ResumeMode.CONFIRM, byPartner = true, reason = "STOP ALL by x"), quiet = true))
    assertEquals("partner required: unchanged", SyncBadge.PARTNER_STOP, SyncBadge.of(up.copy(byPartner = true, reason = "STOP ALL by x"), quiet = true)!!.text)
    assertEquals("partner required: unchanged", SyncBadge.WEARER_STOP, SyncBadge.of(up.copy(byWearer = true, reason = "Stopped by the wearer"), quiet = true)!!.text)
  }

  @Test
  fun badgeSaysWhatIsTrueAboutTheToy() {
    val up = SyncState(link = SyncLink.CONNECTED)
    assertEquals(SyncBadge("Link ok · toy unarmed", SyncBadge.Level.NEUTRAL), SyncBadge.of(up.copy(armed = false, device = "playing")))
    assertEquals(SyncBadge("Link ok · toy armed", SyncBadge.Level.ACCENT), SyncBadge.of(up.copy(armed = true, device = "idle")))
    assertEquals(SyncBadge("Link ok · toy on", SyncBadge.Level.ACCENT), SyncBadge.of(up.copy(armed = true, device = "playing")))
    assertEquals(
        SyncBadge("Link ok · toy stopped", SyncBadge.Level.NEUTRAL),
        SyncBadge.of(up.copy(armed = true, stopped = true, reason = "Not started yet", device = "stopped")))
    assertEquals("older server: no claim about a toy", "Link ok", SyncBadge.of(up)!!.text)
    assertNull("all fine stays quiet on the controls", SyncBadge.of(up.copy(armed = true, device = "playing"), quiet = true))
    // What to do after a STOP ALL, on the wall and on every screen's controls.
    assertEquals(
        SyncBadge(SyncBadge.PARTNER_STOP, SyncBadge.Level.ALERT),
        SyncBadge.of(up.copy(armed = true, stopped = true, byPartner = true), quiet = true))
    assertEquals("Stopped by your partner — wait for them to resume", SyncBadge.PARTNER_STOP)
    assertEquals(
        SyncBadge("Stopped — resume from the console", SyncBadge.Level.ALERT),
        SyncBadge.of(up.copy(armed = false, stopped = true, byWearer = true), quiet = true))
  }

  @Test
  fun ignoresWhatItDoesNotKnow() {
    assertNull(SyncProtocol.decode("not json"))
    assertNull(SyncProtocol.decode("[1,2]"))
    assertNull(SyncProtocol.decode("""{"cmd":"play"}"""))
    assertNull(SyncProtocol.decode("""{"type":"state","screens":[]}"""))
    assertNull(SyncProtocol.decode("""{"type":"command"}"""))
    // Wrong types fall back rather than throw.
    assertEquals(ServerMessage.Command("seek", null, 0, null), SyncProtocol.decode("""{"type":"command","cmd":"seek","deltaMs":"x","screen":5}"""))
  }

  // ---- reporter ----

  @Test
  fun reporterSendsOnChangeAndAsHeartbeat() {
    val r = SyncReporter()
    assertTrue("first report", r.shouldSend(listOf(screen(pos = 0)), now = 0))
    // Playing on schedule: nothing new until the heartbeat is due.
    assertFalse(r.shouldSend(listOf(screen(pos = 250)), now = 250))
    assertFalse(r.shouldSend(listOf(screen(pos = 500)), now = 500))
    assertTrue("heartbeat", r.shouldSend(listOf(screen(pos = 750)), now = 750))
    // Pause: at once.
    assertTrue("pause", r.shouldSend(listOf(screen(pos = 900, playing = false)), now = 900))
    assertFalse(r.shouldSend(listOf(screen(pos = 900, playing = false)), now = 1150))
    // Seek while paused.
    assertTrue("seek", r.shouldSend(listOf(screen(pos = 20_000, playing = false)), now = 1400))
    // Focus moves, a screen opens.
    assertTrue("focus", r.shouldSend(listOf(screen(pos = 20_000, playing = false, focused = false)), now = 1500))
    assertTrue(
        "opened",
        r.shouldSend(listOf(screen(pos = 20_000, playing = false, focused = false), screen(id = "b")), now = 1600))
    // Forced (a command was obeyed) and after reset (reconnect).
    assertTrue(r.shouldSend(listOf(screen(pos = 20_000, playing = false, focused = false), screen(id = "b")), now = 1700, force = true))
    r.reset()
    assertTrue(r.shouldSend(listOf(screen(pos = 20_000, playing = false, focused = false), screen(id = "b")), now = 1800))
  }

  @Test
  fun reporterSeesAStallCatchingUpAsAJump() {
    val r = SyncReporter()
    r.shouldSend(listOf(screen(pos = 10_000)), now = 0)
    // Reported playing, but the picture stood still for 500 ms.
    assertTrue(r.shouldSend(listOf(screen(pos = 10_000)), now = 500))
    // At 2x the expected position runs twice as fast: no jump.
    val fast = SyncReporter()
    fast.shouldSend(listOf(screen(pos = 0, rate = 2f)), now = 0)
    assertFalse(fast.shouldSend(listOf(screen(pos = 500, rate = 2f)), now = 250))
  }

  // ---- badge ----

  @Test
  fun badgeShowsPartnerStopFirst() {
    assertNull(SyncBadge.of(SyncState()))
    assertEquals("Link ok", SyncBadge.of(SyncState(link = SyncLink.CONNECTED))!!.text)
    assertNull("all fine is quiet on the controls", SyncBadge.of(SyncState(link = SyncLink.CONNECTED), quiet = true))
    // The server's "not started yet" latch is not the partner's STOP.
    assertEquals("Link ok", SyncBadge.of(SyncState(link = SyncLink.CONNECTED, stopped = true, reason = "Not started yet"))!!.text)
    val stop = SyncState(link = SyncLink.RECONNECTING, stopped = true, byPartner = true)
    assertEquals(SyncBadge("Stopped by your partner — wait for them to resume", SyncBadge.Level.ALERT), SyncBadge.of(stop, quiet = true))
    assertEquals(SyncBadge.Level.WARN, SyncBadge.of(SyncState(link = SyncLink.RECONNECTING), quiet = true)!!.level)
    assertNull(SyncBadge.of(SyncState(link = SyncLink.CONNECTING), quiet = true))
  }

  @Test
  fun decodesTheBlackoutCommand() {
    assertEquals(
        ServerMessage.Command("blackout", null, 0, null, on = true, seq = 7),
        SyncProtocol.decode("""{"type":"command","cmd":"blackout","on":true,"seq":7}"""),
    )
    assertEquals(
        ServerMessage.Command("blackout", null, 0, "stop-all", on = false, seq = 8),
        SyncProtocol.decode("""{"type":"command","cmd":"blackout","on":false,"seq":8,"reason":"stop-all"}"""),
    )
    // A broken "on" is off, never black.
    assertFalse((SyncProtocol.decode("""{"type":"command","cmd":"blackout","on":"yes"}""") as ServerMessage.Command).on)
  }

  @Test
  fun encodesTheBlackoutReports() {
    val reply = Json.parseToJsonElement(SyncProtocol.encodeBlackout(true, seq = 7)).jsonObject
    assertEquals("blackout", reply["type"]!!.jsonPrimitive.content)
    assertTrue(reply["on"]!!.jsonPrimitive.boolean)
    assertEquals(7L, reply["seq"]!!.jsonPrimitive.long)
    assertFalse(reply.containsKey("liftedBy"))
    val lift = Json.parseToJsonElement(SyncProtocol.encodeBlackout(false, liftedBy = SyncProtocol.LIFTED_BY_WEARER)).jsonObject
    assertFalse(lift["on"]!!.jsonPrimitive.boolean)
    assertEquals("wearer", lift["liftedBy"]!!.jsonPrimitive.content)
    assertFalse(lift.containsKey("seq"))
  }

  @Test
  fun stateCarriesTheBlackoutFlagOnlyWhenReported() {
    val with = Json.parseToJsonElement(SyncProtocol.encodeState(emptyList(), emptyMap(), blackout = true)).jsonObject
    assertTrue(with["blackout"]!!.jsonPrimitive.boolean)
    val without = Json.parseToJsonElement(SyncProtocol.encodeState(emptyList(), emptyMap())).jsonObject
    assertFalse(without.containsKey("blackout"))
  }

  @Test
  fun theLiftedNoticeShowsInTheTagButNotOverStopAll() {
    val s = SyncState(link = SyncLink.CONNECTED, notice = "Black Out lifted")
    assertEquals("Black Out lifted", SyncBadge.of(s)!!.text)
    assertEquals("Black Out lifted", SyncBadge.of(s, quiet = true)!!.text)
    assertEquals("Stopped by your partner — wait for them to resume", SyncBadge.of(s.copy(stopped = true, byPartner = true, reason = "STOP ALL by x"))!!.text)
  }

  @Test
  fun stateCarriesTheHeadViewAndEachScreensBearing() {
    val shown = screen().copy(yawDeg = -12.34f, pitchDeg = 5.55f, distM = 2.6f, widthM = 1.6f, rollDeg = 0f)
    val o =
        parse(
            SyncProtocol.encodeState(
                listOf(shown, screen(id = "screen-2", focused = false)),
                emptyMap(),
                view = HeadView(yawDeg = 31.46f, pitchDeg = -8f, facing = "screen-1", posture = "lie"),
            ))
    val v = o["view"]!!.jsonObject
    assertEquals(31.5, v["yawDeg"]!!.jsonPrimitive.double, 0.0)
    assertEquals(-8.0, v["pitchDeg"]!!.jsonPrimitive.double, 0.0)
    assertEquals("screen-1", v["facing"]!!.jsonPrimitive.content)
    assertEquals("lie", v["posture"]!!.jsonPrimitive.content)
    val s = o["screens"]!!.jsonArray[0].jsonObject
    assertEquals(-12.3, s["yawDeg"]!!.jsonPrimitive.double, 0.0)
    assertEquals(2.6, s["distM"]!!.jsonPrimitive.double, 0.0)
    assertEquals(1.6, s["widthM"]!!.jsonPrimitive.double, 0.0)
    // A screen without a bearing sends none, and a head facing nothing sends null.
    assertFalse("yawDeg" in o["screens"]!!.jsonArray[1].jsonObject)
    val none = parse(SyncProtocol.encodeState(listOf(screen()), emptyMap(), view = HeadView(0f, 0f, null, "sit")))
    assertEquals(JsonNull, none["view"]!!.jsonObject["facing"])
    // No view at all (older flow), and a NaN head, leave the key out.
    assertFalse("view" in parse(SyncProtocol.encodeState(listOf(screen()), emptyMap())))
    assertFalse("view" in parse(SyncProtocol.encodeState(listOf(screen()), emptyMap(), view = HeadView(Float.NaN, 0f, null, "sit"))))
  }

  @Test
  fun turningTheHeadToAnotherScreenIsSentAtOnce() {
    val r = SyncReporter()
    val s = listOf(screen())
    assertTrue(r.shouldSend(s, 0, viewKey = HeadView(0f, 0f, "screen-1", "sit").key()))
    // Steady head: quiet until the heartbeat.
    assertFalse(r.shouldSend(s, 200, viewKey = HeadView(3f, 2f, "screen-1", "sit").key()))
    // Faces another screen, or turns 10 degrees, or changes posture: now.
    assertTrue(r.shouldSend(s, 300, viewKey = HeadView(3f, 2f, "screen-2", "sit").key()))
    assertTrue(r.shouldSend(s, 400, viewKey = HeadView(14f, 2f, "screen-2", "sit").key()))
    assertTrue(r.shouldSend(s, 500, viewKey = HeadView(14f, 2f, "screen-2", "lie").key()))
  }
}
