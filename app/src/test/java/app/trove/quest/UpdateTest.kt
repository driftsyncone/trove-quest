package app.trove.quest

import app.trove.quest.api.ApiException
import app.trove.quest.api.MemoryCookieStorage
import app.trove.quest.api.PersistentCookieJar
import app.trove.quest.api.TroveApi
import app.trove.quest.update.LatestResult
import app.trove.quest.update.Sha256
import app.trove.quest.update.UpdateClient
import app.trove.quest.update.UpdateException
import app.trove.quest.update.UpdateInfo
import app.trove.quest.update.UpdateRules
import app.trove.quest.update.UpdateThrottle
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class UpdateRulesTest {
  private fun info(code: Long) = UpdateInfo(versionCode = code, sha256 = "a".repeat(64), url = "/app/quest/x.apk")

  @Test
  fun onlyAHigherVersionCodeIsAnUpdate() {
    assertTrue(UpdateRules.isNewer(info(2), 1))
    assertTrue(UpdateRules.isNewer(info(100), 99))
    assertFalse(UpdateRules.isNewer(info(1), 1)) // same build
    assertFalse(UpdateRules.isNewer(info(1), 5)) // never offer a downgrade
  }

  @Test
  fun parsesTheServersLatestJson() {
    val json =
        """{"versionCode":3,"versionName":"0.2.0","sizeBytes":98765432,
           "sha256":"${"AB".repeat(32)}","notes":"- New: self-update\n- Fixed: x",
           "url":"/app/quest/trove-0.2.0-3.apk","publishedAt":"2026-10-21T12:00:00Z","extra":1}"""
    val i = UpdateRules.parse(json)
    assertEquals(3L, i.versionCode)
    assertEquals("0.2.0", i.label)
    assertEquals(98765432L, i.sizeBytes)
    assertEquals("ab".repeat(32), i.sha256) // normalized to lowercase
    assertEquals("- New: self-update\n- Fixed: x", i.notes)
    assertEquals("/app/quest/trove-0.2.0-3.apk", i.url)
  }

  @Test
  fun notesAreUnwrappedForTheCard() {
    val notes = "\n- **Updates**: one\n  two `x`\n- Next item\n  - nested\n"
    assertEquals("- Updates: one two x\n- Next item\n  - nested", UpdateRules.displayNotes(notes))
  }

  @Test
  fun labelFallsBackToTheCode() {
    assertEquals("7", UpdateRules.parse("""{"versionCode":7,"sha256":"${"0".repeat(64)}","url":"/a.apk"}""").label)
  }

  @Test
  fun rejectsUnusableLatestJson() {
    val bad =
        listOf(
            "not json",
            """{"versionName":"0.2.0","sha256":"${"0".repeat(64)}","url":"/a.apk"}""", // no code
            """{"versionCode":0,"sha256":"${"0".repeat(64)}","url":"/a.apk"}""",
            """{"versionCode":2,"sha256":"abc","url":"/a.apk"}""",
            """{"versionCode":2,"sha256":"${"g".repeat(64)}","url":"/a.apk"}""",
            """{"versionCode":2,"sha256":"${"0".repeat(64)}","url":" "}""",
            """{"versionCode":2,"sha256":"${"0".repeat(64)}"}""",
        )
    for (json in bad) {
      try {
        UpdateRules.parse(json)
        fail("accepted $json")
      } catch (_: UpdateException) {}
    }
  }
}

class UpdateThrottleTest {
  private val h = 60 * 60 * 1000L

  @Test
  fun checksAtMostEverySixHours() {
    val t0 = 1_800_000_000_000L
    assertTrue(UpdateThrottle.isDue(t0, 0)) // never checked
    assertFalse(UpdateThrottle.isDue(t0 + 1, t0))
    assertFalse(UpdateThrottle.isDue(t0 + 6 * h - 1, t0))
    assertTrue(UpdateThrottle.isDue(t0 + 6 * h, t0))
    assertTrue(UpdateThrottle.isDue(t0 + 30 * h, t0))
  }

  @Test
  fun aClockThatWentBackDoesNotBlockChecks() {
    val t0 = 1_800_000_000_000L
    assertTrue(UpdateThrottle.isDue(t0 - h, t0))
  }
}

