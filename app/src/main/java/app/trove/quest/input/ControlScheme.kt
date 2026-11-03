package app.trove.quest.input

import app.trove.quest.hold.HoldRules

/*
 * The unified controller + hand scheme (docs/control-scheme.md), as plain Kotlin so it can be unit
 * tested. `InputRouter` reads the Controller bits once per frame, builds a [Snapshot], asks
 * [ControlScheme] what a press or a stick step means and dispatches the [Action] through `ActionSink`. Trigger and grip stay pointer-based (Compose clicks, the video / tile scene-object
 * listeners, GrabbableSystem) and are only described here, not routed.
 */

enum class Hand { LEFT, RIGHT }

/** The buttons the router reads. A/X and B/Y are mirrors (same [Role]), so either hand works alone. */
enum class Button(val hand: Hand?, val role: Role, val glyph: String) {
  A(Hand.RIGHT, Role.PRIMARY, "A"),
  X(Hand.LEFT, Role.PRIMARY, "X"),
  B(Hand.RIGHT, Role.BACK, "B"),
  Y(Hand.LEFT, Role.BACK, "Y"),
  /** Left controller only (the right one has the system Meta button). Left palm pinch with hands. */
  MENU(Hand.LEFT, Role.MENU, "☰"),
  /** Either stick pressed in; counts only with the stick centred ([ControlScheme.stickClickCounts]). */
  STICK_CLICK(null, Role.HELP, "Stick press");

  enum class Role { PRIMARY, BACK, MENU, HELP }
}

enum class Press { SHORT, LONG }

enum class Dir { UP, DOWN, LEFT, RIGHT }

/** What a hand's ray points at this frame (the router resolves it from panel hover / ray tests). */
enum class Target {
  NOTHING,

  /** A Compose panel whose content scrolls (wall, details, an open chip row): the SDK owns the stick there. */
  PANEL,

  /** A screen's control panel with no row open (nothing to scroll): the stick acts like on the video. */
  CONTROLS,
  VIDEO,
  TILE,

  /** The Focus layout's centre tile (and its scrub bar): the stick seeks it. */
  CENTRE,
}

/** A row or popover that B closes, newest last. [owner]: "wall" or a screen id. */
data class Row(val kind: Kind, val owner: String) {
  enum class Kind(val label: String) {
    VIEW("View"),
    STREAM("Stream"),
    MARKERS("Markers"),
    SORT("Sort"),
    ACCOUNT("Account"),

    /** The whole VacuGlide sub-panel under a screen's controls: B folds it into its chip. */
    VACUGLIDE("VacuGlide"),

    /** The session timer's chips (15 ... 90 min, +10, Cancel) on a screen's controls or, owner [STORM], the Storm panel. */
    TIMER("Timer"),
  }

  val onWall: Boolean
    get() = owner == WALL

  companion object {
    const val WALL = "wall"

    /** Owner of a row on the Storm control panel. */
    const val STORM = "storm"
  }
}

/** Everything the scheme needs to know about the app this frame. Defaults = the bare library. */
data class Snapshot(
    val loggedIn: Boolean = true,
    /** Black Out: everything hidden; only the both-grips escape works (outside this scheme). */
    val blackout: Boolean = false,
    val keyboardOpen: Boolean = false,
    val dragging: Boolean = false,
    /** The hand holding a panel with grip, or null. */
    val holding: Hand? = null,
    val heldIsWall: Boolean = false,
    /** Open rows, oldest first. Rows of a hidden panel are ignored. */
    val rows: List<Row> = emptyList(),
    val detailsOpen: Boolean = false,
    /** The wall is on a performer / studio / tag page: B goes back to that section's grid. */
    val pageOpen: Boolean = false,
    val libraryVisible: Boolean = true,
    /** The wearer hid the screens' control panels ("Hide controls"). Only meaningful with screens open. */
    val controlsHidden: Boolean = false,
    /** Open video screens (a sphere counts as one). */
    val screens: Int = 0,
    val sphere: Boolean = false,
    val storm: Boolean = false,
    /** Storm's control panel was hidden with its Hide button (Storm still runs). */
    val stormPanelHidden: Boolean = false,
    /** STOPPED by the partner or the wearer: no mapping changes (nothing here ever resumes the toy). */
    val stopped: Boolean = false,
    /**
     * The VacuGlide panel's "Stick: Suction" is on and would work now (armed, the toy online, a screen and
     * not Storm, no Black Out): stick up / down holds Tighter / Looser instead of the volume ([stickSuction]).
     */
    val stickSuction: Boolean = false,
    val pointer: Map<Hand, Target> = emptyMap(),
) {
  val media: Boolean
    get() = screens > 0 || storm

  fun pointing(h: Hand): Target = pointer[h] ?: Target.NOTHING
}

