@file:Suppress("DEPRECATION") // GrabbableSystem: deprecated for the ISDK, but it is what SIMPLE_CONTROLLER uses

package app.trove.quest.hold

import android.util.Log
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trove.quest.input.Hand
import app.trove.quest.input.RouterFrame
import app.trove.quest.layout.Facing
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBaseWithLateExecute
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableSystem
import com.meta.spatial.toolkit.Transform
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * A panel the thumbstick can adjust while it is held with grip (README "Moving panels"). Its
 * [entity] must be a root entity (world Transform) with an enabled `Grabbable(PIVOT_Y)`.
 */
abstract class HoldTarget {
  /** The grabbed entity right now, or null when this target is not in use (e.g. no sphere open). */
  abstract val entity: Entity?

  /** Size range for [size] (screen width in metres, or a scale factor). */
  abstract val sizeRange: ClosedFloatingPointRange<Float>

  /** Current size; setting it applies it (Scale component, re-anchored children). */
  abstract var size: Float

  /** Tilt in degrees, + = clockwise as seen by the user. The system writes it into the Transform. */
  var rollDeg: Float = 0f

  /**
   * Held with grip right now (Compose state). The panel's own scrolling reads it: the SDK also turns
   * the stick into panel scroll events, so a held wall scrolled its grid while the stick pushed,
   * pulled or tilted it.
   */
  var held by mutableStateOf(false)
    internal set

  /**
   * Pitch in degrees, + = the panel faces up and away (it is overhead, tipped down at the face).
   * With Face me on it is worked out from the head ([faceHead]) and the stick doesn't change it;
   * with Face me off the other hand's stick left / right tips it by hand ([RouterFrame.pitchDir]),
   * and posture layouts set it too. A grab keeps it.
   */
  var pitchDeg: Float = 0f

  /** Flat screens only: they have a pitch the stick may change (wall and sphere controls keep theirs). */
  open val hasPitch: Boolean
    get() = false

  /**
   * Face me: the head position while this panel is kept square to the eyes (yaw and pitch from the
   * direction head -> panel, every frame while held), or null when it keeps the pitch it has.
   */
  open fun faceHead(): Vector3? = null

  /** Transient hint for the panel's own UI ([HoldHint]). */
  open fun hint(text: String) {}

  /** Let go: a good moment to save. */
  open fun released() {}
}

/**
 * True while the panel this composition belongs to is held with grip: scrollable containers pass
 * `!LocalPanelHeld.current` as their scroll-enabled flag so the stick moves the panel, not the list.
 */
val LocalPanelHeld = compositionLocalOf { false }

/**
 * Any panel held with grip right now (Compose state). The other stick may point at another panel
 * while one hand holds, so panels also stop scrolling while anything is held (control scheme: the
 * stick is the hold's, or the resize stick, never a scroll).
 */
object HoldState {
  var any by mutableStateOf(false)
}

/** A short-lived line of text a panel shows while it is held (wall pill, control panel tag). */
class HoldHint {
  var text by mutableStateOf<String?>(null)
    private set

  /** Bumped on every show, so the hide timer restarts. */
  var seq by mutableIntStateOf(0)
    private set

  fun show(t: String) {
    text = t
    seq++
  }

  fun clear(at: Int) {
    if (at == seq) text = null
  }

  companion object {
    const val SHOW_MS = 2500L
    const val GRAB_HINT = "Stick ↑↓ distance · ←→ tilt · other stick: size · B: put back"

    /** Face me off: the other stick's ←→ also tips the screen. */
    const val GRAB_HINT_PITCH = "Stick ↑↓ distance · ←→ tilt · other stick: ↑↓ size, ←→ pitch · B: put back"

    /** The other stick pushed ←→ while Face me is on. */
    const val FACE_ME_HINT = "Face me is on: pitch follows your head (turn it off to tip by hand)"
  }
}