class Sha256Test {
  @Test
  fun knownVectors() {
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArrayInputStream(ByteArray(0))))
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        Sha256.hex(ByteArrayInputStream("abc".toByteArray())))
  }

  @Test
  fun matchesIgnoresCaseAndRejectsOtherContent() {
    val f = File.createTempFile("sha", ".bin").apply { deleteOnExit() }
    f.writeText("abc")
    assertTrue(Sha256.matches(f, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
    f.writeText("abd")
    assertFalse(Sha256.matches(f, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    assertFalse(Sha256.matches(File(f.path + ".missing"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
  }
}

class UpdateClientTest {
  private lateinit var server: MockWebServer
  private lateinit var dir: File
  private val apk = Random(42).nextBytes(300_000)
  private val sha = Sha256.hex(ByteArrayInputStream(apk))

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    dir = Files.createTempDirectory("updates").toFile()
  }

  @After
  fun tearDown() {
    server.shutdown()
    dir.deleteRecursively()
  }

  /** Logged in through the mock server so the cookie jar holds trove_session. */
  private fun loggedInClient(): UpdateClient {
    val api = TroveApi(server.url("/").toString(), PersistentCookieJar(MemoryCookieStorage()))
    server.enqueue(
        json(200, """{"user":{"id":1,"name":"j","isAdmin":false}}""")
            .addHeader("Set-Cookie", "trove_session=tok123; Path=/; HttpOnly"))
    runBlocking { api.login("j", "pw") }
    server.takeRequest()
    return UpdateClient(api)
  }

  private fun json(code: Int, body: String) =
      MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json; charset=utf-8")

  private fun latest(code: Long) =
      """{"versionCode":$code,"versionName":"0.$code.0","sizeBytes":${apk.size},"sha256":"$sha",
          "notes":"- n","url":"/app/quest/trove-$code.apk"}"""

  private fun info(sha256: String = sha) =
      UpdateInfo(versionCode = 2, versionName = "0.2.0", sizeBytes = apk.size.toLong(), sha256 = sha256,
          url = "/app/quest/trove-2.apk")

  @Test
  fun latestSendsTheSessionCookieAndComparesVersionCodes() = runBlocking {
    val client = loggedInClient()
    server.enqueue(json(200, latest(2)))
    val r = client.latest(installedVersionCode = 1)
    assertTrue(r is LatestResult.Available)
    assertEquals(2L, (r as LatestResult.Available).info.versionCode)
    val req = server.takeRequest()
    assertEquals("/app/quest/latest.json", req.path)
    assertEquals("trove_session=tok123", req.getHeader("Cookie"))

    server.enqueue(json(200, latest(2)))
    assertTrue(client.latest(installedVersionCode = 2) is LatestResult.UpToDate)
  }

  @Test
  fun noReleaseUnsupportedServerAndLoggedOut() = runBlocking {
    val client = loggedInClient()
    server.enqueue(json(404, """{"error":"No release published"}"""))
    assertEquals(LatestResult.NoRelease, client.latest(1))
    // Older server: the SPA fallback answers with the web app's HTML.
    server.enqueue(MockResponse().setBody("<!doctype html>").setHeader("Content-Type", "text/html; charset=utf-8"))
    assertEquals(LatestResult.Unsupported, client.latest(1))
    server.enqueue(json(401, """{"error":"Not logged in"}"""))
    try {
      client.latest(1)
      fail("expected 401")
    } catch (e: ApiException) {
      assertEquals(401, e.status)
    }
  }

  @Test
  fun downloadVerifiesTheChecksum() = runBlocking {
    val client = loggedInClient()
    server.enqueue(MockResponse().setBody(Buffer().write(apk)))
    var last = 0L
    val file = client.download(info(), dir) { done, _ -> last = done }
    assertArrayEquals(apk, file.readBytes())
    assertEquals(apk.size.toLong(), last)
    assertEquals("trove_session=tok123", server.takeRequest().getHeader("Cookie"))

    // Already downloaded and verified: no second request.
    val again = client.download(info(), dir)
    assertEquals(file, again)
    assertEquals(2, server.requestCount) // login + one download
  }

  @Test
  fun aDamagedDownloadIsRejectedAndDeleted() = runBlocking {
    val client = loggedInClient()
    val damaged = apk.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
    server.enqueue(MockResponse().setBody(Buffer().write(damaged)))
    try {
      client.download(info(), dir)
      fail("expected checksum error")
    } catch (e: UpdateException) {
      assertTrue(e.message!!.contains("checksum"))
    }
    assertTrue(dir.listFiles()!!.isEmpty())
  }

  @Test
  fun anIncompleteDownloadResumesWithRange() = runBlocking {
    val client = loggedInClient()
    File(dir, "trove-2.apk.part").writeBytes(apk.copyOfRange(0, 100_000))
    File(dir, "trove-1.apk").writeBytes(byteArrayOf(1)) // an old download, cleaned up
    server.enqueue(
        MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes 100000-${apk.size - 1}/${apk.size}")
            .setBody(Buffer().write(apk.copyOfRange(100_000, apk.size))))
    val file = client.download(info(), dir)
    assertArrayEquals(apk, file.readBytes())
    assertEquals("bytes=100000-", server.takeRequest().getHeader("Range"))
    assertEquals(listOf("trove-2.apk"), dir.list()!!.toList())
  }

  @Test
  fun downloadErrorsCarryTheStatus() = runBlocking {
    val client = loggedInClient()
    server.enqueue(json(404, """{"error":"Not found"}"""))
    try {
      client.download(info(), dir)
      fail("expected 404")
    } catch (e: ApiException) {
      assertEquals(404, e.status)
    }
  }
}