/** The user-facing situation, for the legend title and docs/control-scheme.md section 2. */
enum class Context(val title: String) {
  LOGIN("Log in"),
  BLACKOUT("Black Out"),
  DRAGGING("Dragging a card"),
  HELD("Holding a panel"),
  KEYBOARD("Keyboard"),
  ROW("Row open"),
  DETAILS("Scene details"),
  LIBRARY("Library"),
  STORM("Storm"),
  SPHERE("180° / 360° video"),
  VIDEO("Video"),
}

enum class Action(val label: String) {
  NONE(""),

  // B / Y: the back stack (ControlScheme.back), top first.
  PUT_BACK("Put back"),
  CANCEL_DRAG("Cancel"),
  CLOSE_KEYBOARD("Close keyboard"),
  CLOSE_ROW("Close row"),
  CLOSE_DETAILS("Back to library"),
  /** B / Y on a performer / studio / tag page: back to the grid it was opened from. */
  CLOSE_PAGE("Back to grid"),
  HIDE_LIBRARY("Back to video"),
  /** B / Y on a video with the library and the controls both hidden: the controls come back first. */
  SHOW_CONTROLS("Show controls"),
  SHOW_LIBRARY("Library"),
  SHOW_STORM_PANEL("Storm controls"),
  /** B / Y in Storm with nothing else to go back from: Storm closes (back to the screens, else the library). */
  EXIT_STORM("Exit Storm"),
  /** B / Y on a video (library hidden): the focused screen closes, back to the library once none is left. */
  CLOSE_SCREEN("Close video"),
  /** Long B / Y: every row and the details closed, the library grid in front of you. */
  HOME("Library home"),

  // A / X
  TOGGLE_PLAY("Play / Pause"),
  STORM_TOGGLE_PLAY("Pause / Play all"),
  /** Long A / X. Only ever pauses (removes stimulation); there is no "play all" on a button. */
  PAUSE_ALL("Pause everything"),

  // Menu
  TOGGLE_LIBRARY("Library"),
  /** Long Menu: hide the screens' control panels, or bring them back. */
  TOGGLE_CONTROLS("Hide / show controls"),

  // Stick press (centred)
  SHOW_LEGEND("Button help"),
  TOGGLE_HOLD_MODE("Distance ↔ size"),

  // Stick steps (not holding)
  SEEK_BACK("−10 s"),
  SEEK_FORWARD("+10 s"),
  VOLUME_UP("Louder"),
  VOLUME_DOWN("Quieter"),
  STORM_NEXT_CLIP("Next clip"),
  STORM_SEEK_BACK("−10 s"),
  STORM_SEEK_FORWARD("+10 s"),

  // Stick up / down with "Stick: Suction" (VacuGlide panel): held, not stepped. The router fires the
  // press when the push starts and SUCTION_RELEASE when it ends (centred, other direction, rule gone).
  SUCTION_TIGHTER("Suction tighter"),
  SUCTION_LOOSER("Suction looser"),
  SUCTION_RELEASE("Suction release"),

  // Stick while holding: continuous, handed to HoldSystem.
  HOLD_STICK("Move / tilt"),
  HOLD_SIZE("Size"),
  HOLD_PITCH("Pitch"),
}

object ControlScheme {
  /** A press held this long is a long press (B = home, A = pause everything); a fill ring shows from [LONG_FEEDBACK_MS]. */
  const val LONG_MS = 800L
  const val LONG_FEEDBACK_MS = 250L

  /** Seek step, and the bigger step once a direction has been held for [SEEK_BIG_AFTER] steps. */
  const val SEEK_MS = 10_000L
  const val SEEK_BIG_MS = 30_000L
  const val SEEK_BIG_AFTER = 6

