package app.trove.quest

import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.TroveApi
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.SyncClient
import app.trove.quest.sync.SyncLink
import app.trove.quest.sync.SyncScreen
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End to end against a real, locally started Trove server (UNARMED: its fake driver only logs).
 * Skipped unless TROVE_SYNC_E2E_URL, TROVE_SYNC_E2E_USER and TROVE_SYNC_E2E_PASSWORD are set; README
 * "Funscript sync" says how to start one. Never point it at the real server: it presses STOP ALL
 * and Resume for that user.
 */
class SyncLiveServerTest {
  private val base = System.getenv("TROVE_SYNC_E2E_URL")
  private val user = System.getenv("TROVE_SYNC_E2E_USER")
  private val password = System.getenv("TROVE_SYNC_E2E_PASSWORD")

  /** The fake screen's clock, read on the client's thread and changed by commands and the test. */
  private class Video(@Volatile var pos0: Long, @Volatile var at0: Long) {
    @Volatile var playing = true

    fun pos() = pos0 + if (playing) System.currentTimeMillis() - at0 else 0
  }

  @Test
  fun quest() {
    assumeTrue("set TROVE_SYNC_E2E_URL/_USER/_PASSWORD to run", base != null && user != null && password != null)
    val executor = Executors.newSingleThreadExecutor()
    val main = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + main)
    val api = TroveApi(base!!, PersistentCookieJar(MemoryCookieStorage()))
    runBlocking { api.login(user!!, password!!) }
    val commands = CopyOnWriteArrayList<ServerMessage.Command>()

    // One focused screen, playing at 1x from 5 s: positions as the app would report them.
    val t0 = System.currentTimeMillis()
    val v = Video(pos0 = 5_000L, at0 = t0)
    fun pos() = v.pos()
    val client =
        SyncClient(
            api, scope,
            screens = { listOf(SyncScreen("screen-1", "42", "E2E scene", v.pos(), 600_000, v.playing, 1f, true, "sbs180")) },
            headset = { mapOf("inOurApp" to true, "battery" to 64, "charging" to false, "tempC" to 30.5) },
            onCommand = { c ->
              commands += c
              if (c.cmd == "pause-all" || c.cmd == "pause") {
                v.pos0 = v.pos()
                v.at0 = System.currentTimeMillis()
                v.playing = false
              }
            },
        )
    val origin = api.baseUrl.toString().trimEnd('/')
    fun call(path: String, body: String? = null): JsonObject {
      val b = Request.Builder().url(api.baseUrl.resolve(path)!!).header("Origin", origin)
      if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
      api.http.newCall(b.build()).execute().use { r ->
        val text = r.body.string()
        assertTrue("$path: ${r.code} $text", r.isSuccessful)
        return Json.parseToJsonElement(text).jsonObject
      }
    }
    fun syncState() = call("api/sync/state")
    fun waitFor(what: String, cond: () -> Boolean) {
      val end = System.currentTimeMillis() + 8000
      while (!cond()) {
        if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what; state ${syncState()}")
        Thread.sleep(50)
      }
    }
    fun device() = syncState()["device"]!!.jsonObject["status"]!!.jsonPrimitive.content

    try {
      runBlocking { withContext(main) { client.setActive(true) } }
      waitFor("connected") { client.state.value.link == SyncLink.CONNECTED }
      // The server's own latch at start ("not started yet") comes right after hello.
      waitFor("initial latch") { client.state.value.stopped }
      assertTrue(!client.state.value.byPartner)

      // The console sees our screen and headset values.
      waitFor("player listed") { syncState()["players"]!!.jsonArray.isNotEmpty() }
      val p = syncState()["players"]!!.jsonArray[0].jsonObject
      assertEquals("quest", p["client"]!!.jsonPrimitive.content)
      val s = p["screens"]!!.jsonArray[0].jsonObject
      assertEquals("42", s["sceneId"]!!.jsonPrimitive.content)
      assertTrue(s["focused"]!!.jsonPrimitive.boolean)
      assertEquals(64.0, p["headset"]!!.jsonObject["battery"]!!.jsonPrimitive.double, 0.0)
      // The server's extrapolated clock tracks ours within the report interval.
      val drift = Math.abs(s["positionMs"]!!.jsonPrimitive.double - pos())
      assertTrue("server clock off by $drift ms", drift < 1000)

      // Resume: the (fake) device runs from our clock.
      call("api/sync/resume", "{}")
      waitFor("resumed") { !client.state.value.stopped }
      waitFor("device playing") { device() == "playing" }

      // Partner remote control: seek reaches us as a command for the screen.
      call("api/sync/control", """{"action":"seek","deltaMs":10000}""")
      waitFor("seek command") { commands.any { it.cmd == "seek" } }
      assertEquals(10_000L, commands.first { it.cmd == "seek" }.deltaMs)

      // STOP ALL: pause-all to us, latched, shown as the partner's.
      call("api/sync/stop", "{}")
      waitFor("pause-all") { commands.any { it.cmd == "pause-all" && it.reason == "STOP ALL" } }
      waitFor("byPartner") { client.state.value.byPartner }
      assertTrue(!v.playing)
      waitFor("device stopped") { device() == "stopped" }

      // Resume again, play: running; then the app goes away while it runs -> dead-man latch.
      call("api/sync/resume", "{}")
      v.at0 = System.currentTimeMillis()
      v.playing = true
      runBlocking { withContext(main) { client.reportNow() } }
      waitFor("device playing again") { device() == "playing" }
      runBlocking { withContext(main) { client.setActive(false) } }
      waitFor("dead-man on disconnect") { device() == "lost" }
    } finally {
      runBlocking { withContext(main) { client.setActive(false) } }
      scope.cancel()
      executor.shutdownNow()
    }
  }
}