/**
 * Thumbstick while gripping: up/down pushes/pulls or resizes (a press of the stick toggles which),
 * left/right tilts. The other hand's stick up/down resizes without a mode and its left/right
 * pitches the screen when Face me is off ([RouterFrame]); B / Y
 * puts the panel back where it was picked up ([putBack]). The stick press and B / Y arrive from
 * `InputRouter` (one reader of those buttons); the stick directions are read here, for the holding
 * hand. Nothing changes while nothing is held.
 *
 * Coexisting with the SDK's [GrabbableSystem] (SIMPLE_CONTROLLER input), which every frame moves a
 * held entity towards `controller pose * (0, 0, grabbedDistance) - yawOnly * grabbedLocalOffset`
 * with a yaw-only rotation facing the user:
 * - **Tilt**: [lateExecute] runs after every system's execute, so after the grab; it keeps the
 *   grab's facing (the forward axis, which a roll about that axis does not change) and puts the roll
 *   back on top. Not held, nothing touches the Transform, so the tilt stays.
 * - **Pitch** (a lying-back or overhead screen is tipped towards the face): the grab's facing is a
 *   yaw, so only the horizontal part of the forward axis is kept ([level]) and the target's
 *   [HoldTarget.pitchDeg] goes on top of it before the roll: yaw, then pitch, then roll. The grab
 *   offset is rotated by the whole tilt (pitch and roll) for the same reason as the roll.
 * - **Face me** ([HoldTarget.faceHead] non-null): each frame, after the grab, the facing is worked
 *   out from the head instead: yaw and pitch of the direction head -> panel ([applyFacing]), the roll
 *   on top, and the target's pitch follows, so a screen held high or low tips towards you as it
 *   moves, and on release ([end]) it is square to the eyes where it ended up. It only replaces the
 *   rotation the grab wrote; position, GrabInfo and the offset fix are untouched, so none of the
 *   swoop cases above come back. With Face me off the other hand's stick left / right changes the
 *   pitch ([RouterFrame.pitchDir]) and the rest is as before.
 * - **Distance**: the grab's private `GrabInfo` (distance along the ray, offset of the grab point)
 *   is replaced through reflection with the new distance, so the grab itself carries the panel
 *   further or closer and never snaps it back.
 * - **Grab start on a tilted panel**: the grab measured the grab point in the tilted frame but
 *   applies it with a level rotation, which would make the panel jump; the offset is rotated by the
 *   tilt once per `GrabInfo`. The grab can write a fresh `GrabInfo` during a hold (measured tilted
 *   again); 0.2.2 fixed only the first one, and the panel swooped sideways to the wrong spot.
 * - **Size**: a Scale component, which the grab ignores (it only writes Transform).
 * If the reflection fails (another SDK version), tilt and size still work and distance is off.
 */
class HoldSystem : SystemBaseWithLateExecute() {
  private val targets = mutableListOf<HoldTarget>()
  private val holds = HashMap<HoldTarget, Hold>()
  private var lastNs = System.nanoTime()
  private var grabAccess: GrabAccess? = null
  private var grabAccessTried = false

  /** Where a target was while nobody held it: what B / Y puts back. */
  private class Rest(val pose: Pose, val size: Float, val roll: Float)

  private val rest = HashMap<HoldTarget, Rest>()

  private class Hold(val controller: Entity?, val right: Boolean, val rest: Rest?) {
    var mode = StickMode.DISTANCE
    var vDir = 0
    var vHeld = 0f
    var sizeDir = 0
    var sizeHeld = 0f
    val gate = AxisGate()

    /** Put back: the panel stays at this pose until grip is let go (the grab no longer moves it). */
    var pinned: Pose? = null

    /** The GrabInfo we last fixed or wrote; a different one is new from the grab and needs fixing. */
    var knownInfo: Any? = null
    var lastCenter: Vector3? = null
    var lastLogMs = 0L
    var lastFaceHintMs = 0L
  }

  /** True while any target is held with grip (look-to-focus waits for the hands to be free). */
  val holding: Boolean
    get() = holds.isNotEmpty()

  /** The hand that holds a panel (the first, if both do), or null. */
  val holdingHand: Hand?
    get() = holds.values.firstOrNull()?.let { if (it.right) Hand.RIGHT else Hand.LEFT }

  /** Stick press of the gripping [hand]: distance <-> size. */
  fun toggleMode(hand: Hand) {
    for ((t, h) in holds) if (h.right == (hand == Hand.RIGHT)) {
      h.mode = h.mode.toggled()
      t.hint(h.mode.hint)
      if (debug) Log.d(TAG, "mode -> ${h.mode}")
    }
  }

  /**
   * B / Y while [hand] holds a panel: it goes back to where, how big and how tilted it was when the
   * grab started, and stays there until grip is let go. (Ending the grab itself is not an SDK
   * call, so the grab is left running and its result overwritten every frame: docs R5.)
   */
  fun putBack(hand: Hand) {
    for ((t, h) in holds) if (h.right == (hand == Hand.RIGHT)) {
      val r = h.rest ?: continue
      t.size = r.size
      t.rollDeg = r.roll
      h.pinned = r.pose
      t.entity?.setComponent(Transform(r.pose))
      t.hint("Put back")
    }
  }