  /** Volume step (app volume 0..1, applied to the screen with sound and to Storm). */
  const val VOLUME_STEP = 0.1f

  fun contextOf(s: Snapshot): Context =
      when {
        !s.loggedIn -> Context.LOGIN
        s.blackout -> Context.BLACKOUT
        s.dragging -> Context.DRAGGING
        s.holding != null -> Context.HELD
        s.keyboardOpen -> Context.KEYBOARD
        visibleRows(s).isNotEmpty() -> Context.ROW
        s.libraryVisible && s.detailsOpen -> Context.DETAILS
        s.libraryVisible || !s.media -> Context.LIBRARY
        s.screens == 0 -> Context.STORM
        s.sphere -> Context.SPHERE
        else -> Context.VIDEO
      }

  /** Rows that are on screen: the wall's only while the library shows. */
  fun visibleRows(s: Snapshot): List<Row> = s.rows.filter { !it.onWall || s.libraryVisible }

  /**
   * B / Y: what "back" does now. The stack is derived from state, not history, so a state changed
   * by another path (a button on a panel, the partner's Director, a screen closing) never leaves a
   * stale entry. Top first:
   * hold (put back) > drag (cancel) > keyboard > newest visible row > details > page (performer / studio / tag) > library over
   * video/Storm (hide it, so browsing never discards a video) > Storm (close it) > a video without the library
   * (close the focused screen; the library shows when none is left) > nothing. It never lifts Black Out or resumes anything.
   */
  fun back(s: Snapshot): Action =
      when {
        s.blackout -> Action.NONE
        s.holding != null -> Action.PUT_BACK
        s.dragging -> Action.CANCEL_DRAG
        s.keyboardOpen -> Action.CLOSE_KEYBOARD
        !s.loggedIn -> Action.NONE
        visibleRows(s).isNotEmpty() -> Action.CLOSE_ROW
        s.libraryVisible && s.detailsOpen -> Action.CLOSE_DETAILS
        s.libraryVisible && s.pageOpen -> Action.CLOSE_PAGE
        s.libraryVisible && s.media -> Action.HIDE_LIBRARY
        !s.libraryVisible && s.storm -> Action.EXIT_STORM
        !s.libraryVisible && s.screens > 0 -> Action.CLOSE_SCREEN
        else -> Action.NONE
      }

  /** The row [Action.CLOSE_ROW] closes: the newest visible one. */
  fun rowToClose(s: Snapshot): Row? = visibleRows(s).lastOrNull()

  /**
   * Long B / Y: as [back] for the transient states; on video screens (no Storm) with nothing else open it shows /
   * hides the library without closing anything; else straight home (no-op when already there).
   */
  fun backLong(s: Snapshot): Action =
      when (val b = back(s)) {
        Action.NONE, Action.PUT_BACK, Action.CANCEL_DRAG, Action.CLOSE_KEYBOARD -> b
        Action.CLOSE_SCREEN, Action.HIDE_LIBRARY -> if (s.screens > 0 && !s.storm) (if (s.libraryVisible) Action.HIDE_LIBRARY else Action.SHOW_LIBRARY) else Action.HOME
        else -> if (visibleRows(s).isEmpty() && s.libraryVisible && !s.detailsOpen && !s.pageOpen) Action.NONE else Action.HOME
      }

  /** A press of a face button, Menu or a (centred) stick press. Trigger and grip are not routed. */
  fun onButton(s: Snapshot, button: Button, press: Press, hand: Hand? = button.hand): Action {
    if (s.blackout) return Action.NONE
    return when (button.role) {
      Button.Role.BACK -> if (press == Press.SHORT) back(s) else backLong(s)
      Button.Role.PRIMARY -> primary(s, press)
      Button.Role.MENU ->
          when {
            !s.loggedIn || s.dragging || s.holding != null || s.keyboardOpen -> Action.NONE
            // Held: the controls of the screens (Storm has none).
            press == Press.LONG -> if (s.screens > 0) Action.TOGGLE_CONTROLS else Action.NONE
            !s.media -> Action.NONE // the library is all there is
            !s.libraryVisible && s.storm && s.stormPanelHidden -> Action.SHOW_STORM_PANEL
            else -> Action.TOGGLE_LIBRARY
          }
      Button.Role.HELP ->
          when {
            s.holding != null -> if (hand == s.holding) Action.TOGGLE_HOLD_MODE else Action.NONE
            s.dragging -> Action.NONE
            else -> Action.SHOW_LEGEND
          }
    }
  }

