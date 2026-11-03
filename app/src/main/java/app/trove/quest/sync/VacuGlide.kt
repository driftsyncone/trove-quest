package app.trove.quest.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * The VacuGlide sub-panel's brain (README "VacuGlide panel"): the controls of the Stash plugin
 * "VacuGlide Sync", sent to the Trove server over the sync socket. Pure Kotlin + Compose state, so
 * the rules run in JVM tests. The panel itself is player/VacuGlidePanel.kt, the entities are the
 * screens' (PlayerSession), the wire is SyncProtocol.
 */

/**
 * The two valves, by what they do to the suction. The server decides which Autoblow valve each one opens
 * (server/vacuglide.ts VALVE_NAMES, swapped after 0.5.2 when the real device showed them reversed); the
 * app only ever sends "tighter" / "looser".
 */
enum class ValveKey(val wire: String, val label: String) {
  TIGHTER("tighter", "Tighter"),
  LOOSER("looser", "Looser"),
}

/**
 * The server's status `vg`: the toy's connection and state, and the settings it holds for this user.
 * [connected]: the Autoblow cloud says the VacuGlide is online (always false while the server's fake
 * driver is in use). [valve]: the valve held open by a person ("tighter" / "looser").
 */
data class VgState(
    val connected: Boolean = false,
    val mode: String? = null,
    val targetSpeed: Int? = null,
    val firmware: String? = null,
    val battery: Int? = null,
    val latencyMs: Int? = null,
    val valve: String? = null,
    val error: String? = null,
    val offsetMs: Int = 0,
    val intensity: Int = VacuGlideRules.INTENSITY_DEFAULT,
    val speedLimit: Int = 0,
    val suction: Int = 0,
)

/** Where the sub-panel is, as the wearer left it (remembered): the chip, the whole panel, or gone. */
enum class VgUi(val pref: String) {
  CHIP("chip"),
  FULL("full"),
  HIDDEN("hidden");

  companion object {
    fun parse(s: String?): VgUi = entries.firstOrNull { it.pref == s } ?: CHIP
  }
}

/** What is on screen right now for a screen's controls. */
enum class VgPanel {
  NONE,
  CHIP,
  FULL,
}

object VacuGlideRules {
  const val INTENSITY_DEFAULT = 100

  /** Plugin ranges and steps. */
  const val OFFSET_STEP = 50
  const val OFFSET_MIN = -5000
  const val OFFSET_MAX = 5000
  const val INTENSITY_STEP = 10
  const val INTENSITY_MIN = 20
  const val INTENSITY_MAX = 200

  /** Plugin SPEED_LIMITS (position units per second, 0 = off), in cycling order. */
  val SPEED_LIMITS = listOf(0, 500, 400, 300, 200)

  /** Plugin SUCTION_LEVELS names (index = the `suction` value). */
  val SUCTION_NAMES = listOf("Off", "Low", "Medium", "High")

  /** A press is ended after this long whatever happens (the server does the same). */
  const val MAX_HOLD_MS = 5000L

  /** A refusal or an error stays on the panel this long. */
  const val NOTICE_MS = 4000L

  /**
   * The panel exists only while the server drives a real toy ([SyncState.armed]), the link is up and
   * Autoblow reports the VacuGlide online. Anything else (unarmed, offline, reconnecting) and it is gone.
   */
  fun available(s: SyncState): Boolean = s.link == SyncLink.CONNECTED && s.armed == true && s.vg.connected

  /**
   * What a screen's controls carry: nothing while the toy is not available, the controls are down
   * (another screen is focused, "Hide controls") or Black Out is on; else the chip or the whole panel
   * as the wearer left it ([VgUi.HIDDEN] = nothing).
   */
  fun panel(available: Boolean, ui: VgUi, controlsVisible: Boolean, blackout: Boolean): VgPanel =
      when {
        !available || !controlsVisible || blackout -> VgPanel.NONE
        ui == VgUi.HIDDEN -> VgPanel.NONE
        ui == VgUi.FULL -> VgPanel.FULL
        else -> VgPanel.CHIP
      }

