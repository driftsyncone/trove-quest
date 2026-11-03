package app.trove.quest

import app.trove.quest.api.ApiException
import app.trove.quest.api.Facet
import app.trove.quest.api.FacetType
import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.SceneQuery
import app.trove.quest.api.TroveApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class TroveApiTest {
  private lateinit var server: MockWebServer
  private lateinit var storage: MemoryCookieStorage

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    storage = MemoryCookieStorage()
  }

  @After
  fun tearDown() = server.shutdown()

  private fun api(storage: MemoryCookieStorage = this.storage) =
      TroveApi(server.url("/").toString(), PersistentCookieJar(storage))

  private fun json(code: Int, body: String, vararg headers: Pair<String, String>) =
      MockResponse().setResponseCode(code).setBody(body).apply {
        setHeader("Content-Type", "application/json; charset=utf-8")
        for ((k, v) in headers) addHeader(k, v)
      }

  @Test
  fun wrongLoginIsA401WithTheServerMessage() = runBlocking {
    server.enqueue(json(401, """{"error":"Wrong name or password."}"""))
    try {
      api().login("nobody", "wrong")
      fail("expected ApiException")
    } catch (e: ApiException) {
      assertEquals(401, e.status)
      assertEquals("Wrong name or password.", e.message)
    }
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/api/login", req.path)
    assertEquals("""{"name":"nobody","password":"wrong"}""", req.body.readUtf8())
    assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
  }

  @Test
  fun sessionCookieSurvivesARestartAndLogoutClearsIt() = runBlocking {
    server.enqueue(
        json(
            200,
            """{"user":{"id":1,"name":"alice","isAdmin":true}}""",
            "Set-Cookie" to "trove_session=abc123; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000",
        ))
    val user = api().login("alice", "pw")
    assertEquals("alice", user.name)
    server.takeRequest()

    // A new jar from the same storage = app restart.
    val restarted = api()
    assertTrue(restarted.hasSession())
    server.enqueue(json(200, """{"user":{"id":1,"name":"alice","isAdmin":true}}"""))
    restarted.me()
    assertEquals("trove_session=abc123", server.takeRequest().getHeader("Cookie"))

    server.enqueue(json(200, """{"ok":true}""", "Set-Cookie" to "trove_session=; Path=/; HttpOnly; Max-Age=0"))
    restarted.logout()
    assertFalse(restarted.hasSession())
    assertFalse(api().hasSession())
  }

  @Test
  fun wallQueryMatchesTheWebUiParams() {
    val a = api()
    assertEquals("api/scenes?perPage=12", a.scenesPath(SceneQuery()))
    assertEquals(
        "api/scenes?filter=7&q=foo%20bar&sort=random&dir=ASC&page=3&seed=42&perPage=12",
        a.scenesPath(SceneQuery(filterId = "7", q = " foo bar ", sort = "random", dir = "ASC", seed = 42, page = 3)))
  }

  @Test
  fun performerAndStudioSortsGoOutAsSortAndDir() {
    val a = api()
    assertEquals("api/scenes?sort=performer&dir=ASC&perPage=12", a.scenesPath(SceneQuery(sort = "performer", dir = "ASC")))
    assertEquals(
        "api/scenes?filter=3&q=x&studios=9&sort=studio&dir=DESC&page=2&perPage=12",
        a.scenesPath(
            SceneQuery(
                filterId = "3", q = "x", facets = listOf(Facet(FacetType.STUDIO, "9", "S")),
                sort = "studio", dir = "DESC", page = 2)))
  }

  @Test
  fun sortChipRowLabelsPerformerAndStudio() {
    assertEquals("Performer", app.trove.quest.ui.SORT_LABELS["performer"])
    assertEquals("Studio", app.trove.quest.ui.SORT_LABELS["studio"])
    // Every sort the server lists today has a label (the chip row falls back to the raw key otherwise).
    val serverSorts = listOf("date", "created_at", "updated_at", "title", "random", "rating", "play_count",
        "o_counter", "duration", "last_played_at", "filesize", "interactive_speed", "performer", "studio")
    for (s in serverSorts) assertTrue(s, app.trove.quest.ui.SORT_LABELS.containsKey(s))
  }

  @Test
  fun parsesScenesAndIgnoresUnknownFields() = runBlocking {
    server.enqueue(
        json(
            200,
            """{"count":1,"scenes":[{"id":"5","title":"T","date":null,"duration":61.5,"width":7680,
              "height":2160,"codec":"hevc","rating":null,"interactive":true,"oCount":0,"playCount":2,
              "studio":{"id":"1","name":"S"},"performers":[],"thumbVersion":"99","projection":"sbs","new":1}]}"""))
    val res = api().scenes(SceneQuery())
    assertEquals(1, res.count)
    assertEquals("sbs", res.scenes[0].projection)
    assertTrue(res.scenes[0].interactive)
    assertTrue(api().thumbUrl(res.scenes[0]).endsWith("/media/scene/5/thumb?w=480&v=99"))
  }

  /**
   * One deliberately wrong login against a real server, to confirm the 401 shape end to end.
   * Off by default: TROVE_LIVE_URL=https://trove.example.com ./gradlew testDebugUnitTest
   * (each run counts as one failed attempt toward the server's per-IP lockout of 10 / 15 min).
   */
  @Test
  fun liveServerRejectsAWrongLogin() = runBlocking {
    val url = System.getenv("TROVE_LIVE_URL")
    assumeTrue("TROVE_LIVE_URL not set", !url.isNullOrBlank())
    val live = TroveApi(url!!, PersistentCookieJar(MemoryCookieStorage()))
    try {
      live.me()
      fail("expected 401 without a cookie")
    } catch (e: ApiException) {
      assertEquals(401, e.status)
      assertEquals("Not logged in", e.message)
    }
    try {
      live.login("trove-quest-probe", "not-a-real-password")
      fail("expected ApiException")
    } catch (e: ApiException) {
      println("live login answer: ${e.status} ${e.message}")
      assertEquals(401, e.status)
      assertEquals("Wrong name or password.", e.message)
    }
    assertFalse(live.hasSession())
  }
}