  private fun primary(s: Snapshot, press: Press): Action =
      when {
        !s.loggedIn || s.dragging || s.keyboardOpen -> Action.NONE
        press == Press.LONG -> if (s.media) Action.PAUSE_ALL else Action.NONE
        s.screens > 0 -> Action.TOGGLE_PLAY
        s.storm -> Action.STORM_TOGGLE_PLAY
        else -> Action.NONE
      }

  /**
   * One step of [hand]'s stick (the router's [StickStepper] decides when a step fires). The stick
   * scrolls whatever Compose panel the ray is on (the SDK does that; we stay out); pointed anywhere
   * else it drives the focused video: ←→ seek, ↑↓ volume (or, with "Stick: Suction" on the VacuGlide panel,
   * ↑↓ held = suction Tighter / Looser held, see [Snapshot.stickSuction]). On the Focus centre, ←→ seek it. In Storm with no screen, → cuts to the next
   * clip. Holding a panel, the gripping hand's stick is HoldSystem's, the other stick's ↑↓ resizes and its ←→ pitches (Face me off).
   */
  fun onStick(s: Snapshot, hand: Hand, dir: Dir): Action {
    if (!s.loggedIn || s.blackout || s.dragging || s.keyboardOpen) return Action.NONE
    s.holding?.let { h ->
      return when {
        h == hand -> Action.HOLD_STICK
        dir == Dir.UP || dir == Dir.DOWN -> Action.HOLD_SIZE
        else -> Action.HOLD_PITCH
      }
    }
    val t = s.pointing(hand)
    if (t == Target.PANEL) return Action.NONE
    if (t == Target.CENTRE && s.storm) {
      return when (dir) {
        Dir.LEFT -> Action.STORM_SEEK_BACK
        Dir.RIGHT -> Action.STORM_SEEK_FORWARD
        Dir.UP -> Action.VOLUME_UP
        Dir.DOWN -> Action.VOLUME_DOWN
      }
    }
    val stormTarget = s.storm && (s.screens == 0 || t == Target.TILE)
    return when {
      stormTarget ->
          when (dir) {
            Dir.RIGHT -> Action.STORM_NEXT_CLIP
            Dir.LEFT -> Action.NONE
            Dir.UP -> Action.VOLUME_UP
            Dir.DOWN -> Action.VOLUME_DOWN
          }
      s.screens > 0 ->
          when (dir) {
            Dir.LEFT -> Action.SEEK_BACK
            Dir.RIGHT -> Action.SEEK_FORWARD
            Dir.UP -> if (stickSuction(s)) Action.SUCTION_TIGHTER else Action.VOLUME_UP
            Dir.DOWN -> if (stickSuction(s)) Action.SUCTION_LOOSER else Action.VOLUME_DOWN
          }
      else -> Action.NONE
    }
  }

  /** Stick up / down drives the valves: the wearer chose it and the VacuGlide panel would work (a screen, not Storm, no Black Out). */
  fun stickSuction(s: Snapshot): Boolean = s.stickSuction && s.screens > 0 && !s.storm && !s.blackout

  /** A stick action that is held (press at the start of the push, release at its end), not stepped. */
  fun isSuction(a: Action): Boolean = a == Action.SUCTION_TIGHTER || a == Action.SUCTION_LOOSER

  /** Seek size for the [count]-th step of one push (1-based): 10 s, then 30 s once held a while. */
  fun seekMs(count: Int): Long = if (count > SEEK_BIG_AFTER) SEEK_BIG_MS else SEEK_MS

  /**
   * The stick-press bit also fires while the stick is merely pushed on the Quest; a press counts only
   * with the stick centred now and for [HoldRules.CLICK_QUIET_MS] before (same rule as the hold toggle).
   */
  fun stickClickCounts(deflected: Boolean, msSinceDeflected: Long): Boolean =
      HoldRules.acceptToggle(clickPressed = true, facePressed = false, deflected, msSinceDeflected)

