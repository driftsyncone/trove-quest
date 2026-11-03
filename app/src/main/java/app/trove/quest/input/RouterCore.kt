package app.trove.quest.input

import app.trove.quest.hold.AxisGate
import app.trove.quest.hold.HoldRules

/** One hand's buttons for a frame, decoded from the Controller bits (the face buttons are that hand's A/B or X/Y). */
data class HandInput(
    val primary: Boolean = false,
    val back: Boolean = false,
    val menu: Boolean = false,
    val click: Boolean = false,
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
) {
  val deflected: Boolean
    get() = up || down || left || right
}

/** What one frame of input decided. [sizeFrom] / [sizeDir]: the stick that resizes the held panel (HoldSystem applies it). */
class RouterOut(val fired: List<Fired>, val sizeFrom: Hand?, val sizeDir: Int, val pitchDir: Int = 0, val pitchHeldS: Float = 0f)

/**
 * The router's brain, free of Spatial SDK types: edges, short / long presses, stick steps and the
 * centred-click rule per hand, then [ControlScheme] decides what each means in the [Snapshot].
 * `InputRouter` feeds it every frame and runs what comes out.
 */
class RouterCore {
  private class HandState {
    val primary = PressTimer()
    val back = PressTimer()
    val stepper = StickStepper()
    val menu = PressTimer()
    var clickDown = false
    var lastDeflectedMs = NEVER

    /** The suction action this hand's stick is holding ("Stick: Suction"), or null. */
    var suction: Action? = null
  }

  private val hands = mapOf(Hand.LEFT to HandState(), Hand.RIGHT to HandState())

  /** The resize / pitch stick of the hand that is not holding: up / down or left / right, never both ([AxisGate]). */
  private val otherGate = AxisGate()
  private var lastMs = NEVER

  fun update(nowMs: Long, left: HandInput, right: HandInput, s: Snapshot): RouterOut {
    val fired = mutableListOf<Fired>()
    for ((hand, input) in listOf(Hand.LEFT to left, Hand.RIGHT to right)) frame(nowMs, hand, input, s, fired)
    // While one hand holds a panel, the other hand's stick up / down resizes it (continuous, HoldSystem's).
    val holder = s.holding
    val sizeFrom = holder?.let { if (it == Hand.LEFT) Hand.RIGHT else Hand.LEFT }
    val other = if (sizeFrom == Hand.LEFT) left else right
    val live = sizeFrom != null && !s.blackout
    val dt = if (lastMs == NEVER) 0f else ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.1f)
    lastMs = nowMs
    otherGate.step(if (live) HoldRules.vertical(other.up, other.down) else 0, if (live) HoldRules.axis(other.right, other.left) else 0, dt)
    return RouterOut(fired, sizeFrom, otherGate.v, otherGate.tilt, otherGate.tiltS)
  }

  private fun frame(now: Long, hand: Hand, input: HandInput, s: Snapshot, out: MutableList<Fired>) {
    val st = hands.getValue(hand)
    val primary = if (hand == Hand.RIGHT) Button.A else Button.X
    val backB = if (hand == Hand.RIGHT) Button.B else Button.Y

    st.primary.update(input.primary, now)?.let { fire(out, s, primary, it, hand) }
    st.back.update(input.back, now)?.let { fire(out, s, backB, it, hand) }

    // Menu exists on the left controller only. Short fires on release (the library); held 0.8 s it
    // hides / shows the screens' controls (so a short press waits for the release, like B and A).
    if (hand == Hand.LEFT) st.menu.update(input.menu, now)?.let { fire(out, s, Button.MENU, it, hand) }

    // The click bit also fires while the stick is only pushed: it counts only with the stick centred.
    if (input.click && !st.clickDown && ControlScheme.stickClickCounts(input.deflected, now - st.lastDeflectedMs)) {
      fire(out, s, Button.STICK_CLICK, Press.SHORT, hand)
    }
    st.clickDown = input.click
    if (input.deflected) st.lastDeflectedMs = now

    val step = st.stepper.update(input.up, input.down, input.left, input.right, now)
    if (step != null) {
      val action = ControlScheme.onStick(s, hand, step.dir)
      if (action != Action.NONE && action != Action.HOLD_STICK && action != Action.HOLD_SIZE && action != Action.HOLD_PITCH && !ControlScheme.isSuction(action)) {
        out += Fired(action, hand, step.count)
      }
    }
    // "Stick: Suction": the valve is held as long as the push lasts and the rule still gives it; anything
    // else (centred, pushed elsewhere, pointing at a panel, Storm, Black Out, the toggle off) lets go.
    val want = st.stepper.holding?.let { ControlScheme.onStick(s, hand, it) }?.takeIf(ControlScheme::isSuction)
    if (want != st.suction) {
      if (st.suction != null) out += Fired(Action.SUCTION_RELEASE, hand)
      if (want != null) out += Fired(want, hand)
      st.suction = want
    }
  }

  private fun fire(out: MutableList<Fired>, s: Snapshot, b: Button, press: Press, hand: Hand) {
    val a = ControlScheme.onButton(s, b, press, hand)
    if (a == Action.NONE) return
    out += Fired(a, hand, row = if (a == Action.CLOSE_ROW) ControlScheme.rowToClose(s) else null)
  }

  /** A thumb microgesture (hand tracking) as the controller input it stands in for. */
  fun microgesture(m: ControlScheme.Microgesture, hand: Hand, s: Snapshot): List<Fired> {
    val (button, dir) = ControlScheme.microgesture(m, hand)
    val a =
        when {
          button != null -> ControlScheme.onButton(s, button, Press.SHORT, hand)
          dir != null -> ControlScheme.onStick(s, hand, dir)
          else -> Action.NONE
        }
    // A swipe is a step, never a hold: suction stays on the stick (swipes are left / right anyway).
    if (a == Action.NONE || a == Action.HOLD_STICK || a == Action.HOLD_SIZE || a == Action.HOLD_PITCH || ControlScheme.isSuction(a)) return emptyList()
    return listOf(Fired(a, hand, row = if (a == Action.CLOSE_ROW) ControlScheme.rowToClose(s) else null))
  }

  private companion object {
    const val NEVER = -1_000_000L
  }
}

/** How long the button legend stays up, and its on / off state. Pure so the timing is testable. */
class LegendTimer {
  private var until = 0L

  fun visible(nowMs: Long): Boolean = nowMs < until

  fun show(nowMs: Long, forMs: Long = SHOW_MS) {
    until = nowMs + forMs
  }

  fun hide() {
    until = 0L
  }

  /** A stick press: shows it, or hides it when it is already up. */
  fun toggle(nowMs: Long, forMs: Long = SHOW_MS) {
    if (visible(nowMs)) hide() else show(nowMs, forMs)
  }

  companion object {
    const val SHOW_MS = 5_000L
    const val FIRST_RUN_MS = 15_000L
  }
}

/** The first-run card (docs/control-scheme.md section 6): four short lines, once after the update. */
object Coach {
  val lines =
      listOf(
          LegendLineText("B", "goes back. Hold B: straight to the library."),
          LegendLineText("A", "plays and pauses. Hold A: pause everything."),
          LegendLineText("Stick", "seeks left / right and sets the volume up / down (on a panel it scrolls). Menu: library."),
          LegendLineText(
              "Both grips 2 s",
              "stop everything and show the room (hands only: both open hands up in front of your face, 2 s). Press the stick for this help any time."),
      )

  data class LegendLineText(val input: String, val text: String)
}
