package app.trove.quest.input

import android.os.SystemClock
import android.util.Log
import app.trove.quest.drag.V3
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.MicrogestureBits
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Microgestures
import com.meta.spatial.toolkit.Transform

/**
 * The one reader of the face buttons, Menu and the stick presses (docs/control-scheme.md, section 8).
 * Every frame it reads each local controller once, works out what the user points at, asks
 * [RouterCore] / [ControlScheme] what the input means in the app's [Snapshot] and runs it through the
 * [ActionSink]. Trigger and grip stay the SDK's; HoldSystem keeps the grip + stick maths and reads the
 * resize stick from [RouterFrame]; the both-grips escape is `BlackoutGripSystem`, which this system
 * neither delays nor replaces (and in Black Out the scheme maps every button to nothing).
 *
 * [snapshot]: the app state without the pointer. [regions]: what rays can hit (wall, screens, controls,
 * tiles). [onFrame]: called after dispatch with the time and the snapshot (the legend's timer).
 */
class InputRouter(
    private val snapshot: () -> Snapshot,
    private val regions: () -> List<PointerRegion>,
    private val sink: ActionSink,
    private val onFrame: (nowMs: Long, s: Snapshot) -> Unit = { _, _ -> },
) : SystemBase() {
  private val core = RouterCore()
  private var microgesturesBroken = false

  /** Debug builds log every fired action (logcat TroveInput). */
  var debug = false

  private class Gathered {
    var bits = 0
    var ray: Pair<V3, V3>? = null
  }

  override fun execute() {
    val now = SystemClock.uptimeMillis()
    val gathered = mapOf(Hand.LEFT to Gathered(), Hand.RIGHT to Gathered())
    val micro = ArrayList<Pair<Hand, Entity>>(2)
    for (e in Query.where { has(Controller.id) }.eval()) {
      if (!e.isLocal()) continue
      val c = e.tryGetComponent<Controller>() ?: continue
      if (!c.isActive) continue
      val hand = handOf(e, c.buttonState) ?: continue
      val g = gathered.getValue(hand)
      g.bits = g.bits or c.buttonState
      if (g.ray == null) {
        val p = e.tryGetComponent<Transform>()?.transform
        if (p != null) {
          val f = p.forward()
          g.ray = V3(p.t.x, p.t.y, p.t.z) to V3(f.x, f.y, f.z).normalized()
        }
      }
    }
    val rs = regions()
    val pointer =
        buildMap {
          for ((h, g) in gathered) g.ray?.let { (o, d) -> put(h, PointerTargets.resolve(o, d, rs)) }
        }
    val s = snapshot().copy(pointer = pointer)
    if (debug && now - lastPointerLogMs >= POINTER_LOG_MS) {
      lastPointerLogMs = now
      logPointer(gathered, rs, pointer, s)
    }
    val out = core.update(now, decode(Hand.LEFT, gathered.getValue(Hand.LEFT).bits), decode(Hand.RIGHT, gathered.getValue(Hand.RIGHT).bits), s)
    RouterFrame.sizeFrom = out.sizeFrom
    RouterFrame.sizeDir = out.sizeDir
    RouterFrame.pitchDir = out.pitchDir
    RouterFrame.pitchHeldS = out.pitchHeldS
    for (f in out.fired) run(f)
    for (f in microgestures(s)) run(f)
    onFrame(now, s)
  }

  private var lastPointerLogMs = 0L

  /**
   * Debug builds, every 500 ms: each hand's pointer target, the ray, and how far (degrees) the ray is
   * from the centre of the nearest region of each target kind, so a missed hit shows how much it missed by.
   */
  private fun logPointer(gathered: Map<Hand, Gathered>, rs: List<PointerRegion>, pointer: Map<Hand, Target>, s: Snapshot) {
    val sb = StringBuilder("pointer")
    for ((h, g) in gathered) {
      val ray = g.ray
      if (ray == null) {
        sb.append(" $h=no-ray")
        continue
      }
      val (o, d) = ray
      sb.append(" $h=${pointer[h] ?: Target.NOTHING} o=(%.2f,%.2f,%.2f) d=(%.2f,%.2f,%.2f)".format(o.x, o.y, o.z, d.x, d.y, d.z))
      val off =
          rs.groupBy { it.target }.entries.joinToString(",") { (t, list) ->
            val best =
                list.minOf { r ->
                  val v = (r.rect.center - o).normalized()
                  Math.toDegrees(Math.acos((v dot d).toDouble().coerceIn(-1.0, 1.0)))
                }
            "$t:%.1fdeg".format(best)
          }
      sb.append(" off[$off]")
    }
    sb.append(" regions=${rs.size} storm=${s.storm} screens=${s.screens} lib=${s.libraryVisible}")
    Log.d(TAG, sb.toString())
  }

  private fun run(f: Fired) {
    if (debug) Log.d(TAG, "${f.hand} ${f.action} x${f.count}")
    sink.run(f)
  }

  /** Left or right: the controller entity's attachment ("left_controller" ...), else the bits it reports. */
  private fun handOf(e: Entity, bits: Int): Hand? {
    val type = e.tryGetComponent<AvatarAttachment>()?.type
    return when {
      type != null && type.startsWith("right") -> Hand.RIGHT
      type != null && type.startsWith("left") -> Hand.LEFT
      bits and ButtonBits.RightButtonMask != 0 -> Hand.RIGHT
      bits and ButtonBits.LeftButtonMask != 0 -> Hand.LEFT
      else -> null
    }
  }

  private fun decode(hand: Hand, b: Int): HandInput =
      if (hand == Hand.LEFT) {
        HandInput(
            primary = b and ButtonBits.ButtonX != 0,
            back = b and ButtonBits.ButtonY != 0,
            menu = b and ButtonBits.ButtonMenu != 0,
            click = b and ButtonBits.ButtonThumbLClick != 0,
            up = b and ButtonBits.ButtonThumbLU != 0,
            down = b and ButtonBits.ButtonThumbLD != 0,
            left = b and ButtonBits.ButtonThumbLL != 0,
            right = b and ButtonBits.ButtonThumbLR != 0,
        )
      } else {
        HandInput(
            primary = b and ButtonBits.ButtonA != 0,
            back = b and ButtonBits.ButtonB != 0,
            click = b and ButtonBits.ButtonThumbRClick != 0,
            up = b and ButtonBits.ButtonThumbRU != 0,
            down = b and ButtonBits.ButtonThumbRD != 0,
            left = b and ButtonBits.ButtonThumbRL != 0,
            right = b and ButtonBits.ButtonThumbRR != 0,
        )
      }

  /**
   * Hand tracking: the SDK's thumb microgestures (component [Microgestures] on the hand entities,
   * the same data its MicrogesturesSystem hands to listeners) as the controller inputs they stand
   * for. Untested on a device: if reading them throws, they are switched off for the run.
   */
  private fun microgestures(s: Snapshot): List<Fired> {
    if (microgesturesBroken) return emptyList()
    val out = ArrayList<Fired>(1)
    try {
      for (e in Query.where { has(Microgestures.id) }.eval()) {
        if (!e.isLocal()) continue
        val m = e.tryGetComponent<Microgestures>() ?: continue
        val rising = m.changedMicrogestures and m.microgestureState
        if (rising == 0) continue
        val type = e.tryGetComponent<AvatarAttachment>()?.type ?: continue
        val hand = if (type.startsWith("right")) Hand.RIGHT else if (type.startsWith("left")) Hand.LEFT else continue
        for ((mask, g) in gestureMasks(hand)) if (rising and mask != 0) out += core.microgesture(g, hand, s)
      }
    } catch (t: Throwable) {
      microgesturesBroken = true
      Log.w(TAG, "microgestures unavailable, hand shortcuts are off", t)
    }
    return out
  }

  private fun gestureMasks(hand: Hand): List<Pair<Int, ControlScheme.Microgesture>> {
    val b = MicrogestureBits
    return if (hand == Hand.LEFT) {
      listOf(
          b.LeftMicrogestureTapThumb to ControlScheme.Microgesture.TAP,
          b.LeftMicrogestureSwipeLeft to ControlScheme.Microgesture.SWIPE_LEFT,
          b.LeftMicrogestureSwipeRight to ControlScheme.Microgesture.SWIPE_RIGHT,
          b.LeftMicrogestureSwipeForward to ControlScheme.Microgesture.SWIPE_FORWARD,
          b.LeftMicrogestureSwipeBack to ControlScheme.Microgesture.SWIPE_BACK,
      )
    } else {
      listOf(
          b.RightMicrogestureTapThumb to ControlScheme.Microgesture.TAP,
          b.RightMicrogestureSwipeLeft to ControlScheme.Microgesture.SWIPE_LEFT,
          b.RightMicrogestureSwipeRight to ControlScheme.Microgesture.SWIPE_RIGHT,
          b.RightMicrogestureSwipeForward to ControlScheme.Microgesture.SWIPE_FORWARD,
          b.RightMicrogestureSwipeBack to ControlScheme.Microgesture.SWIPE_BACK,
      )
    }
  }

  private companion object {
    const val TAG = "TroveInput"
    const val POINTER_LOG_MS = 500L
  }
}

/** What the router tells HoldSystem each frame: the stick that resizes (up / down) and pitches (left / right) the panel in the other hand. */
object RouterFrame {
  @Volatile var sizeFrom: Hand? = null

  @Volatile var sizeDir: Int = 0

  /** The same stick left / right (+1 right): tips the held screen when Face me is off. */
  @Volatile var pitchDir: Int = 0

  /** Seconds the pitch push has run (for the ease-in). */
  @Volatile var pitchHeldS: Float = 0f
}