  /**
   * Suction is refused by the server while a person's STOP ALL (partner or wearer) or Black Out holds;
   * the buttons grey out then instead of sending something that is refused.
   */
  fun valvesAllowed(s: SyncState, blackout: Boolean): Boolean = !blackout && !s.byPartner && !s.byWearer

  fun stepOffset(cur: Int, dir: Int): Int = (cur + dir * OFFSET_STEP).coerceIn(OFFSET_MIN, OFFSET_MAX)

  fun stepIntensity(cur: Int, dir: Int): Int = (cur + dir * INTENSITY_STEP).coerceIn(INTENSITY_MIN, INTENSITY_MAX)

  fun nextSpeedLimit(cur: Int): Int {
    val i = SPEED_LIMITS.indexOf(cur)
    return SPEED_LIMITS[(i + 1) % SPEED_LIMITS.size] // unknown value (index -1) goes to Off
  }

  fun nextSuction(cur: Int): Int = (cur.coerceIn(0, SUCTION_NAMES.size - 1) + 1) % SUCTION_NAMES.size

  fun offsetLabel(ms: Int) = "$ms ms"

  fun intensityLabel(v: Int) = "$v%"

  fun speedLimitLabel(v: Int) = if (v > 0) "$v /s" else "Off"

  fun suctionLabel(v: Int) = SUCTION_NAMES[v.coerceIn(0, SUCTION_NAMES.size - 1)]

  /** The toy's state in words: "Script playing · 40 %". */
  fun toyLine(vg: VgState): String {
    val mode =
        when (vg.mode) {
          null -> "Online"
          "ONLINE_CONNECTED" -> "Idle"
          "SYNC_SCRIPT_PLAYING" -> "Script playing"
          "SYNC_SCRIPT_PAUSED" -> "Script paused"
          "LOCAL_SCRIPT_PLAYING" -> "Local script"
          else -> vg.mode.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    val speed = vg.targetSpeed?.takeIf { it > 0 }?.let { " · $it %" }.orEmpty()
    val valve = vg.valve?.let { " · suction ${if (it == "tighter") "tighter" else "looser"}" }.orEmpty()
    return mode + speed + valve
  }

  /**
   * The debug audit ([PlayerManager.auditVg], tag TroveVg): at most one VacuGlide panel is up (the chip OR the
   * whole panel, on the primary screen's controls) and never without a control panel to hang on.
   */
  fun auditProblems(full: Int, chip: Int, controlsUp: Int): List<String> {
    val out = mutableListOf<String>()
    if (full + chip > 1) out += "$full full + $chip chip panels up (max 1)"
    if (full + chip > controlsUp) out += "VacuGlide panel up with $controlsUp control panel(s)"
    return out
  }

  /** The panel's two caption lines, as the plugin words them (latency from the server). */
  fun offsetHint(latencyMs: Int?) = "Positive = device moves earlier. Network latency (${latencyMs ?: 0} ms) is added automatically."

  const val SUCTION_HINT = "Tap for a little, hold for more (max 5 s per press). Stick: Suction = stick ↑ Tighter, ↓ Looser."

  /** The "Stick" toggle's two values on the panel. */
  fun stickLabel(suction: Boolean) = if (suction) "Suction" else "Volume"
}

/** What the sub-panel sends; the activity implements it with the sync socket. False = not sent (link down). */
interface VgOut {
  fun valve(key: ValveKey, on: Boolean): Boolean

  fun set(offsetMs: Int? = null, intensity: Int? = null, speedLimit: Int? = null, suction: Int? = null): Boolean

  fun reload(): Boolean

  /** Choose a scene's script ([ScriptPicker]); false = not sent. */
  fun script(sceneId: String, id: String): Boolean = false
}

/**
 * Hold-to-use for the valves, as the plugin does it: pressing opens the valve, letting go closes it,
 * one valve at a time. A release is never lost: [release] forgets the press even when the message could
 * not be sent (the server closes the valve itself after 5 s and when the socket closes). Pure: the clock
 * is passed in.
 */
class ValveHold(private val send: (ValveKey, Boolean) -> Boolean, private val maxMs: Long = VacuGlideRules.MAX_HOLD_MS) {
  var held: ValveKey? = null
    private set

