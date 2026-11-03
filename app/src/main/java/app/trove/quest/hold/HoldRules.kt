package app.trove.quest.hold

import kotlin.math.abs
import kotlin.math.pow

/** What thumbstick up/down does while a panel is held. Left/right is always tilt. */
enum class StickMode {
  /** Push the panel away / pull it closer along the controller ray (its size stays). */
  DISTANCE,

  /** Make the panel bigger / smaller in place. */
  SIZE;

  fun toggled(): StickMode = if (this == DISTANCE) SIZE else DISTANCE

  /** The transient hint shown on the panel after a press of the stick. */
  val hint: String
    get() = if (this == DISTANCE) "Stick: distance" else "Stick: size"
}

/**
 * The thumbstick-while-gripping rules, free of Spatial SDK types so they can be unit tested
 * (README "Moving panels"). The Quest's stick reaches us only as direction bits (no analog value),
 * so every motion runs at a fixed rate that eases in over [RAMP_S] after the stick is pushed.
 */
object HoldRules {
  /** Seconds from stick pushed to full speed (smoothstep), so a quick flick makes a small change. */
  const val RAMP_S = 0.3f

  /** Push / pull speed at full ramp, metres per second. */
  const val PUSH_SPEED_MPS = 1.0f

  /** How close / far a held panel may be pushed (distance from the head to its centre). */
  const val MIN_DISTANCE_M = 0.6f
  const val MAX_DISTANCE_M = 10f

  /** Size grows by this factor per second at full ramp (shrinks by its inverse). */
  const val ZOOM_PER_S = 1.5f

  /** Tilt speed at full ramp, degrees per second (0.2.1: 45, too quick to stop where you want). */
  const val ROLL_DEG_PER_S = 30f

  /** A tilt starts after left/right has been held this long (a quick flick does nothing). */
  const val TILT_DELAY_S = 0.2f

  /** Letting go within this many degrees of level snaps the panel level. */
  const val SNAP_DEG = 3f

  /** Video screen width (metres). */
  val SCREEN_WIDTH_M = 0.4f..8f

  /** Wall and sphere-control scale (1 = the built-in size: wall 1.8 m, controls 1.2 m wide). */
  val PANEL_SCALE = 0.5f..2.5f

  /** -1, 0 or +1 from two direction bits (both = 0). */
  fun axis(positive: Boolean, negative: Boolean): Int =
      when {
        positive && !negative -> 1
        negative && !positive -> -1
        else -> 0
      }

  /**
   * Stick up/down from the U and D bits: +1 up, -1 down. Down wins when both are set, like the SDK's
   * own panel scrolling (PanelInputListener checks U, then D overrides); 0.2.1 read "both" as nothing.
   */
  fun vertical(up: Boolean, down: Boolean): Int = if (down) -1 else if (up) 1 else 0

  /** A stick click only counts after the stick has been centred this long. */
  const val CLICK_QUIET_MS = 200L

  /**
   * Whether this frame toggles distance/size. [facePressed] always does (nobody passes it any
   * more: B / Y put the panel back; the control scheme's stick press uses this with it false).
   * [clickPressed] (stick-click bit went down) only with the stick centred now and for
   * [CLICK_QUIET_MS] before: on the Quest that bit also fires while the stick is merely pushed, which
   * in 0.2.1 flipped the mode on every up/down (and so stick-down seemed to never pull).
   */
  fun acceptToggle(clickPressed: Boolean, facePressed: Boolean, deflected: Boolean, msSinceDeflected: Long): Boolean =
      facePressed || (clickPressed && !deflected && msSinceDeflected >= CLICK_QUIET_MS)

  /** 0..1: how far into the ease-in the stick is after [heldS] seconds in one direction. */
  fun ramp(heldS: Float): Float {
    val x = (heldS / RAMP_S).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
  }

  /**
   * New size after [dt] seconds with the stick at [dir] (+1 up = bigger). Exponential, so it feels
   * the same at every size; never leaves [range] (a size already outside it is pulled in).
   */
  fun zoom(size: Float, dir: Int, dt: Float, ramp: Float, range: ClosedFloatingPointRange<Float>): Float {
    val next = size * ZOOM_PER_S.pow(dir * dt * ramp)
    return next.coerceIn(range.start, range.endInclusive)
  }