  /** Debug builds log what the stick does while held, ~2 per second (logcat TroveHold). */
  var debug = false

  fun register(t: HoldTarget) {
    if (t !in targets) targets += t
  }

  fun unregister(t: HoldTarget) {
    targets -= t
    holds.remove(t)
    rest.remove(t)
    t.held = false
  }

  private fun access(): GrabAccess? {
    if (!grabAccessTried) {
      grabAccessTried = true
      grabAccess =
          try {
            GrabAccess(systemManager.findSystem<GrabbableSystem>())
          } catch (e: Throwable) {
            Log.w(TAG, "no access to GrabbableSystem internals: push/pull is off", e)
            null
          }
    }
    return grabAccess
  }

  /** Runs in the system order, possibly before the grab: fix a new grab's offset as early as we can. */
  override fun execute() {
    val acc = access() ?: return
    val held = acc.grabbers() // one pass over the SDK's map per frame, not one per target (review T1)
    for (t in targets) {
      val e = t.entity ?: continue
      val grabber = held[e]
      if (grabber == null) {
        // Not held: remember where it is, for the put back of the next grab.
        e.tryGetComponent<Transform>()?.transform?.let { rest[t] = Rest(it, t.size, t.rollDeg) }
        continue
      }
      val h = holds.getOrPut(t) { startHold(t, grabber) }
      fixOffset(acc, h, t)
    }
  }

  override fun lateExecute() {
    val now = System.nanoTime()
    val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
    lastNs = now
    val acc = access()
    val held = acc?.grabbers() ?: emptyMap()
    for (t in targets.toList()) {
      val e = t.entity
      val grabber = e?.let { if (acc != null) held[it] else if (isGrabbed(it)) fallbackController() else null }
      var h = holds[t]
      if (e == null || (grabber == null && (acc != null || !isGrabbed(e)))) {
        if (h != null) end(t)
        continue
      }
      if (h == null) h = startHold(t, grabber).also { holds[t] = it }
      if (acc != null) fixOffset(acc, h, t)
      step(t, e, h, acc, dt)
    }
    val any = holds.isNotEmpty()
    if (HoldState.any != any) HoldState.any = any
  }

  private fun startHold(t: HoldTarget, controller: Entity?): Hold {
    val right =
        controller?.tryGetComponent<AvatarAttachment>()?.type?.startsWith("right")
            ?: ((controller?.tryGetComponent<Controller>()?.buttonState ?: 0) and ButtonBits.ButtonSqueezeR != 0)
    t.hint(if (t.faceHead() == null && t.hasPitch) HoldHint.GRAB_HINT_PITCH else HoldHint.GRAB_HINT)
    t.held = true
    return Hold(controller, right, rest[t])
  }

  private fun end(t: HoldTarget) {
    holds.remove(t)
    t.held = false
    rest[t]?.let { rest[t] = Rest(t.entity?.tryGetComponent<Transform>()?.transform ?: it.pose, t.size, t.rollDeg) }
    val snapped = HoldRules.snap(t.rollDeg)
    if (snapped != t.rollDeg) {
      t.rollDeg = snapped
      t.entity?.let { applyTilt(it, t.pitchDeg, snapped) }
    }
    // Let go: square to the eyes where it ended up.
    t.faceHead()?.let { head -> t.entity?.let { applyFacing(it, t, head) } }
    t.released()
  }

