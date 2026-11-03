package app.trove.quest

import app.trove.quest.sync.DirectorAction
import app.trove.quest.sync.DirectorCommand
import app.trove.quest.sync.HeadView
import app.trove.quest.sync.ResumeMode
import app.trove.quest.sync.ScriptOption
import app.trove.quest.sync.ScriptState
import app.trove.quest.sync.ServerMessage
import app.trove.quest.sync.SessionTimer
import app.trove.quest.sync.TimerOp
import app.trove.quest.sync.TimerState
import app.trove.quest.sync.StormQuery
import app.trove.quest.sync.SyncProtocol
import app.trove.quest.sync.SyncScreen
import app.trove.quest.sync.ValveKey
import app.trove.quest.sync.VgState
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync wire contract against the fixtures shared with the Trove server
 * (`app/src/test/resources/sync/`, identical to the server's `test/fixtures/sync/`; see its README).
 * The server test proves it emits every `s2p-*` file exactly and accepts every `p2s-*` file; this one
 * proves the app decodes every `s2p-*` to the expected value and encodes every `p2s-*` for an
 * equivalent state. Numbers compare numerically (Kotlin writes 1.0, JS writes 1).
 */
class SyncContractTest {
  private val dir: File = File(javaClass.getResource("/sync/SHA256SUMS")!!.toURI()).parentFile

  private fun text(name: String) = File(dir, "$name.json").readText()

  private fun fixtures(prefix: String) = dir.listFiles()!!.map { it.name }.filter { it.startsWith(prefix) && it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()

  /** Deep-normalise: numbers to doubles, so 1 == 1.0. */
  private fun norm(e: JsonElement): Any? =
      when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { norm(it.value) }
        is JsonArray -> e.map { norm(it) }
        is JsonPrimitive -> if (e.isString) e.content else e.content.toBooleanStrictOrNull() ?: e.content.toDouble()
      }

  private fun assertSameJson(name: String, actual: String) =
      assertEquals(name, norm(Json.parseToJsonElement(text(name))), norm(Json.parseToJsonElement(actual)))

  @Test
  fun checksumsMatchTheFilesHere() {
    val files = dir.listFiles()!!.map { it.name }.filter { it.endsWith(".json") }.sorted()
    val sums = File(dir, "SHA256SUMS").readLines().filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")) }
    assertEquals("SHA256SUMS lists the same files", files, sums.map { it[1] })
    for ((sum, name) in sums) {
      val hex = MessageDigest.getInstance("SHA-256").digest(File(dir, name).readBytes()).joinToString("") { "%02x".format(it) }
      assertEquals("$name changed: re-copy from the server repo (sync-fixtures.sh push)", sum, hex)
    }
  }

  // ---- server -> player: decode ----

  private fun cmd(
      c: String, screen: String? = null, deltaMs: Long = 0, reason: String? = null, on: Boolean = false, seq: Long = 0,
  ) = ServerMessage.Command(c, screen, deltaMs, reason, on, seq)

  private fun dir(seq: Long, a: DirectorAction) = ServerMessage.Director(DirectorCommand(seq, a))

  private fun scripts(chosen: String, loading: Boolean = false) =
      ScriptState("1", listOf(ScriptOption("Default", "Default", true), ScriptOption("FULL", "FULL"), ScriptOption("HALF", "HALF")), chosen, loading, null)

  private fun notStarted(script: ScriptState) =
      ServerMessage.Status(true, "Not started yet: press Resume to allow the device", "stopped", false, ResumeMode.NO, VgState(), script)

  private val s2p: Map<String, ServerMessage> =
      mapOf(
          "s2p-hello" to ServerMessage.Hello("AbCdEfGh", 3000, 1000, 1_790_000_000_000L),
          "s2p-cmd-pause-all-stop-all" to cmd("pause-all", reason = SyncProtocol.REASON_STOP_ALL),
          "s2p-cmd-play" to cmd("play", screen = "screen-1"),
          "s2p-cmd-pause" to cmd("pause"),
          "s2p-cmd-seek" to cmd("seek", screen = "screen-2", deltaMs = -10000),
          "s2p-cmd-blackout-on" to cmd("blackout", on = true, seq = 1),
          "s2p-cmd-blackout-off-console" to cmd("blackout", reason = "console", seq = 2),
          "s2p-cmd-blackout-off-stop-all" to cmd("blackout", reason = SyncProtocol.LIFT_STOP_ALL, seq = 4),
          "s2p-cmd-blackout-off-wearer" to cmd("blackout", reason = SyncProtocol.LIFTED_BY_WEARER, seq = 6),
          "s2p-status-not-started" to ServerMessage.Status(true, "Not started yet: press Resume to allow the device", "stopped", false, ResumeMode.NO, VgState()),
          "s2p-status-resumed" to ServerMessage.Status(false, null, "idle", false, ResumeMode.NO, VgState()),
          "s2p-status-stop-all" to ServerMessage.Status(true, "STOP ALL by synctest", "stopped", false, ResumeMode.NO, VgState()),
          "s2p-status-stop-all-solo" to ServerMessage.Status(true, "STOP ALL by synctest", "stopped", false, ResumeMode.CONFIRM, VgState()),
          "s2p-status-wearer" to ServerMessage.Status(true, SyncProtocol.REASON_WEARER, "stopped", false, ResumeMode.NOW, VgState()),
          "s2p-status-vg-held" to
              ServerMessage.Status(true, "Not started yet: press Resume to allow the device", "stopped", false, ResumeMode.NO, VgState(valve = "tighter")),
          "s2p-status-vg-settings" to
              ServerMessage.Status(
                  true, "Not started yet: press Resume to allow the device", "stopped", false, ResumeMode.NO,
                  VgState(offsetMs = 150, intensity = 80, speedLimit = 300, suction = 2)),
          "s2p-status-vg-connected" to
              ServerMessage.Status(
                  false, null, "playing", true, ResumeMode.NO,
                  VgState(
                      connected = true, mode = "SYNC_SCRIPT_PLAYING", targetSpeed = 40, firmware = "7", battery = null, latencyMs = 85,
                      valve = null, error = null, offsetMs = -50, intensity = 120, speedLimit = 500, suction = 3)),
          "s2p-status-script" to notStarted(scripts("Default")),
          "s2p-status-script-loading" to notStarted(scripts("FULL", loading = true)),
          "s2p-status-script-chosen" to notStarted(scripts("FULL")),
          "s2p-vg-ack-ok" to ServerMessage.VgAck("valve", true, null),
          "s2p-vg-ack-script" to ServerMessage.VgAck("script", true, null),
          "s2p-vg-ack-script-error" to ServerMessage.VgAck("script", false, "This scene has no such script"),
          "s2p-vg-ack-error" to ServerMessage.VgAck("valve", false, "name: tighter|looser"),
          "s2p-dir-open-new" to dir(1, DirectorAction.Open("123", true, null, 0)),
          "s2p-dir-open-replace" to dir(2, DirectorAction.Open("456", false, "screen-2", 90000)),
          "s2p-dir-swap" to dir(3, DirectorAction.Swap("screen-1", "789")),
          "s2p-dir-focus" to dir(4, DirectorAction.Focus("screen-2")),
          "s2p-dir-close" to dir(5, DirectorAction.Close("screen-2")),
          "s2p-dir-random" to dir(6, DirectorAction.Random(false, "screen-1")),
          "s2p-dir-storm-on" to dir(7, DirectorAction.Storm(true, StormQuery("beach", listOf("11", "12"), listOf("7"), listOf("3"), "5"))),
          "s2p-dir-storm-off" to dir(8, DirectorAction.Storm(false, null)),
          "s2p-dir-close-all" to dir(9, DirectorAction.CloseAll),
          "s2p-status-timer" to ServerMessage.Status(false, null, "idle", false, ResumeMode.NO, VgState(), timer = TimerState(1790000000000, "wearer")),
          "s2p-status-timer-ended" to ServerMessage.Status(true, SessionTimer.ENDED_REASON, "stopped", false, ResumeMode.NOW, VgState()),
          "s2p-cmd-pause-all-timer" to cmd("pause-all", reason = SessionTimer.REASON),
          "s2p-timer-ack-ok" to ServerMessage.TimerAck("set", true, null),
          "s2p-timer-ack-error" to ServerMessage.TimerAck("extend", false, "No timer running"),
          "s2p-timer-warn-5" to ServerMessage.TimerWarn(5),
          "s2p-timer-warn-1" to ServerMessage.TimerWarn(1),
      )

  @Test
  fun everyServerFixtureHasAnExpectationAndNoneIsMissing() {
    assertEquals(fixtures("s2p-"), s2p.keys.sorted())
  }

  @Test
  fun everyServerFixtureDecodesToTheExpectedValue() {
    for ((name, want) in s2p) assertEquals(name, want, SyncProtocol.decode(text(name)))
  }

  // ---- player -> server: encode ----

  private val headsetBasic = linkedMapOf<String, Any>("inOurApp" to true, "battery" to 81, "charging" to false, "tempC" to 31.3, "app" to "0.2.3")
  private val headsetFull = linkedMapOf<String, Any>("inOurApp" to true, "battery" to 80, "storm" to "on, 9 tiles")

  private val p2s: Map<String, () -> String> =
      mapOf(
          "p2s-state-basic" to {
            SyncProtocol.encodeState(listOf(SyncScreen("screen-1", "123", "Title", 61234, 1_800_000, true, 1f, true)), headsetBasic)
          },
          "p2s-state-full" to {
            SyncProtocol.encodeState(
                listOf(
                    SyncScreen("screen-1", "123", "Title", 61234, 1_800_000, true, 1f, true, yawDeg = -12.5f, pitchDeg = 3f, distM = 2.5f, widthM = 1.6f, rollDeg = 0f),
                    SyncScreen("screen-2", "456", null, 0, null, false, 1f, false, yawDeg = 40f, pitchDeg = -5.5f, distM = 3f, widthM = 1.2f, rollDeg = -2.5f),
                ),
                headsetFull,
                blackout = false,
                view = HeadView(-10f, 2.5f, "screen-1", "sit"),
            )
          },
          "p2s-state-away" to {
            SyncProtocol.encodeState(
                listOf(SyncScreen("screen-1", "123", "Title", 61234, 1_800_000, true, 1f, true)),
                linkedMapOf<String, Any>("inOurApp" to false, "battery" to 81, "charging" to false, "tempC" to 31.3, "app" to "0.2.3"),
            )
          },
          "p2s-ack-ok" to { SyncProtocol.encodeAck(1, true) },
          "p2s-ack-failed" to { SyncProtocol.encodeAck(2, false, "No such screen") },
          "p2s-blackout-reply" to { SyncProtocol.encodeBlackout(true, seq = 1) },
          "p2s-blackout-wearer-lift" to { SyncProtocol.encodeBlackout(false, liftedBy = SyncProtocol.LIFTED_BY_WEARER) },
          "p2s-wearer-stop" to { SyncProtocol.encodeWearerStop() },
          "p2s-vg-valve-press" to { SyncProtocol.encodeVgValve(ValveKey.TIGHTER, true) },
          "p2s-vg-valve-release" to { SyncProtocol.encodeVgValve(ValveKey.TIGHTER, false) },
          "p2s-vg-set" to { SyncProtocol.encodeVgSet(offsetMs = 150, intensity = 80, speedLimit = 300, suction = 2) },
          "p2s-vg-reload" to { SyncProtocol.encodeVgReload() },
          "p2s-vg-script" to { SyncProtocol.encodeVgScript("1", "FULL") },
          "p2s-resume" to { SyncProtocol.encodeResume() },
          "p2s-resume-confirm" to { SyncProtocol.encodeResume(confirm = true) },
          "p2s-timer-set" to { SyncProtocol.encodeTimer(TimerOp.Set(30)) },
          "p2s-timer-extend" to { SyncProtocol.encodeTimer(TimerOp.Extend) },
          "p2s-timer-cancel" to { SyncProtocol.encodeTimer(TimerOp.Cancel) },
      )

  @Test
  fun everyPlayerFixtureHasAnEncoderAndNoneIsMissing() {
    assertEquals(fixtures("p2s-"), p2s.keys.sorted())
  }

  @Test
  fun everyPlayerFixtureIsWhatTheAppEncodesForTheEquivalentState() {
    for ((name, encode) in p2s) assertSameJson(name, encode())
  }

  @Test
  fun theFixtureFilesAreNotEmpty() {
    for (n in fixtures("")) assertNotNull(n, Json.parseToJsonElement(text(n)) as? JsonObject)
    assertTrue(fixtures("").size >= 40)
  }
}