  /**
   * How far to move a held panel along the ray this frame (+ = away), with the stick at [dir]
   * (+1 up = push). Stops at [MIN_DISTANCE_M] / [MAX_DISTANCE_M] from the head; a panel already
   * past a limit may still move back towards the allowed range but not further out.
   */
  fun pushDelta(headDistance: Float, dir: Int, dt: Float, ramp: Float): Float {
    if (dir == 0) return 0f
    val want = dir * PUSH_SPEED_MPS * dt * ramp
    return if (dir > 0) {
      if (headDistance >= MAX_DISTANCE_M) 0f else want.coerceAtMost(MAX_DISTANCE_M - headDistance)
    } else {
      if (headDistance <= MIN_DISTANCE_M) 0f else want.coerceAtLeast(MIN_DISTANCE_M - headDistance)
    }
  }

  /** New tilt after [dt] s with the stick at [dir] (+1 right = clockwise as you look at it), in -180..180. */
  fun roll(deg: Float, dir: Int, dt: Float, ramp: Float): Float = wrap(deg + dir * ROLL_DEG_PER_S * dt * ramp)

  /** Pitch speed at full ramp, degrees per second (Face me off: the other stick left / right). */
  const val PITCH_DEG_PER_S = 30f

  /** Pitch limit of a flat screen, degrees. */
  const val MAX_PITCH_DEG = 85f

  /** New pitch after [dt] s with the other stick at [dir] (+1 right = the top tips away, the screen faces up), within the limit. */
  fun pitch(deg: Float, dir: Int, dt: Float, ramp: Float): Float = (deg + dir * PITCH_DEG_PER_S * dt * ramp).coerceIn(-MAX_PITCH_DEG, MAX_PITCH_DEG)

  /** On release: close to level becomes exactly level. */
  fun snap(deg: Float): Float = if (abs(wrap(deg)) <= SNAP_DEG) 0f else wrap(deg)

  /** Degrees into -180 (exclusive) .. 180. */
  fun wrap(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
  }
}

/**
 * Keeps one stick movement (from centred back to centred) to either up/down or tilt. The stick only
 * reports direction bits, and on the Quest a sideways push with the thumb arcs: left reads as
 * up + left and right as down + right after the first few frames (logged on the device, 0.2.4). The
 * first bit that lights up decides: left/right first = a tilt (after [HoldRules.TILT_DELAY_S]), and
 * up/down is ignored until the stick is centred; up/down first (or both in the same frame) = push,
 * pull or resize, and left/right is ignored until centred. 0.2.3 let up/down win any diagonal, so a
 * tilt pushed or pulled the panel instead (the "swoop").
 */
class AxisGate {
  private enum class Lock { NONE, VERTICAL, HORIZONTAL }

  private var lock = Lock.NONE
  private var hDir = 0
  private var hHeld = 0f

  /** Up/down to apply this frame (+1 up). */
  var v = 0
    private set

  /** Tilt to apply this frame (+1 right), 0 while the delay runs. */
  var tilt = 0
    private set

  /** Seconds since the tilt started, for [HoldRules.ramp]. */
  var tiltS = 0f
    private set

  fun step(vIn: Int, hzIn: Int, dt: Float) {
    if (vIn == 0 && hzIn == 0) lock = Lock.NONE
    else if (lock == Lock.NONE) lock = if (vIn == 0) Lock.HORIZONTAL else Lock.VERTICAL
    v = if (lock == Lock.VERTICAL) vIn else 0
    val h = if (lock == Lock.HORIZONTAL) hzIn else 0
    hHeld = if (h == 0) 0f else if (h == hDir) hHeld + dt else dt
    hDir = h
    tilt = if (h != 0 && hHeld >= HoldRules.TILT_DELAY_S) h else 0
    tiltS = if (tilt != 0) hHeld - HoldRules.TILT_DELAY_S else 0f
  }
}