  private var since = 0L

  /** Down on a valve button. True when this key is (now) held; false when another valve is held or nothing could be sent. */
  fun press(key: ValveKey, now: Long): Boolean {
    held?.let { return it == key }
    if (!send(key, true)) return false
    held = key
    since = now
    return true
  }

  /** Up (or anything that ends the press). True when a press was ended. */
  fun release(): Boolean {
    val k = held ?: return false
    held = null
    send(k, false)
    return true
  }

  /** Releases a press that has been down [maxMs]; true when it did. */
  fun tick(now: Long): Boolean = held != null && now - since >= maxMs && release()
}

/**
 * The sub-panel's state and actions. Settings are absolute values computed from what the panel shows
 * (the server's last status, or the wearer's own last tap until the next status), so quick taps add
 * up. [onChange] tells the screens to show / hide their panel entity.
 */
class VacuGlideControl(
    private val sync: SyncIndicator,
    private val out: VgOut,
    private val clock: () -> Long,
    initialUi: VgUi = VgUi.CHIP,
    private val saveUi: (VgUi) -> Unit = {},
    private val blackout: () -> Boolean = { false },
    initialStickSuction: Boolean = false,
    private val saveStickSuction: (Boolean) -> Unit = {},
) {
  var onChange: () -> Unit = {}

  var ui by mutableStateOf(initialUi)
    private set

  /** The valve being held (the button looks pressed). */
  var held by mutableStateOf<ValveKey?>(null)
    private set

  /** A refusal / error from the server or "no link", shown on the panel for [VacuGlideRules.NOTICE_MS]. */
  var notice by mutableStateOf<String?>(null)
    private set

  private var noticeAt = 0L
  private val hold = ValveHold(out::valve)

  /**
   * "Stick: Volume / Suction" (remembered): with Suction, stick up / down holds Tighter / Looser while the
   * panel would be available ([stickActive]); else the stick is the volume as always.
   */
  var stickSuction by mutableStateOf(initialStickSuction)
    private set

  /** The stick (a hand's name) that holds the current press, or null when a button holds it / nothing is held. */
  private var stickOwner: String? = null

  /** Which of the scene's scripts drives the toy: the panel's Script row and the scene details share it. */
  val scripts = ScriptPicker(sync, out::script, clock)

  /** The wearer's taps not yet confirmed by a status. */
  private var local: VgState? = null

  val available: Boolean
    get() = VacuGlideRules.available(sync.state)

  /** The settings as shown: the wearer's own last tap, else the server's. */
  val shown: VgState
    get() = local ?: sync.state.vg

  val valvesAllowed: Boolean
    get() = VacuGlideRules.valvesAllowed(sync.state, blackout())

  /** What screen controls [controlsVisible] carries now. */
  fun panelFor(controlsVisible: Boolean): VgPanel = VacuGlideRules.panel(available, ui, controlsVisible, blackout())

  /** A bring-it-back button is useful: the toy is there but the wearer hid the panel. */
  val canRestore: Boolean
    get() = available && ui == VgUi.HIDDEN

  // ---- panel position ----

  fun expand() = moveTo(VgUi.FULL)

  fun collapse() = moveTo(VgUi.CHIP)

  fun hide() = moveTo(VgUi.HIDDEN)

  fun restore() = moveTo(VgUi.CHIP)

  private fun moveTo(v: VgUi) {
    if (v == ui) return
    // Whatever was being held goes with the panel.
    release("panel changed")
    ui = v
    saveUi(v)
    onChange()
  }

  /** B / Y: the whole panel folds into the chip (a row of the control scheme). True when it did. */
  fun collapseIfOpen(): Boolean {
    if (ui != VgUi.FULL) return false
    collapse()
    return true
  }

  fun toggleStick() {
    // Whatever the stick holds goes with the switch.
    if (stickOwner != null) release("stick mode changed")
    stickSuction = !stickSuction
    saveStickSuction(stickSuction)
  }

  /** The stick drives the valves now: the wearer chose Suction and the toy is there (the screen / Storm / Black Out part is the control scheme's). */
  val stickActive: Boolean
    get() = stickSuction && available && !blackout()

  // ---- hold-to-use suction ----

  /**
   * A stick pushed up (Tighter) or down (Looser), [who] = the hand. Same rules as the buttons: one valve
   * at a time (a press while anything is held is refused), the 5 s cap, every release path.
   */
  fun stickPress(key: ValveKey, who: String): Boolean {
    if (hold.held != null) return false
    if (!press(key)) return false
    stickOwner = who
    return true
  }

  /** A panel button came up or the pointer left it / the panel: ends a button's press, never the stick's. */
  fun releaseButton(why: String = "") {
    if (stickOwner == null) release(why)
  }

  /** [who]'s stick let go: ends the press only if that stick holds it (never a button's or the other stick's). */
  fun stickRelease(who: String) {
    if (stickOwner == who) release("stick")
  }

  fun press(key: ValveKey): Boolean {
    if (!available || !valvesAllowed) return false
    val ok = hold.press(key, clock())
    if (!ok && hold.held == null) flash("No link to the server")
    held = hold.held
    return ok
  }

  /**
   * Ends a press: the button came up, the pointer left it or the panel, the link dropped, the app was
   * paused, Black Out / a STOP ALL came, the panel closed or the controls went down. Safe to call any time.
   */
  fun release(@Suppress("UNUSED_PARAMETER") why: String = "") {
    hold.release()
    held = null
    stickOwner = null
  }

  /** Once a frame or so: a press that outlived the plugin's 5 s ends here too. */
  fun tick() {
    if (hold.tick(clock())) {
      held = null
      stickOwner = null
    }
    if (notice != null && clock() - noticeAt >= VacuGlideRules.NOTICE_MS) notice = null
  }

  // ---- settings ----

  fun stepOffset(dir: Int) = change { it.copy(offsetMs = VacuGlideRules.stepOffset(it.offsetMs, dir)) }

  fun stepIntensity(dir: Int) = change { it.copy(intensity = VacuGlideRules.stepIntensity(it.intensity, dir)) }

  fun cycleSpeedLimit() = change { it.copy(speedLimit = VacuGlideRules.nextSpeedLimit(it.speedLimit)) }

  fun cycleSuction() = change { it.copy(suction = VacuGlideRules.nextSuction(it.suction)) }

  private fun change(f: (VgState) -> VgState) {
    if (!available) return
    val cur = shown
    val next = f(cur)
    if (next == cur) return
    val ok =
        out.set(
            offsetMs = next.offsetMs.takeIf { it != cur.offsetMs },
            intensity = next.intensity.takeIf { it != cur.intensity },
            speedLimit = next.speedLimit.takeIf { it != cur.speedLimit },
            suction = next.suction.takeIf { it != cur.suction },
        )
    if (ok) local = next else flash("No link to the server")
  }

  fun reload() {
    if (!available) return
    if (out.reload()) flash("Reloading the script…") else flash("No link to the server")
  }

  // ---- from the server / the app ----

  /** A status arrived: the server's values are the truth again; anything that cannot work any more lets go. */
  fun onStatus() {
    scripts.onStatus()
    local = null
    if (!available || !valvesAllowed) release("status")
    onChange()
  }

  fun onAck(ack: ServerMessage.VgAck) {
    if (ack.op == "script") return scripts.onAck(ack)
    if (!ack.ok) flash(ack.error ?: "The server refused ${ack.op}")
    if (!ack.ok && ack.op == "valve") release("refused")
  }

  /** Black Out on / off changed (the panel is hidden with it). */
  fun onBlackout(on: Boolean) {
    if (on) release("black out")
    onChange()
  }

  private fun flash(text: String) {
    notice = text
    noticeAt = clock()
  }
}