  private fun step(t: HoldTarget, e: Entity, h: Hold, acc: GrabAccess?, dt: Float) {
    h.pinned?.let {
      e.setComponent(Transform(it)) // put back: the grab keeps writing the hand's pose; overwrite it
      return
    }
    val c = h.controller?.tryGetComponent<Controller>() ?: return
    val bits = c.buttonState
    val pressed = c.changedButtons and bits
    val r = h.right
    val up = bits and (if (r) ButtonBits.ButtonThumbRU else ButtonBits.ButtonThumbLU) != 0
    val down = bits and (if (r) ButtonBits.ButtonThumbRD else ButtonBits.ButtonThumbLD) != 0
    val right = bits and (if (r) ButtonBits.ButtonThumbRR else ButtonBits.ButtonThumbLR) != 0
    val left = bits and (if (r) ButtonBits.ButtonThumbRL else ButtonBits.ButtonThumbLL) != 0
    val nowMs = System.currentTimeMillis()
    // The stick press (distance <-> size) and B / Y (put back) come from InputRouter: toggleMode / putBack.
    val hz = HoldRules.axis(right, left)
    h.gate.step(HoldRules.vertical(up, down), hz, dt)
    val v = h.gate.v
    h.vHeld = if (v != 0 && v == h.vDir) h.vHeld + dt else 0f
    h.vDir = v

    // The other hand's stick up / down resizes, with no mode (RouterFrame); a hand that holds its own panel is not that stick.
    val sizeHand = RouterFrame.sizeFrom
    val otherOk = sizeHand != null && (sizeHand == Hand.RIGHT) != r && holds.values.none { it.right == (sizeHand == Hand.RIGHT) }
    val otherDir = if (otherOk) RouterFrame.sizeDir else 0
    h.sizeHeld = if (otherDir != 0 && otherDir == h.sizeDir) h.sizeHeld + dt else 0f
    h.sizeDir = otherDir
    if (otherDir != 0) t.size = HoldRules.zoom(t.size, otherDir, dt, HoldRules.ramp(h.sizeHeld + dt), t.sizeRange)

    var pushed: Push? = null
    if (v != 0) {
      val ramp = HoldRules.ramp(h.vHeld + dt)
      when (h.mode) {
        StickMode.SIZE -> t.size = HoldRules.zoom(t.size, v, dt, ramp, t.sizeRange)
        StickMode.DISTANCE -> if (acc != null && h.controller != null) pushed = push(acc, h, e, v, dt, ramp)
      }
    }
    if (debug && (bits and STICK_BITS != 0 || pressed != 0)) {
      if (nowMs - h.lastLogMs >= LOG_EVERY_MS) {
        h.lastLogMs = nowMs
        Log.d(
            TAG,
            "held ${if (r) "R" else "L"} bits=0x${Integer.toHexString(bits)} U=$up D=$down L=$left R=$right " +
                "v=$v hz=$hz tilt=${h.gate.tilt} mode=${h.mode} size=${"%.2f".format(t.size)} roll=${"%.1f".format(t.rollDeg)} " +
                (pushed?.let { "headDist=${"%.2f".format(it.headDist)} rayDist=${"%.2f".format(it.rayDist)} delta=${"%.4f".format(it.delta)}" }
                    ?: "access=${acc != null}"))
      }
    }
    var rollChanged = false
    if (h.gate.tilt != 0) {
      t.rollDeg = HoldRules.roll(t.rollDeg, h.gate.tilt, dt, HoldRules.ramp(h.gate.tiltS))
      rollChanged = true
    }
    // The other hand's stick left / right tips the screen, when Face me is not doing it.
    val head = t.faceHead()
    val pitchDir = if (otherOk) RouterFrame.pitchDir else 0
    if (pitchDir != 0) {
      if (head != null) {
        if (nowMs - h.lastFaceHintMs > FACE_HINT_EVERY_MS) {
          h.lastFaceHintMs = nowMs
          t.hint(HoldHint.FACE_ME_HINT)
        }
      } else if (t.hasPitch) {
        t.pitchDeg = HoldRules.pitch(t.pitchDeg, pitchDir, dt, HoldRules.ramp(RouterFrame.pitchHeldS))
        rollChanged = true
      }
    }
    // The grab wrote a level, yaw-only rotation this frame. Face me: square to the eyes (yaw and pitch
    // from the head, the roll on top). Otherwise put the pitch and the tilt back on top of its facing.
    if (head != null) applyFacing(e, t, head)
    else if (rollChanged || t.rollDeg != 0f || t.pitchDeg != 0f) applyTilt(e, t.pitchDeg, t.rollDeg)
    if (debug) logJump(e, h)
  }

  /** Debug: a panel centre moving more than [JUMP_M] in one frame is the "swoop"; log what was going on. */
  private fun logJump(e: Entity, h: Hold) {
    val c = e.tryGetComponent<Transform>()?.transform?.t ?: return
    val last = h.lastCenter
    h.lastCenter = c
    if (last != null && c.distanceTo(last) > JUMP_M) {
      Log.d(TAG, "jump ${"%.2f".format(c.distanceTo(last))} m: v=${h.gate.v} tilt=${h.gate.tilt} mode=${h.mode}")
    }
  }

  private class Push(val headDist: Float, val rayDist: Float, val delta: Float)

