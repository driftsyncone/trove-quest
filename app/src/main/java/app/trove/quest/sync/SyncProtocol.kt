package app.trove.quest.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One open screen as the sync channel sees it (Trove server/sync.ts `ScreenState`).
 *
 * [playing] means the picture advances right now (not paused, buffering, seeking or ended): the
 * server runs the toy from [positionMs] + the time since the report, so a stalled video must
 * report false or the device runs ahead of the picture. [projection] is extra: today's server
 * drops it, a later console may show it.
 */
data class SyncScreen(
    val id: String,
    val sceneId: String,
    val title: String?,
    val positionMs: Long,
    val durationMs: Long?,
    val playing: Boolean,
    val rate: Float,
    val focused: Boolean,
    val projection: String? = null,
    /**
     * Where the screen is, seen from the head (0.3.1, for the console's map; null = not reported):
     * bearing [yawDeg] (0 = +z, + right) and [pitchDeg] (+ up), [distM], [widthM] and tilt [rollDeg].
     */
    val yawDeg: Float? = null,
    val pitchDeg: Float? = null,
    val distM: Float? = null,
    val widthM: Float? = null,
    val rollDeg: Float? = null,
)

/** Status `resume`: "no" = only a console can lift the latch, "now" = one press, "confirm" = a held press (`confirm: true`). */
enum class ResumeMode {
  NO,
  NOW,
  CONFIRM;

  companion object {
    fun parse(s: String?): ResumeMode =
        when (s) {
          "now" -> NOW
          "confirm" -> CONFIRM
          else -> NO
        }
  }
}

/** Server -> player messages (README of the Trove server, "API (for the native app)"). */
sealed interface ServerMessage {
  /** [serverNow]: the server's wall clock (epoch ms) when sent; null from an older server. */
  data class Hello(val id: String, val deadmanMs: Long, val heartbeatMs: Long, val serverNow: Long? = null) : ServerMessage

  /**
   * `pause-all` (STOP ALL or server shutdown, [reason] says which), `play` / `pause` / `seek`
   * (partner remote control; [screen] null = the focused screen, [deltaMs] for seek), `blackout`
   * ([on], numbered by [seq]; [reason] on a lift says why: console, stop-all, console-lost, ...).
   */
  data class Command(
      val cmd: String,
      val screen: String?,
      val deltaMs: Long,
      val reason: String?,
      val on: Boolean = false,
      val seq: Long = 0L,
  ) : ServerMessage

  /** A Director command (open / swap / close / focus / close-all / random / storm), numbered by [seq]. */
  data class Director(val command: DirectorCommand) : ServerMessage

  /** A Director command the app cannot read (missing field): answered with an error ack. */
  data class DirectorInvalid(val seq: Long, val cmd: String) : ServerMessage

  /**
   * The server's latch / device state; [stopped] covers STOP ALL, dead-man and "not started yet".
   * [armed]: the server drives a real toy (false = its fake driver; null = an older server that doesn't say).
   */
  data class Status(
      val stopped: Boolean,
      val reason: String?,
      val device: String?,
      val armed: Boolean? = null,
      /** What a `resume` from this headset would do right now (server `resumeMode()`); [ResumeMode.NO] from an older server. */
      val resume: ResumeMode = ResumeMode.NO,
      /** The VacuGlide: connection, the toy's state and the control settings (status `vg`); null from an older server. */
      val vg: VgState? = null,
      /** The current scene's script variants (status `script`); null = none, or an older server. */
      val script: ScriptState? = null,
      /** The session timer (status `timer`); null = none running, or an older server. */
      val timer: TimerState? = null,
  ) : ServerMessage

  /** The server's answer to a `timer` message: [op] is `set`, `extend` or `cancel`; [error] says why a refused one was. */
  data class TimerAck(val op: String, val ok: Boolean, val error: String?) : ServerMessage

  /** `{"type":"timer","event":"warn","minutesLeft":5|1}`: show a small notice (not during Black Out). */
  data class TimerWarn(val minutesLeft: Int) : ServerMessage

  /** The server's answer to a `vg` message: [op] is `valve`, `set`, `reload` or `script`; [error] says why a refused one was. */
  data class VgAck(val op: String, val ok: Boolean, val error: String?) : ServerMessage
}

