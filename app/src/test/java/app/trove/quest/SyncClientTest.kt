package app.trove.quest

import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.TroveApi
import app.trove.quest.sync.DirectorAction
import app.trove.quest.sync.DirectorCommand
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.SyncClient
import app.trove.quest.sync.SyncLink
import app.trove.quest.sync.SyncScreen
import app.trove.quest.sync.SyncState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [SyncClient] against MockWebServer's WebSocket: cookie, reports, commands, status, reconnect. */
class SyncClientTest {
  private lateinit var server: MockWebServer
  private val executor = Executors.newSingleThreadExecutor()
  private val main = executor.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + main)
  private val commands = CopyOnWriteArrayList<ServerMessage.Command>()
  @Volatile private var screens = listOf(SyncScreen("screen-1", "42", "T", 1000, 60_000, true, 1f, true, "flat"))

  /** One accepted socket on the fake server: what it received, and a handle to talk back. */
  private class Peer : WebSocketListener() {
    val received = LinkedBlockingQueue<String>()
    @Volatile var ws: WebSocket? = null
    val opened = java.util.concurrent.CountDownLatch(1)

    override fun onOpen(webSocket: WebSocket, response: Response) {
      ws = webSocket
      opened.countDown()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      received += text
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(1000, null)
    }
  }

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    scope.cancel()
    executor.shutdownNow()
    server.shutdown()
  }

  private val directed = CopyOnWriteArrayList<DirectorCommand>()
  private val dropped = java.util.concurrent.atomic.AtomicInteger()

  private val vgAcks = CopyOnWriteArrayList<ServerMessage.VgAck>()

  private fun client(blackout: () -> Boolean? = { null }, onLinkLost: () -> Unit = {}): SyncClient {
    val jar = PersistentCookieJar(MemoryCookieStorage())
    val url = server.url("/")
    jar.saveFromResponse(url, listOf(Cookie.parse(url, "trove_session=tok123; Path=/; HttpOnly")!!))
    return SyncClient(
        TroveApi(url.toString(), jar),
        scope,
        screens = { screens },
        headset = { mapOf("inOurApp" to true, "battery" to 77) },
        onCommand = { commands += it },
        blackout = blackout,
        onLinkLost = onLinkLost,
        onDirector = { directed += it },
        onEscapeDropped = { dropped.incrementAndGet() },
        onVgAck = { vgAcks += it },
    )
  }

  private fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }

  private fun waitFor(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
      Thread.sleep(20)
    }
  }

  private fun Peer.next(): String = received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no message")

  private fun state(c: SyncClient): SyncState = c.state.value

  @Test
  fun connectsWithTheCookieReportsAndObeys() {
    val peer = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(peer))
    val c = client()
    onMain { c.setActive(true) }
    val req = server.takeRequest(5, TimeUnit.SECONDS)!!
    assertEquals("/api/sync/ws?role=player&client=quest&caps=director", req.path)
    assertEquals("trove_session=tok123", req.getHeader("Cookie"))
    assertNull("the native app sends no Origin", req.getHeader("Origin"))

    // First report right after connecting.
    val first = Json.parseToJsonElement(peer.next()).jsonObject
    assertEquals("state", first["type"]!!.jsonPrimitive.content)
    assertEquals("42", first["screens"]!!.jsonArray[0].jsonObject["sceneId"]!!.jsonPrimitive.content)
    assertEquals("77", first["headset"]!!.jsonObject["battery"]!!.jsonPrimitive.content)
    waitFor("connected") { state(c).link == SyncLink.CONNECTED }

    // Heartbeats keep coming without changes.
    val t0 = System.currentTimeMillis()
    peer.next()
    assertTrue("heartbeat within ~1 s", System.currentTimeMillis() - t0 < 1500)

    // A partner STOP ALL: the latch status, then the command; a report follows at once.
    // hello.serverNow: a server clock 10 min ahead of this one gives the timer's clock offset.
    peer.ws!!.send("""{"type":"hello","id":"p1","deadmanMs":3000,"heartbeatMs":1000,"serverNow":${System.currentTimeMillis() + 600_000}}""")
    waitFor("clock offset") { kotlin.math.abs(state(c).clockOffsetMs - 600_000) < 2_000 }
    peer.ws!!.send("""{"type":"status","stopped":true,"reason":"STOP ALL by alice","device":"stopped"}""")
    peer.ws!!.send("""{"type":"command","cmd":"pause-all","reason":"STOP ALL"}""")
    waitFor("command") { commands.size == 1 }
    assertEquals(ServerMessage.Command("pause-all", null, 0, "STOP ALL"), commands[0])
    val s = state(c)
    assertTrue(s.stopped && s.byPartner)
    assertEquals("stopped", s.device)

    // Resume: unlatched.
    peer.ws!!.send("""{"type":"status","stopped":false,"reason":null,"device":"idle"}""")
    waitFor("resumed") { !state(c).stopped && !state(c).byPartner }

    onMain { c.setActive(false) }
    assertEquals(SyncState(), state(c))
  }

  @Test
  fun vacuGlideStatusAcksAndMessagesTravelOverTheSocket() {
    val first = Peer()
    val second = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(first))
    server.enqueue(MockResponse().withWebSocketUpgrade(second))
    val c = client()
    // Nothing goes out before the link is up.
    assertFalse(c.sendVgValve(app.trove.quest.sync.ValveKey.TIGHTER, true))
    assertFalse(c.sendVgSet(offsetMs = 50))
    assertFalse(c.sendVgReload())
    onMain { c.setActive(true) }
    assertTrue(first.opened.await(5, TimeUnit.SECONDS))
    first.next()
    waitFor("connected") { state(c).link == SyncLink.CONNECTED }
    assertFalse("a server without vg: not connected", state(c).vg.connected)

    first.ws!!.send(
        """{"type":"status","stopped":false,"reason":null,"device":"idle","armed":true,"resume":"no","vg":{"connected":true,"mode":"ONLINE_CONNECTED","offsetMs":150,"intensity":80,"speedLimit":300,"suction":2}}""")
    waitFor("vg in the state") { state(c).vg.connected }
    assertEquals(150, state(c).vg.offsetMs)
    assertEquals(true, state(c).armed)

    // Hold-to-use: press and release go out as they happen.
    assertTrue(c.sendVgValve(app.trove.quest.sync.ValveKey.LOOSER, true))
    assertTrue(c.sendVgValve(app.trove.quest.sync.ValveKey.LOOSER, false))
    assertTrue(c.sendVgSet(offsetMs = 200))
    assertTrue(c.sendVgReload())
    val got = mutableListOf<String>()
    while (got.size < 4) {
      val m = first.next()
      if (!m.contains("\"type\":\"state\"")) got += m
    }
    assertEquals(
        listOf(
            """{"type":"vg","op":"valve","name":"looser","on":true}""",
            """{"type":"vg","op":"valve","name":"looser","on":false}""",
            """{"type":"vg","op":"set","offsetMs":200}""",
            """{"type":"vg","op":"reload"}""",
        ),
        got)

    first.ws!!.send("""{"type":"vg-ack","op":"valve","ok":false,"error":"Black Out is on"}""")
    waitFor("ack") { vgAcks.size == 1 }
    assertEquals(ServerMessage.VgAck("valve", false, "Black Out is on"), vgAcks[0])

    // The link drops: the VacuGlide is gone from the state at once (the panel goes with it).
    first.ws!!.close(1001, "restart")
    waitFor("reconnecting") { state(c).link == SyncLink.RECONNECTING }
    assertFalse(state(c).vg.connected)
    assertFalse("nothing can be sent while down", c.sendVgValve(app.trove.quest.sync.ValveKey.TIGHTER, true))
    assertTrue(second.opened.await(5, TimeUnit.SECONDS))
    onMain { c.setActive(false) }
  }

  @Test
  fun reconnectsAfterTheServerGoesAway() {
    val first = Peer()
    val second = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(first))
    server.enqueue(MockResponse().withWebSocketUpgrade(second))
    val c = client()
    onMain { c.setActive(true) }
    assertTrue(first.opened.await(5, TimeUnit.SECONDS))
    first.next()
    first.ws!!.close(1001, "server restart")
    waitFor("reconnecting") { state(c).link == SyncLink.RECONNECTING }
    // Backoff starts at 1 s.
    assertTrue(second.opened.await(5, TimeUnit.SECONDS))
    assertNotNull(second.next())
    waitFor("connected again") { state(c).link == SyncLink.CONNECTED }
    onMain { c.setActive(false) }
  }

  @Test
  fun refusedUpgradeRetriesAndNothingIsSentWhileOff() {
    server.enqueue(MockResponse().setResponseCode(401))
    val c = client()
    onMain { c.setActive(true) }
    server.takeRequest(5, TimeUnit.SECONDS)
    waitFor("reconnecting") { state(c).link == SyncLink.RECONNECTING }
    onMain { c.setActive(false) }
    assertEquals(SyncLink.OFF, state(c).link)
    // Switched off: no further attempt even after the short backoff would have passed.
    Thread.sleep(1500)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun blackoutIsReportedInHeartbeatsRepliedToAtOnceAndLiftedWhenTheLinkDrops() {
    val peer = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(peer))
    var black = false
    val lost = java.util.concurrent.atomic.AtomicInteger()
    val cl = client(blackout = { black }, onLinkLost = { lost.incrementAndGet() })
    onMain { cl.setActive(true) }
    val first = Json.parseToJsonElement(peer.next()).jsonObject
    assertEquals("false", first["blackout"]!!.jsonPrimitive.content)
    waitFor("connected") { state(cl).link == SyncLink.CONNECTED }

    // The command is handed to the app, which answers through sendBlackout; heartbeats then say true.
    peer.ws!!.send("""{"type":"command","cmd":"blackout","on":true,"seq":5}""")
    waitFor("command") { commands.size == 1 }
    assertEquals(ServerMessage.Command("blackout", null, 0, null, on = true, seq = 5), commands[0])
    black = true
    onMain { cl.sendBlackout(true, seq = 5) }
    var reply: kotlinx.serialization.json.JsonObject? = null
    waitFor("reply") {
      reply = Json.parseToJsonElement(peer.next()).jsonObject.takeIf { it["type"]!!.jsonPrimitive.content == "blackout" }
      reply != null
    }
    assertEquals("5", reply!!["seq"]!!.jsonPrimitive.content)
    val hb = Json.parseToJsonElement(peer.next()).jsonObject
    assertEquals("state", hb["type"]!!.jsonPrimitive.content)
    assertEquals("true", hb["blackout"]!!.jsonPrimitive.content)

    // Link drops: the app is told, so the wearer is never stuck in black.
    peer.ws!!.close(1001, "gone")
    waitFor("link lost") { lost.get() >= 1 }
    onMain { cl.setActive(false) }
  }

  /** Next message of [type] from the client, skipping heartbeats. */
  private fun Peer.nextOf(type: String): kotlinx.serialization.json.JsonObject {
    var found: kotlinx.serialization.json.JsonObject? = null
    waitFor("a $type message") {
      found = Json.parseToJsonElement(next()).jsonObject.takeIf { it["type"]!!.jsonPrimitive.content == type }
      found != null
    }
    return found!!
  }

  @Test
  fun directorCommandsReachTheAppAndTheAckGoesBack() {
    val peer = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(peer))
    val c = client()
    onMain { c.setActive(true) }
    peer.nextOf("state")
    waitFor("connected") { state(c).link == SyncLink.CONNECTED }
    peer.ws!!.send("""{"type":"command","cmd":"open","seq":12,"sceneId":"77","mode":"new","startMs":5000}""")
    waitFor("director command") { directed.size == 1 }
    assertEquals(DirectorCommand(12, DirectorAction.Open("77", true, null, 5000)), directed[0])
    assertTrue("a Director command is not a plain command", commands.isEmpty())
    onMain { c.sendAck(12, true) }
    val ack = peer.nextOf("ack")
    assertEquals("12", ack["seq"]!!.jsonPrimitive.content)
    assertEquals("true", ack["ok"]!!.jsonPrimitive.content)
    // A command the app cannot read is answered with an error by the client itself.
    peer.ws!!.send("""{"type":"command","cmd":"swap","seq":13,"sceneId":"5"}""")
    val bad = peer.nextOf("ack")
    assertEquals("13", bad["seq"]!!.jsonPrimitive.content)
    assertEquals("false", bad["ok"]!!.jsonPrimitive.content)
    assertTrue(directed.size == 1)
    onMain { c.setActive(false) }
  }

  @Test
  fun resumeGoesOutOnlyWithTheLinkUpAndCarriesConfirm() {
    val peer = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(peer))
    val c = client()
    var sent = true
    onMain { sent = c.sendResume() }
    assertFalse("no link, nothing queued", sent)
    onMain { c.setActive(true) }
    peer.nextOf("state")
    waitFor("connected") { state(c).link == SyncLink.CONNECTED }
    onMain { sent = c.sendResume() }
    assertTrue(sent)
    val plain = peer.nextOf("resume")
    assertNull(plain["confirm"])
    onMain { c.sendResume(confirm = true) }
    assertEquals("true", peer.nextOf("resume")["confirm"]!!.jsonPrimitive.content)
    onMain { c.setActive(false) }
  }

  @Test
  fun wearerStopGoesOutAtOnceWhenConnected() {
    val peer = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(peer))
    val c = client()
    onMain { c.setActive(true) }
    peer.nextOf("state")
    waitFor("connected") { state(c).link == SyncLink.CONNECTED }
    onMain { c.sendWearerStop() }
    assertEquals("wearer-stop", peer.nextOf("wearer-stop")["type"]!!.jsonPrimitive.content)
    assertFalse(c.escapeQueued)
    onMain { c.setActive(false) }
  }

  @Test
  fun wearerStopWithTheLinkDownIsSentAfterTheReconnectOnlyIfStillRelevant() {
    val first = Peer()
    val second = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(first))
    server.enqueue(MockResponse().withWebSocketUpgrade(second))
    val c = client()
    onMain { c.setActive(true) }
    first.nextOf("state")
    first.ws!!.close(1001, "gone")
    waitFor("reconnecting") { state(c).link == SyncLink.RECONNECTING }
    onMain { c.sendWearerStop() }
    assertTrue("queued, not lost", c.escapeQueued)
    assertTrue(second.opened.await(5, TimeUnit.SECONDS))
    // Nothing is sent until the server's status says whether it is stopped already.
    second.nextOf("state")
    assertTrue(c.escapeQueued)
    second.ws!!.send("""{"type":"status","stopped":false,"reason":null,"device":"playing"}""")
    assertEquals("wearer-stop", second.nextOf("wearer-stop")["type"]!!.jsonPrimitive.content)
    assertFalse(c.escapeQueued)
    assertEquals(0, dropped.get())
    onMain { c.setActive(false) }
  }

  @Test
  fun aQueuedWearerStopIsDroppedWhenTheServerIsStoppedAlready() {
    val first = Peer()
    val second = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(first))
    server.enqueue(MockResponse().withWebSocketUpgrade(second))
    val c = client()
    onMain { c.setActive(true) }
    first.nextOf("state")
    first.ws!!.close(1001, "gone")
    waitFor("reconnecting") { state(c).link == SyncLink.RECONNECTING }
    onMain { c.sendWearerStop() }
    assertTrue(second.opened.await(5, TimeUnit.SECONDS))
    second.ws!!.send("""{"type":"status","stopped":true,"reason":"Partner console lost (dead-man)","device":"lost"}""")
    waitFor("dropped") { !c.escapeQueued }
    // Not stale, just redundant: the local escape state rides on the server's latch instead.
    assertEquals(0, dropped.get())
    // Only heartbeats follow, never a stop.
    Thread.sleep(1200)
    assertTrue(second.received.none { it.contains("wearer-stop") })
    onMain { c.setActive(false) }
  }

  @Test
  fun aStopAllDuringALinkGapStopsOnTheStatusAfterTheReconnect() {
    val first = Peer()
    val second = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(first))
    server.enqueue(MockResponse().withWebSocketUpgrade(second))
    val c = client()
    // What the activity does with the state flow: a rising edge of a person's STOP = pause-all + room.
    val edge = app.trove.quest.sync.PersonStopEdge()
    val stops = java.util.concurrent.atomic.AtomicInteger()
    onMain {
      c.setActive(true)
      scope.launch { c.state.collect { if (edge.rose(it)) stops.incrementAndGet() } }
    }
    first.nextOf("state")
    first.ws!!.send("""{"type":"status","stopped":false,"reason":null,"device":"playing","armed":true}""")
    waitFor("armed") { state(c).armed == true }
    first.ws!!.close(1001, "wifi blip")
    waitFor("reconnecting") { state(c).link == SyncLink.RECONNECTING }
    // The partner pressed STOP ALL meanwhile: after the reconnect only the status says so.
    assertTrue(second.opened.await(5, TimeUnit.SECONDS))
    second.nextOf("state")
    second.ws!!.send("""{"type":"status","stopped":true,"reason":"STOP ALL by alice","device":"stopped","armed":true}""")
    waitFor("stop edge") { stops.get() == 1 }
    assertTrue(state(c).byPartner)
    // The server's resent pause-all still reaches the activity (idempotent there); no second edge.
    second.ws!!.send("""{"type":"command","cmd":"pause-all","reason":"STOP ALL"}""")
    waitFor("command") { commands.any { it.cmd == "pause-all" } }
    second.ws!!.send("""{"type":"status","stopped":true,"reason":"STOP ALL by alice","device":"stopped","armed":true}""")
    Thread.sleep(200)
    assertEquals(1, stops.get())
    onMain { c.setActive(false) }
  }

  @Test
  fun wearerLatchShowsAsStoppedByYou() {
    val peer = Peer()
    server.enqueue(MockResponse().withWebSocketUpgrade(peer))
    val c = client()
    onMain { c.setActive(true) }
    peer.nextOf("state")
    waitFor("connected") { state(c).link == SyncLink.CONNECTED }
    peer.ws!!.send("""{"type":"status","stopped":true,"reason":"Stopped by the wearer","device":"stopped"}""")
    waitFor("latch") { state(c).stopped }
    assertTrue(state(c).byWearer)
    assertFalse("not the partner's", state(c).byPartner)
    onMain { c.setActive(false) }
  }
}