  /** Hand tracking (SDK `MicrogesturesSystem`, needs a device test): what each thumb microgesture stands in for. */
  enum class Microgesture { TAP, SWIPE_LEFT, SWIPE_RIGHT, SWIPE_FORWARD, SWIPE_BACK }

  /** A microgesture as the equivalent controller input: a [Button] press or a stick [Dir] step. */
  fun microgesture(m: Microgesture, hand: Hand): Pair<Button?, Dir?> =
      when (m) {
        Microgesture.TAP -> (if (hand == Hand.RIGHT) Button.A else Button.X) to null
        Microgesture.SWIPE_BACK -> (if (hand == Hand.RIGHT) Button.B else Button.Y) to null
        Microgesture.SWIPE_FORWARD -> Button.MENU to null
        Microgesture.SWIPE_LEFT -> null to Dir.LEFT
        Microgesture.SWIPE_RIGHT -> null to Dir.RIGHT
      }

  /** One line of the on-screen button legend. */
  data class LegendLine(val input: String, val text: String)

  /**
   * The legend for [hand]'s controller, built from the same rules the router runs, so it can never
   * disagree with what the buttons do. Lines with no action are left out; the escape is always last.
   */
  fun legend(s: Snapshot, hand: Hand): List<LegendLine> {
    if (s.blackout) return listOf(LegendLine("Both grips 2 s", "Stop + room"))
    val out = mutableListOf<LegendLine>()
    triggerText(s)?.let { out += LegendLine("Trigger", it) }
    val primary = if (hand == Hand.RIGHT) Button.A else Button.X
    val backB = if (hand == Hand.RIGHT) Button.B else Button.Y
    for ((b, p) in listOf(primary to Press.SHORT, primary to Press.LONG, backB to Press.SHORT, backB to Press.LONG)) {
      val a = onButton(s, b, p, hand)
      if (a == Action.NONE) continue
      if (p == Press.LONG && a == onButton(s, b, Press.SHORT, hand)) continue
      out += LegendLine(if (p == Press.LONG) "Hold ${b.glyph}" else b.glyph, a.label)
    }
    if (hand == Hand.LEFT) {
      onButton(s, Button.MENU, Press.SHORT).takeIf { it != Action.NONE }?.let { out += LegendLine("☰", it.label) }
      onButton(s, Button.MENU, Press.LONG).takeIf { it != Action.NONE }?.let { out += LegendLine("Hold ☰", if (s.controlsHidden) "Show controls" else "Hide controls") }
    }
    stickText(s, hand)?.let { out += it }
    onButton(s, Button.STICK_CLICK, Press.SHORT, hand).takeIf { it != Action.NONE }?.let { out += LegendLine("Press stick", it.label) }
    out += LegendLine("Both grips 2 s", "Stop + room")
    return out
  }

  private fun stickText(s: Snapshot, hand: Hand): List<LegendLine>? {
    val lr = onStick(s, hand, Dir.LEFT) to onStick(s, hand, Dir.RIGHT)
    val ud = onStick(s, hand, Dir.UP) to onStick(s, hand, Dir.DOWN)
    if (lr.first == Action.HOLD_STICK) {
      return listOf(LegendLine("Stick ↑↓", "Closer / further (or size)"), LegendLine("Stick ←→", "Tilt"))
    }
    val lines = mutableListOf<LegendLine>()
    if (lr.first == Action.HOLD_PITCH) {
      lines += LegendLine("Stick ←→", "Pitch (other hand, Face me off)")
    } else if (lr.first != Action.NONE || lr.second != Action.NONE) {
      lines += LegendLine("Stick ←→", listOf(lr.first, lr.second).filter { it != Action.NONE }.joinToString(" / ") { it.label })
    }
    if (ud.first != Action.NONE) {
      lines +=
          LegendLine(
              "Stick ↑↓",
              when (ud.first) {
                Action.HOLD_SIZE -> "Size (other hand)"
                Action.SUCTION_TIGHTER -> "Hold: suction tighter / looser"
                else -> "Volume"
              },
          )
    }
    if (lines.isEmpty() && s.pointing(hand) == Target.PANEL) lines += LegendLine("Stick", "Scroll")
    return lines.ifEmpty { null }
  }