/** Encoding / decoding of the sync socket's JSON text frames. Pure, so it runs in JVM tests. */
object SyncProtocol {
  /** The server keeps 8 screens and 20 headset values; more are cut there anyway. */
  const val MAX_SCREENS = 8
  const val MAX_HEADSET_KEYS = 20

  /** `reason` of the pause-all the console's STOP ALL sends (server/sync.ts stopAll). */
  const val REASON_STOP_ALL = "STOP ALL"

  /** `reason` of a blackout lift that STOP ALL caused (server/sync.ts setBlackout). */
  const val LIFT_STOP_ALL = "stop-all"

  /** Status `reason` while the wearer's own escape has the server latched (server/sync.ts WEARER_REASON). */
  const val REASON_WEARER = "Stopped by the wearer"

  /** The commands of the Director family; they carry a `seq` that the ack repeats. */
  val DIRECTOR_CMDS = setOf("open", "swap", "close", "close-all", "focus", "random", "storm")

  /** `liftedBy` of the wearer's own escape. */
  const val LIFTED_BY_WEARER = "wearer"

  private val parser = Json { ignoreUnknownKeys = true }

  /** `{"type":"state","screens":[...],"headset":{...}}`: the heartbeat that carries the clock. */
  fun encodeState(screens: List<SyncScreen>, headset: Map<String, Any>, blackout: Boolean? = null, view: HeadView? = null): String =
      buildJsonObject {
            put("type", JsonPrimitive("state"))
            put(
                "screens",
                buildJsonArray {
                  for (s in screens.take(MAX_SCREENS)) {
                    add(
                        buildJsonObject {
                          put("id", JsonPrimitive(s.id))
                          put("sceneId", JsonPrimitive(s.sceneId))
                          s.title?.let { put("title", JsonPrimitive(it)) }
                          put("positionMs", JsonPrimitive(s.positionMs.coerceAtLeast(0)))
                          put("durationMs", s.durationMs?.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                          put("playing", JsonPrimitive(s.playing))
                          put("rate", JsonPrimitive(s.rate))
                          put("focused", JsonPrimitive(s.focused))
                          s.projection?.let { put("projection", JsonPrimitive(it)) }
                          s.yawDeg?.takeIf { it.isFinite() }?.let { put("yawDeg", JsonPrimitive(round1(it))) }
                          s.pitchDeg?.takeIf { it.isFinite() }?.let { put("pitchDeg", JsonPrimitive(round1(it))) }
                          s.distM?.takeIf { it.isFinite() }?.let { put("distM", JsonPrimitive(round2(it))) }
                          s.widthM?.takeIf { it.isFinite() }?.let { put("widthM", JsonPrimitive(round2(it))) }
                          s.rollDeg?.takeIf { it.isFinite() }?.let { put("rollDeg", JsonPrimitive(round1(it))) }
                        })
                  }
                })
            blackout?.let { put("blackout", JsonPrimitive(it)) }
            view?.takeIf { it.yawDeg.isFinite() && it.pitchDeg.isFinite() }?.let { v ->
              put(
                  "view",
                  buildJsonObject {
                    put("yawDeg", JsonPrimitive(round1(v.yawDeg)))
                    put("pitchDeg", JsonPrimitive(round1(v.pitchDeg)))
                    put("facing", v.facing?.let(::JsonPrimitive) ?: JsonNull)
                    put("posture", JsonPrimitive(v.posture))
                  })
            }
            put(
                "headset",
                buildJsonObject {
                  for ((k, v) in headset.entries.take(MAX_HEADSET_KEYS)) {
                    when (v) {
                      is Boolean -> put(k, JsonPrimitive(v))
                      is Number -> put(k, JsonPrimitive(v))
                      else -> put(k, JsonPrimitive(v.toString()))
                    }
                  }
                })
          }
          .toString()

  private fun round1(v: Float): Double = Math.round(v * 10.0) / 10.0

  private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0

  /**
   * Black Out report: the reply to a blackout command (same [seq]) or, with [liftedBy] "wearer", the
   * wearer's own escape. Sent at once, not with the next heartbeat.
   */
  fun encodeBlackout(on: Boolean, seq: Long? = null, liftedBy: String? = null): String =
      buildJsonObject {
            put("type", JsonPrimitive("blackout"))
            put("on", JsonPrimitive(on))
            seq?.let { put("seq", JsonPrimitive(it)) }
            liftedBy?.let { put("liftedBy", JsonPrimitive(it)) }
          }
          .toString()

  /** Answer to a Director command: done ([ok]) or failed with [error]. Sent as soon as it is known. */
  fun encodeAck(seq: Long, ok: Boolean, error: String? = null): String =
      buildJsonObject {
            put("type", JsonPrimitive("ack"))
            put("seq", JsonPrimitive(seq))
            put("ok", JsonPrimitive(ok))
            if (!ok) put("error", JsonPrimitive((error ?: "failed").take(200)))
          }
          .toString()

  /** The wearer's escape (both grips, 2 s): the server latches STOPPED with the wearer as the cause. */
  fun encodeWearerStop(): String = buildJsonObject { put("type", JsonPrimitive("wearer-stop")) }.toString()

  /**
   * The wearer's Start / Resume from the headset: `{"type":"resume"}`, with `"confirm":true` after a held
   * press. The server decides (status `resume`); a refused one changes nothing.
   */
  fun encodeResume(confirm: Boolean = false): String =
      buildJsonObject {
            put("type", JsonPrimitive("resume"))
            if (confirm) put("confirm", JsonPrimitive(true))
          }
          .toString()

  /**
   * VacuGlide valve, hold-to-use: `on: true` when the button goes down, `on: false` when it comes up
   * (or anything else ends the press, see [ValveHold]). The server also closes it after 5 s and when this socket closes.
   */
  fun encodeVgValve(key: ValveKey, on: Boolean): String =
      buildJsonObject {
            put("type", JsonPrimitive("vg"))
            put("op", JsonPrimitive("valve"))
            put("name", JsonPrimitive(key.wire))
            put("on", JsonPrimitive(on))
          }
          .toString()

  /** VacuGlide settings: any subset of the four, absolute values (the server checks the ranges). */
  fun encodeVgSet(offsetMs: Int? = null, intensity: Int? = null, speedLimit: Int? = null, suction: Int? = null): String =
      buildJsonObject {
            put("type", JsonPrimitive("vg"))
            put("op", JsonPrimitive("set"))
            offsetMs?.let { put("offsetMs", JsonPrimitive(it)) }
            intensity?.let { put("intensity", JsonPrimitive(it)) }
            speedLimit?.let { put("speedLimit", JsonPrimitive(it)) }
            suction?.let { put("suction", JsonPrimitive(it)) }
          }
          .toString()

  /** Forget the uploaded script and send it again (the plugin's "Reload script"). */
  fun encodeVgReload(): String = buildJsonObject { put("type", JsonPrimitive("vg")); put("op", JsonPrimitive("reload")) }.toString()

  /** Choose which of a scene's scripts drives the toy (an id from status `script.options` or the scene's script list). */
  fun encodeVgScript(sceneId: String, id: String): String =
      buildJsonObject {
            put("type", JsonPrimitive("vg"))
            put("op", JsonPrimitive("script"))
            put("sceneId", JsonPrimitive(sceneId))
            put("id", JsonPrimitive(id))
          }
          .toString()

  /** Session timer: `{"type":"timer","op":"set","minutes":N}`, `{"op":"extend"}` (+10 min) or `{"op":"cancel"}`. */
  fun encodeTimer(op: TimerOp): String =
      buildJsonObject {
            put("type", JsonPrimitive("timer"))
            when (op) {
              is TimerOp.Set -> {
                put("op", JsonPrimitive("set"))
                put("minutes", JsonPrimitive(op.minutes))
              }
              TimerOp.Extend -> put("op", JsonPrimitive("extend"))
              TimerOp.Cancel -> put("op", JsonPrimitive("cancel"))
            }
          }
          .toString()

  /** Unknown types, broken JSON and messages without a type are null (ignored, like the web client). */
  fun decode(text: String): ServerMessage? {
    val o = runCatching { parser.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    return when (o.str("type")) {
      "hello" -> ServerMessage.Hello(o.str("id").orEmpty(), o.long("deadmanMs") ?: 3000L, o.long("heartbeatMs") ?: 1000L, o.long("serverNow"))
      "command" -> {
        val cmd = o.str("cmd") ?: return null
        if (cmd in DIRECTOR_CMDS) return director(cmd, o)
        ServerMessage.Command(
            cmd, o.str("screen"), o.long("deltaMs") ?: 0L, o.str("reason"), o.bool("on") ?: false, o.long("seq") ?: 0L)
      }
      "status" ->
          ServerMessage.Status(
              o.bool("stopped") ?: false, o.str("reason"), o.str("device"), o.bool("armed"), ResumeMode.parse(o.str("resume")),
              (o["vg"] as? JsonObject)?.let(::vgState), (o["script"] as? JsonObject)?.let(::scriptState),
              (o["timer"] as? JsonObject)?.let(::timerState))
      "timer-ack" -> ServerMessage.TimerAck(o.str("op").orEmpty(), o.bool("ok") ?: false, o.str("error"))
      "timer" -> if (o.str("event") == "warn") o.long("minutesLeft")?.let { ServerMessage.TimerWarn(it.toInt()) } else null
      "vg-ack" -> ServerMessage.VgAck(o.str("op").orEmpty(), o.bool("ok") ?: false, o.str("error"))
      else -> null
    }
  }

  /** Status `timer`: null without a usable `endsAt`. */
  private fun timerState(o: JsonObject): TimerState? = o.long("endsAt")?.let { TimerState(it, o.str("setBy") ?: "partner") }

  private fun vgState(o: JsonObject) =
      VgState(
          connected = o.bool("connected") ?: false,
          mode = o.str("mode"),
          targetSpeed = o.long("targetSpeed")?.toInt(),
          firmware = o.str("firmware"),
          battery = o.long("battery")?.toInt(),
          latencyMs = o.long("latencyMs")?.toInt(),
          valve = o.str("valve"),
          error = o.str("error"),
          offsetMs = o.long("offsetMs")?.toInt() ?: 0,
          intensity = o.long("intensity")?.toInt() ?: VacuGlideRules.INTENSITY_DEFAULT,
          speedLimit = o.long("speedLimit")?.toInt() ?: 0,
          suction = o.long("suction")?.toInt() ?: 0,
      )

  /** Status `script`; options without an id are dropped, a script without a scene id or options is null. */
  private fun scriptState(o: JsonObject): ScriptState? {
    val sceneId = o.str("sceneId") ?: return null
    val options =
        (o["options"] as? JsonArray)?.mapNotNull { e ->
          val x = e as? JsonObject ?: return@mapNotNull null
          val id = x.str("id") ?: return@mapNotNull null
          ScriptOption(id, x.str("label") ?: id, x.bool("default") ?: false)
        } ?: emptyList()
    if (options.isEmpty()) return null
    return ScriptState(sceneId, options, o.str("chosen"), o.bool("loading") ?: false, o.str("error"))
  }

  private fun director(cmd: String, o: JsonObject): ServerMessage {
    val seq = o.long("seq") ?: 0L
    val invalid = ServerMessage.DirectorInvalid(seq, cmd)
    if (seq <= 0) return invalid
    val screen = o.str("screen")
    val sceneId = o.str("sceneId")
    val newScreen = (o.str("mode") ?: "new") == "new"
    val action: DirectorAction =
        when (cmd) {
          "open" -> DirectorAction.Open(sceneId ?: return invalid, newScreen, screen, (o.long("startMs") ?: 0L).coerceAtLeast(0))
          "swap" -> DirectorAction.Swap(screen ?: return invalid, sceneId ?: return invalid)
          "close" -> DirectorAction.Close(screen ?: return invalid)
          "focus" -> DirectorAction.Focus(screen ?: return invalid)
          "close-all" -> DirectorAction.CloseAll
          "random" -> DirectorAction.Random(newScreen, screen)
          else -> {
            val on = o.bool("on") ?: return invalid
            DirectorAction.Storm(
                on,
                if (on) StormQuery(o.str("q").orEmpty(), o.strings("tags"), o.strings("performers"), o.strings("studios"), o.str("filter")) else null)
          }
        }
    return ServerMessage.Director(DirectorCommand(seq, action))
  }

  private fun JsonObject.strings(k: String): List<String> =
      (this[k] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: emptyList()

  private fun JsonObject.prim(k: String) = (this[k] as? JsonElement)?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive }.getOrNull() }

  private fun JsonObject.str(k: String) = prim(k)?.takeIf { it.isString }?.contentOrNull

  private fun JsonObject.long(k: String) = prim(k)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

  private fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull
}