  private fun push(acc: GrabAccess, h: Hold, e: Entity, dir: Int, dt: Float, ramp: Float): Push? {
    val controller = h.controller ?: return null
    val info = acc.info(controller) ?: return null
    val head = systemManager.getScene().getViewerPose().t
    val center = e.tryGetComponent<Transform>()?.transform?.t ?: return null
    val headDist = center.distanceTo(head)
    // The larger of the two, so a wrong head pose can never block a pull (the ray distance is a floor).
    val d = HoldRules.pushDelta(maxOf(headDist, info.distance), dir, dt, ramp)
    if (d != 0f) h.knownInfo = acc.replace(controller, info.copy(distance = (info.distance + d).coerceAtLeast(0.05f)))
    return Push(headDist, info.distance, d)
  }

  /**
   * The grab point was measured in the tilted frame; the grab applies it level. Rotate it once for
   * every GrabInfo the grab writes (the first at grab start, and any it writes again mid-hold).
   */
  private fun fixOffset(acc: GrabAccess, h: Hold, t: HoldTarget) {
    val controller = h.controller ?: return
    val raw = acc.raw(controller) ?: return
    if (raw === h.knownInfo) return
    h.knownInfo = raw
    if (t.rollDeg == 0f && t.pitchDeg == 0f) return
    val info = acc.info(controller) ?: return
    if (debug && h.lastCenter != null) Log.d(TAG, "grab wrote a new GrabInfo mid-hold; fixing its offset")
    h.knownInfo = acc.replace(controller, info.copy(offset = tiltQuat(t.pitchDeg, t.rollDeg).times(info.offset)))
  }

  private fun isGrabbed(e: Entity): Boolean = e.tryGetComponent<Grabbable>()?.isGrabbed == true

  /** Without the grab internals: the first active local controller with grip held. */
  private fun fallbackController(): Entity? =
      Query.where { has(Controller.id) }
          .eval()
          .filter { it.isLocal() }
          .firstOrNull {
            val c = it.tryGetComponent<Controller>()
            c != null && c.isActive && (c.buttonState and (ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR)) != 0
          }

  companion object {
    private const val TAG = "TroveHold"
    private const val LOG_EVERY_MS = 500L
    private const val JUMP_M = 0.25f
    private const val FACE_HINT_EVERY_MS = 4000L
    private val STICK_BITS =
        ButtonBits.ButtonThumbLU or ButtonBits.ButtonThumbLD or ButtonBits.ButtonThumbLL or ButtonBits.ButtonThumbLR or
            ButtonBits.ButtonThumbRU or ButtonBits.ButtonThumbRD or ButtonBits.ButtonThumbRL or ButtonBits.ButtonThumbRR
    private val FORWARD = Vector3(0f, 0f, 1f)
    private val Z_AXIS = Vector3(0f, 0f, 1f)

    /** Rotation for a tilt of [deg] clockwise as the user sees it (local +z points away from them). */
    fun rollQuat(deg: Float): Quaternion = Quaternion.fromAxisAngle(Z_AXIS, -deg)

    private val X_AXIS = Vector3(1f, 0f, 0f)

    /** Rotation for a pitch of [deg] up (the panel's forward axis tips up; it is a turn about the right axis). */
    fun pitchQuat(deg: Float): Quaternion = Quaternion.fromAxisAngle(X_AXIS, -deg)

    /** Pitch, then roll: the turn a level panel gets for its pitch and tilt. */
    fun tiltQuat(pitchDeg: Float, rollDeg: Float): Quaternion = pitchQuat(pitchDeg).times(rollQuat(rollDeg))

    /** A panel's rotation without its tilt: yaw only, facing the same way. */
    fun level(q: Quaternion): Quaternion = Quaternion.lookRotationAroundY(q.times(FORWARD))

    /** [level] rotation with a tilt of [deg] on top. */
    fun tilted(level: Quaternion, deg: Float): Quaternion = if (deg == 0f) level else level.times(rollQuat(deg))

    /** [level] rotation with a pitch of [pitchDeg] and then a tilt of [rollDeg] on top. */
    fun tilted(level: Quaternion, pitchDeg: Float, rollDeg: Float): Quaternion =
        if (pitchDeg == 0f) tilted(level, rollDeg) else level.times(tiltQuat(pitchDeg, rollDeg))

    /** A level rotation facing [yawDeg] (0 = +z, + towards +x). */
    fun levelYaw(yawDeg: Float): Quaternion {
      val r = Math.toRadians(yawDeg.toDouble())
      return Quaternion.lookRotationAroundY(Vector3(Math.sin(r).toFloat(), 0f, Math.cos(r).toFloat()))
    }

    /** The yaw a rotation faces, degrees (the horizontal part of its forward axis). */
    fun yawDegOf(q: Quaternion): Float {
      val f = q.times(FORWARD)
      return Math.toDegrees(Math.atan2(f.x.toDouble(), f.z.toDouble())).toFloat()
    }

    /** Pose without tilt (what layouts save, together with the tilt). */
    fun levelPose(p: Pose): Pose = Pose(p.t, level(p.q))

    /**
     * Face me: [e] turned so its forward axis points from [head] to its centre (yaw and pitch, the
     * target's pitch is updated), then its roll about that axis. The position is the grab's.
     */
    fun applyFacing(e: Entity, t: HoldTarget, head: Vector3) {
      val p = e.tryGetComponent<Transform>()?.transform ?: return
      val dx = p.t.x - head.x
      val dy = p.t.y - head.y
      val dz = p.t.z - head.z
      if (dx * dx + dy * dy + dz * dz < 1e-4f) return
      t.pitchDeg = Facing.pitchDeg(dx, dy, dz)
      e.setComponent(Transform(Pose(p.t, tilted(levelYaw(Facing.yawDeg(dx, dz)), t.pitchDeg, t.rollDeg))))
    }

    /** Sets [e]'s rotation to its own facing plus a pitch of [pitchDeg] and a tilt of [rollDeg]. */
    fun applyTilt(e: Entity, pitchDeg: Float, rollDeg: Float) {
      val p = e.tryGetComponent<Transform>()?.transform ?: return
      e.setComponent(Transform(Pose(p.t, tilted(level(p.q), pitchDeg, rollDeg))))
    }
  }
}

