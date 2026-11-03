package app.trove.quest

import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.TroveApi
import app.trove.quest.player.MediaPlayers
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The HTTP clients' limits (MediaPlayers, TroveApi): Storm's tiles and the wall's covers are
 * `enqueue`d, and OkHttp's default dispatcher let only 5 per host wait for their headers at once.
 */
class HttpLimitsTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() = server.shutdown()

  private fun api() = TroveApi(server.url("/").toString(), PersistentCookieJar(MemoryCookieStorage()))

  @Test
  fun mediaClientIsHttp11WithItsOwnDispatcherAndPool() {
    val api = api().http
    val media = MediaPlayers.mediaClient(api)
    assertEquals(listOf(Protocol.HTTP_1_1), media.protocols)
    assertNotSame(api.dispatcher, media.dispatcher)
    assertNotSame(api.connectionPool, media.connectionPool)
    assertEquals(MediaPlayers.MAX_REQUESTS_PER_HOST, media.dispatcher.maxRequestsPerHost)
    // Same cookies (the session) as the API.
    assertSame(api.cookieJar, media.cookieJar)
    // One media client per API client.
    assertSame(media, MediaPlayers.mediaClient(api))
  }

  @Test
  fun apiClientLetsAWallPageOfCoversRunAtOnce() {
    assertTrue(api().http.dispatcher.maxRequestsPerHost >= 12)
  }

  /** How many of [n] enqueued calls reach the server while every answer is held back. */
  private fun concurrentAtServer(client: OkHttpClient, n: Int): Int {
    repeat(n) { server.enqueue(MockResponse().setHeadersDelay(1500, TimeUnit.MILLISECONDS).setBody("x")) }
    val done = CountDownLatch(n)
    repeat(n) { i ->
      client
          .newCall(Request.Builder().url(server.url("/media/scene/$i/preview")).build())
          .enqueue(
              object : Callback {
                override fun onFailure(call: Call, e: IOException) = done.countDown()

                override fun onResponse(call: Call, response: Response) {
                  response.close()
                  done.countDown()
                }
              })
    }
    val end = System.currentTimeMillis() + 1000
    while (server.requestCount < n && System.currentTimeMillis() < end) Thread.sleep(10)
    val seen = server.requestCount
    done.await(10, TimeUnit.SECONDS)
    return seen
  }

  @Test
  fun aFullStormStartsEveryTileAtOnce() {
    val media = MediaPlayers.mediaClient(api().http)
    assertEquals(14, concurrentAtServer(media, 14))
  }

  @Test
  fun theOldDefaultStartedThemInWavesOfFive() {
    // The baseline this change fixes: a stock client lets 5 through until the first answers come.
    assertEquals(5, concurrentAtServer(OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).build(), 14))
  }
}
