package app.trove.quest

import android.content.SharedPreferences
import app.trove.quest.api.Card
import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.TroveApi
import app.trove.quest.ui.AppModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The wall's cold start (filters and the first page at once) and the next page fetched ahead (ui/PageCache). */
class WallPrefetchTest {
  private lateinit var server: MockWebServer
  private val executor = Executors.newSingleThreadExecutor()
  private val main = executor.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + main)
  private val paths = CopyOnWriteArrayList<String>()
  private val covers = CopyOnWriteArrayList<String>()

  /** No preferences: any call answers null (nothing on these paths writes). */
  private val prefs =
      java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, _, _ -> null }
          as SharedPreferences

  @Before
  fun setUp() {
    server = MockWebServer()
    server.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            paths += path
            return when {
              path == "/api/me" -> MockResponse().setBody("""{"user":{"id":1,"name":"alice"}}""")
              // A slow saved-filter lookup: the first page must not wait for it.
              path == "/api/filters" ->
                  MockResponse().setHeadersDelay(1200, TimeUnit.MILLISECONDS).setBody("""{"filters":[{"id":"f1","name":"Fav"}],"sorts":["date"]}""")
              path.startsWith("/api/scenes?") -> {
                val page = request.requestUrl!!.queryParameter("page")?.toInt() ?: 1
                val n = if (page < 3) 12 else 6 // 30 scenes, 12 a page
                val cards = (1..n).joinToString(",") { """{"id":"p$page-$it","title":"Scene $it"}""" }
                MockResponse().setBody("""{"count":30,"scenes":[$cards]}""")
              }
              else -> MockResponse().setResponseCode(404).setBody("""{"error":"no"}""")
            }
          }
        }
    server.start()
  }

  @After
  fun tearDown() {
    scope.cancel()
    executor.shutdownNow()
    server.shutdown()
  }

  private fun waitFor(what: String, ms: Long = 5000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!cond()) {
      if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
      Thread.sleep(10)
    }
  }

  private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

  private fun loggedInModel(): AppModel {
    val jar = PersistentCookieJar(MemoryCookieStorage())
    val url = server.url("/")
    jar.saveFromResponse(url, listOf(Cookie.parse(url, "${TroveApi.SESSION_COOKIE}=s; Path=/")!!))
    return AppModel(
        TroveApi(url.toString(), jar), prefs, scope,
        onPlay = {}, onLoggedOut = {},
        prefetchCovers = { cards: List<Card> -> covers += cards.map { it.id } },
    )
  }

  private fun scenePages(page: Int) = paths.count { it.startsWith("/api/scenes?") && (if (page == 1) "page=" !in it else "page=$page&" in it) }

  @Test
  fun theFirstPageDoesNotWaitForTheFilterList() {
    val m = loggedInModel()
    onMain { m.start() }
    waitFor("first page", ms = 1000) { m.scenes.size == 12 }
    assertTrue("filters still on their way", m.filters.isEmpty())
    waitFor("filters") { m.filters.isNotEmpty() }
    assertEquals(null, m.wallError)
  }

  @Test
  fun nextAndPrevShowAtOnceFromThePagesFetchedAhead() {
    val m = loggedInModel()
    onMain { m.start() }
    waitFor("first page") { m.scenes.size == 12 }
    waitFor("page 2 fetched ahead with its covers") { covers.size == 12 }
    assertEquals("p2-1", covers[0])
    assertEquals(1, scenePages(2))

    // Next: the answer is already here, nothing to wait for.
    onMain { m.goToPage(2) }
    assertEquals("p2-1", m.scenes[0].id)
    assertFalse(m.loading)
    assertEquals(1, scenePages(2))

    // ... and the last page is fetched ahead in turn.
    waitFor("page 3 fetched ahead") { scenePages(3) == 1 && covers.size == 18 }

    // Prev: the page just seen.
    onMain { m.goToPage(1) }
    assertEquals("p1-1", m.scenes[0].id)
    assertFalse(m.loading)
    assertEquals(1, scenePages(1))
  }

  @Test
  fun aNewSearchStillAsksTheServer() {
    val m = loggedInModel()
    onMain { m.start() }
    waitFor("first page") { m.scenes.size == 12 }
    onMain {
      m.typeSearch("x")
      m.search()
    }
    waitFor("search") { paths.any { it.startsWith("/api/scenes?q=x") } }
  }
}