  /** Trigger / pinch, which stays pointer-based: described for the legend only. */
  fun triggerText(s: Snapshot): String? =
      when (contextOf(s)) {
        Context.BLACKOUT -> null
        Context.DRAGGING -> "Let go to drop"
        Context.HELD -> null
        Context.KEYBOARD -> "Type"
        Context.LOGIN, Context.ROW, Context.DETAILS -> "Select"
        Context.LIBRARY -> "Open · hold: new screen · drag out"
        Context.STORM -> "Tile: sound (Focus: to centre)"
        Context.SPHERE, Context.VIDEO -> if (s.screens > 1) "Video: focus · again: play/pause" else "Video: play / pause"
      }
}

/**
 * Short vs long press of one button. Feed it every frame; it reports [Press.LONG] once when the
 * button has been down [longMs] (while still held, so the action lands without letting go) and
 * [Press.SHORT] on a release before that. A press that went long reports nothing on release.
 */
class PressTimer(private val longMs: Long = ControlScheme.LONG_MS) {
  private var downAt: Long? = null
  private var firedLong = false

  /** 0..1 while held past [ControlScheme.LONG_FEEDBACK_MS] (for a fill ring), else 0. */
  fun fill(nowMs: Long): Float {
    val d = downAt ?: return 0f
    if (firedLong) return 0f
    val held = nowMs - d
    if (held < ControlScheme.LONG_FEEDBACK_MS) return 0f
    return (held.toFloat() / longMs).coerceIn(0f, 1f)
  }

  fun update(down: Boolean, nowMs: Long): Press? {
    val since = downAt
    if (down) {
      if (since == null) {
        downAt = nowMs
        firedLong = false
        return null
      }
      if (!firedLong && nowMs - since >= longMs) {
        firedLong = true
        return Press.LONG
      }
      return null
    }
    if (since == null) return null
    downAt = null
    return if (firedLong) null else Press.SHORT
  }
}

/**
 * Turns one stick's direction bits into steps: one at the push, then repeats while held. The first
 * direction that lights up owns the push until the stick is centred again (a sideways push on the
 * Quest arcs into up+left / down+right, which must not change the volume while seeking); when U and D
 * are both set, down wins (as in [HoldRules.vertical]). [count] numbers the steps of one push (1, 2,
 * ...), so the caller can grow the step ([ControlScheme.seekMs]).
 */
class StickStepper(
    private val firstRepeatMs: Long = FIRST_REPEAT_MS,
    private val repeatMs: Long = REPEAT_MS,
) {
  data class Step(val dir: Dir, val count: Int)

  private var lock: Dir? = null
  private var nextAt = 0L
  private var count = 0

  /** The push's direction while the stick is still pushed that way (else null): what a held stick action holds. */
  var holding: Dir? = null
    private set

  fun update(up: Boolean, down: Boolean, left: Boolean, right: Boolean, nowMs: Long): Step? {
    val v = HoldRules.vertical(up, down)
    val h = HoldRules.axis(right, left)
    if (v == 0 && h == 0) {
      lock = null
      holding = null
      return null
    }
    val current = lock
    if (current == null) {
      // Up/down first, or both in the same frame, is vertical (as AxisGate): a seek starts sideways.
      val d = if (v != 0) (if (v > 0) Dir.UP else Dir.DOWN) else (if (h > 0) Dir.RIGHT else Dir.LEFT)
      lock = d
      holding = d
      count = 1
      nextAt = nowMs + firstRepeatMs
      return Step(d, count)
    }
    val stillHeld =
        when (current) {
          Dir.UP -> v > 0
          Dir.DOWN -> v < 0
          Dir.LEFT -> h < 0
          Dir.RIGHT -> h > 0
        }
    holding = if (stillHeld) current else null
    if (!stillHeld || nowMs < nextAt) return null
    count++
    nextAt = nowMs + repeatMs
    return Step(current, count)
  }

  companion object {
    const val FIRST_REPEAT_MS = 450L
    const val REPEAT_MS = 250L
  }
}
