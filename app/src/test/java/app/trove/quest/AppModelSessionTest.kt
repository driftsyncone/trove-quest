package app.trove.quest

import android.content.SharedPreferences
import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.TroveApi
import app.trove.quest.ui.AppModel
import app.trove.quest.ui.Screen
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** [AppModel] on a 401: whatever plays is stopped before the login shows (screens, Storm, sync report). */
class AppModelSessionTest {
  private lateinit var server: MockWebServer
  private val executor = Executors.newSingleThreadExecutor()
  private val main = executor.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + main)

  /** The wall's reload never touches the preferences; any call answers null. */
  private val prefs =
      java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, _, _ -> null }
          as SharedPreferences

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

  private fun waitFor(what: String, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + 5000
    while (!cond()) {
      if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
      Thread.sleep(20)
    }
  }

  @Test
  fun aSessionThatEndsStopsPlaybackBeforeTheLoginShows() {
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
    val api = TroveApi(server.url("/").toString(), PersistentCookieJar(MemoryCookieStorage()))
    val events = CopyOnWriteArrayList<String>()
    lateinit var model: AppModel
    model =
        AppModel(
            api, prefs, scope,
            onPlay = {},
            onLoggedOut = { events += "logged out" },
            onSessionLost = { events += "stop everything (login shown: ${model.screen == Screen.LOGIN})" },
        )
    runBlocking { withContext(main) { model.reload() } }
    waitFor("login") { model.screen == Screen.LOGIN }
    assertEquals(listOf("stop everything (login shown: false)"), events)
    assertEquals("Your session ended. Log in again.", model.loginError)
  }
}
