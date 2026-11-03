package app.trove.quest

import app.trove.quest.api.ApiException
import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.TroveApi
import app.trove.quest.play.ActivityReport
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PlayActivityHttpTest {
  private lateinit var server: MockWebServer

  @Before fun setUp() { server = MockWebServer().also { it.start() } }

  @After fun tearDown() = server.shutdown()

  private fun api() = TroveApi(server.url("/").toString(), PersistentCookieJar(MemoryCookieStorage()))

  private fun json(code: Int, body: String) =
      MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json; charset=utf-8")

  @Test
  fun reportPostsTheBodyAndParsesTheAck() = runBlocking {
    server.enqueue(json(200, """{"ok":true,"resumeTime":754.2,"playCounted":true}"""))
    val ack = api().activity("42", ActivityReport(754.2, 9.8, 1800.0, final = true))
    assertEquals(754.2, ack.resumeTime, 0.001)
    assertTrue(ack.playCounted)
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/api/scenes/42/activity", req.path)
    assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    assertEquals("""{"position":754.2,"played":9.8,"duration":1800.0,"final":true}""", req.body.readUtf8())
  }

  @Test
  fun errorStatusesSurfaceAsApiExceptions() = runBlocking {
    for (code in listOf(404, 502, 400, 401)) {
      server.enqueue(json(code, """{"error":"nope"}"""))
      try {
        api().activity("42", ActivityReport(1.0, 0.0))
        fail("expected $code")
      } catch (e: ApiException) {
        assertEquals(code, e.status)
      }
    }
  }

  @Test
  fun sceneCarriesTheServersResumeFields() = runBlocking {
    server.enqueue(json(200, """{"scene":{"id":"42","title":"T","duration":1800,"resumeTime":754.2,"playDuration":300,"lastPlayedAt":"2026-10-21T10:00:00Z"}}"""))
    val s = api().scene("42")
    assertEquals(754.2, s.resumeTime, 0.001)
    assertEquals("2026-10-21T10:00:00Z", s.lastPlayedAt)
  }

  @Test
  fun anOlderServerSceneWithoutTheFieldsDefaultsToNone() = runBlocking {
    server.enqueue(json(200, """{"scene":{"id":"42","title":"T"}}"""))
    val s = api().scene("42")
    assertEquals(0.0, s.resumeTime, 0.0)
    assertEquals(null, s.lastPlayedAt)
  }
}
