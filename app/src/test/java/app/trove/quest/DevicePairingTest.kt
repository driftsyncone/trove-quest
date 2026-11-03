package app.trove.quest

import app.trove.quest.api.DevicePairing
import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PairingState
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.TroveApi
import app.trove.quest.ui.qrMatrix
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Device pairing client against a mock Trove server (server/pairing.ts). */
class DevicePairingTest {
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

  private fun api() =
      TroveApi(server.url("/").toString(), PersistentCookieJar(storage), userAgent = "Trove/test (Quest 3; Android 14)")

  private fun json(code: Int, body: String, vararg headers: Pair<String, String>) =
      MockResponse().setResponseCode(code).setBody(body).apply {
        setHeader("Content-Type", "application/json; charset=utf-8")
        for ((k, v) in headers) addHeader(k, v)
      }

  private fun start(code: String = "ABC234", id: String = "pid-$code") =
      json(
          200,
          """{"code":"$code","pairId":"$id","secret":"sec-$code","expiresIn":300,"interval":2,
            "url":"https://vr.example/pair?c=$code"}""")

  private fun status(s: String) = json(200, """{"status":"$s"}""")

  private val approved =
      json(
          200,
          """{"status":"approved","user":{"id":3,"name":"alice","isAdmin":false}}""",
          "Set-Cookie" to "trove_session=paired123; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000",
      )

  @Test
  fun startPendingApprovedStoresTheSessionCookie() = runTest {
    server.enqueue(start())
    server.enqueue(status("pending"))
    server.enqueue(status("pending"))
    server.enqueue(approved)
    val states = mutableListOf<PairingState>()
    val a = api()
    assertFalse(a.hasSession())

    val result = DevicePairing(a).run { states += it }

    assertEquals(PairingState.Approved(app.trove.quest.api.User(3, "alice")), result)
    assertTrue(a.hasSession())
    assertEquals(PairingState.Starting, states[0])
    val waiting = states[1] as PairingState.Waiting
    assertEquals("ABC234", waiting.start.code)
    assertEquals("https://vr.example/pair?c=ABC234", waiting.start.url)
    assertEquals(result, states.last())

    val startReq = server.takeRequest()
    assertEquals("POST", startReq.method)
    assertEquals("/api/pair/start", startReq.path)
    assertEquals("{}", startReq.body.readUtf8())
    assertEquals("Trove/test (Quest 3; Android 14)", startReq.getHeader("User-Agent"))
    repeat(3) {
      val poll = server.takeRequest()
      assertEquals("/api/pair/poll", poll.path)
      assertEquals("""{"pairId":"pid-ABC234","secret":"sec-ABC234"}""", poll.body.readUtf8())
      assertTrue(poll.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    // The rest of the app now sends the paired cookie.
    server.enqueue(json(200, """{"user":{"id":3,"name":"alice","isAdmin":false}}"""))
    a.me()
    assertEquals("trove_session=paired123", server.takeRequest().getHeader("Cookie"))
  }

  @Test
  fun deniedStops() = runTest {
    server.enqueue(start())
    server.enqueue(status("denied"))
    val result = DevicePairing(api()).run {}
    assertEquals(PairingState.Denied, result)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun expiredCodeIsReplacedWithANewOne() = runTest {
    server.enqueue(start("AAA222"))
    server.enqueue(status("expired"))
    server.enqueue(start("BBB333"))
    server.enqueue(approved)
    val codes = mutableListOf<String>()
    val result = DevicePairing(api()).run { if (it is PairingState.Waiting) codes += it.start.code }
    assertTrue(result is PairingState.Approved)
    assertEquals(listOf("AAA222", "BBB333"), codes)
  }

  @Test
  fun wrongSecretAnswerGetsANewCode() = runTest {
    server.enqueue(start("AAA222"))
    server.enqueue(json(403, """{"error":"Wrong pairing secret"}"""))
    server.enqueue(start("BBB333"))
    server.enqueue(status("denied"))
    assertEquals(PairingState.Denied, DevicePairing(api()).run {})
    assertEquals(4, server.requestCount)
  }

  @Test
  fun stopsAfterMaxCodesWithoutAnAnswer() = runTest {
    repeat(2) {
      server.enqueue(start("C${it}C222"))
      server.enqueue(status("expired"))
    }
    assertEquals(PairingState.Stopped, DevicePairing(api(), maxCodes = 2).run {})
    assertEquals(4, server.requestCount)
  }

  @Test
  fun serverTroubleIsReportedAndRetried() = runTest {
    server.enqueue(json(500, """{"error":"Server error"}"""))
    server.enqueue(start())
    server.enqueue(json(502, """{"error":"Bad gateway"}"""))
    server.enqueue(approved)
    val states = mutableListOf<PairingState>()
    val result = DevicePairing(api(), retryDelayMs = 1_000).run { states += it }
    assertTrue(result is PairingState.Approved)
    assertEquals(PairingState.Trouble("Server error"), states[1])
    assertTrue(states.contains(PairingState.Trouble("Bad gateway")))
    // After a successful poll the code is shown again.
    assertTrue(states.count { it is PairingState.Waiting } >= 1)
  }

  @Test
  fun qrCodeRoundTrips() {
    val url = "https://trove.example.com/pair?c=ABC234"
    val m = qrMatrix(url)
    assertTrue(m.width >= 21 && m.width == m.height)
    // Decode it again with ZXing's reader: render with a 4-module quiet zone, 4 px per module.
    val scale = 4
    val size = (m.width + 8) * scale
    val source =
        object : LuminanceSource(size, size) {
          override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = row ?: ByteArray(size)
            for (x in 0 until size) out[x] = lum(x, y)
            return out
          }

          override fun getMatrix(): ByteArray = ByteArray(size * size) { lum(it % size, it / size) }

          private fun lum(x: Int, y: Int): Byte {
            val mx = x / scale - 4
            val my = y / scale - 4
            val dark = mx in 0 until m.width && my in 0 until m.height && m.get(mx, my)
            return if (dark) 0 else -1
          }
        }
    val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    assertEquals(url, text)
  }
}