/**
 * Reflection into [GrabbableSystem]'s private grab state (Spatial SDK 0.14): the map from the
 * gripping controller to its `GrabInfo(grabbedEntity, grabbedDistance, grabbedLocalOffset,
 * grabbedFacing)`. GrabInfo is immutable, so a change replaces the map entry with a new one.
 */
private class GrabAccess(private val system: GrabbableSystem) {
  data class Info(val entity: Entity, val distance: Float, val offset: Vector3, val facing: Float)

  private val infoField: Field = GrabbableSystem::class.java.getDeclaredField("grabbingInfo_").apply { isAccessible = true }
  private val infoClass: Class<*> = Class.forName("com.meta.spatial.toolkit.GrabInfo")
  private val getEntity: Method = infoClass.getDeclaredMethod("getGrabbedEntity").apply { isAccessible = true }
  private val getDistance: Method = infoClass.getDeclaredMethod("getGrabbedDistance").apply { isAccessible = true }
  private val getOffset: Method = infoClass.getDeclaredMethod("getGrabbedLocalOffset").apply { isAccessible = true }
  private val getFacing: Method = infoClass.getDeclaredMethod("getGrabbedFacing").apply { isAccessible = true }
  private val ctor: Constructor<*> =
      infoClass
          .getDeclaredConstructor(Entity::class.java, Float::class.javaPrimitiveType, Vector3::class.java, Float::class.javaPrimitiveType)
          .apply { isAccessible = true }

  init {
    map() // fail now (and fall back) rather than every frame
  }

  @Suppress("UNCHECKED_CAST")
  private fun map(): HashMap<Entity, Any> = infoField.get(system) as HashMap<Entity, Any>

  /** Grabbed entity -> the controller holding it, in one walk of the SDK's map (empty and allocation-free when nothing is grabbed). */
  fun grabbers(): Map<Entity, Entity> {
    val m = map()
    if (m.isEmpty()) return emptyMap()
    val out = HashMap<Entity, Entity>(m.size * 2)
    for ((controller, info) in m) (getEntity.invoke(info) as? Entity)?.let { out[it] = controller }
    return out
  }

  /** The GrabInfo object itself (compare by identity to spot a new one). */
  fun raw(controller: Entity): Any? = map()[controller]

  fun info(controller: Entity): Info? {
    val raw = map()[controller] ?: return null
    return Info(
        getEntity.invoke(raw) as Entity,
        getDistance.invoke(raw) as Float,
        getOffset.invoke(raw) as Vector3,
        getFacing.invoke(raw) as Float,
    )
  }

  /** Swaps in a GrabInfo built from [info] and returns it (null when the grab has ended). */
  fun replace(controller: Entity, info: Info): Any? {
    val m = map()
    if (controller !in m) return null
    return ctor.newInstance(info.entity, info.distance, info.offset, info.facing).also { m[controller] = it }
  }
}
