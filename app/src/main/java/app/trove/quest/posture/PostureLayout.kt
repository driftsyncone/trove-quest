package app.trove.quest.posture

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** How the wearer is lying or sitting: [PostureLayout] places the screens for it. */
enum class Posture(val id: String, val label: String) {
  SIT("sit", "Sitting"),
  LIE_BACK("lie", "Lying back"),
  SIDE_LEFT("left", "Left side"),
  SIDE_RIGHT("right", "Right side");

  companion object {
    fun of(id: String?): Posture = entries.firstOrNull { it.id == id } ?: SIT
  }
}

/** A point or direction in world metres (y up; yaw 0 looks along +z, + turns towards +x). */
data class Vec(val x: Float, val y: Float, val z: Float) {
  operator fun plus(o: Vec) = Vec(x + o.x, y + o.y, z + o.z)
  operator fun minus(o: Vec) = Vec(x - o.x, y - o.y, z - o.z)
  operator fun times(k: Float) = Vec(x * k, y * k, z * k)
  fun dot(o: Vec) = x * o.x + y * o.y + z * o.z
  val length: Float
    get() = sqrt(dot(this))
}

/**
 * Where a screen goes and how it is turned, as three angles in the order the app applies them
 * (yaw about the vertical, then pitch about the screen's own right axis, then roll about its own
 * forward axis). [pitchDeg] > 0: the screen faces up and away (its top tips back, as when you look up
 * at it); [rollDeg] > 0: clockwise as you look at it, like HoldSystem's tilt. The saved layout keeps
 * yaw in the pose and these two as tilt.
 */
data class Placed(val pos: Vec, val yawDeg: Float, val pitchDeg: Float, val rollDeg: Float)

/** What the layout needs of a screen: its width and how far from the head it should be. */
data class ScreenSlot(val widthM: Float, val distM: Float)

/**
 * Posture layouts (docs/vr-hook-ideas.md S2), free of Spatial SDK types so they can be unit tested.
 *
 * Every posture starts from the same sitting arc: screens side by side round the head at eye level,
 * the focused one straight ahead. A posture then turns that whole arc about the head, which keeps
 * every screen facing the face:
 * - lying back: about the head's right axis by [LIE_ELEVATION_DEG], so the arc is overhead and each
 *   screen pitched down at you (the top of the picture towards the head of the bed);
 * - on your side: about the head's forward axis by +-90 degrees, so the arc runs up and down and each
 *   screen is rolled with your head.
 */
object PostureLayout {
  /** Lying back: where straight ahead goes, degrees above the horizon (neutral neck on a pillow). */
  const val LIE_ELEVATION_DEG = 70f

  /** Gap between neighbouring screens on the arc, degrees. */
  const val GAP_DEG = 4f

  /** The arc never spreads further than this to each side. */
  const val MAX_SPREAD_DEG = 170f

  private fun rad(d: Float) = (d * PI / 180.0).toFloat()

  private fun deg(r: Float) = (r * 180.0 / PI).toFloat()

  fun wrap(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
  }

  /** Direction of a look: yaw 0 = +z, + towards +x; pitch + = up. */
  fun direction(yawDeg: Float, pitchDeg: Float): Vec {
    val y = rad(yawDeg)
    val p = rad(pitchDeg)
    return Vec(cos(p) * sin(y), sin(p), cos(p) * cos(y))
  }

  fun yawOf(v: Vec): Float = deg(atan2(v.x, v.z))

  fun pitchOf(v: Vec): Float {
    val len = v.length
    return if (len < 1e-6f) 0f else deg(asin((v.y / len).coerceIn(-1f, 1f)))
  }

  /**
   * The yaw the wearer's body faces, from the head's [forward] and [up] vectors. Looking roughly
   * level that is the look direction; lying on the back (or face down) the look direction is
   * vertical, and the body axis is the top of the head: feet are the other way round, so "ahead" is
   * away from where the head's top points. Null when neither says (looking straight up with the
   * head's top vertical too, which cannot happen on a real head).
   */
  fun bodyYawDeg(forward: Vec, up: Vec): Float? {
    val fh = sqrt(forward.x * forward.x + forward.z * forward.z)
    if (fh >= 0.5f * forward.length) return yawOf(Vec(forward.x, 0f, forward.z))
    val uh = sqrt(up.x * up.x + up.z * up.z)
    if (uh < 0.1f) return if (fh > 1e-3f) yawOf(Vec(forward.x, 0f, forward.z)) else null
    // Face up: ahead = away from the head's top. Face down: ahead = the head's top.
    val s = if (forward.y > 0f) -1f else 1f
    return yawOf(Vec(up.x * s, 0f, up.z * s))
  }

  /** Screen indices from left to right: [centre] in the middle, the others alternating right, left, ... in index order. */
  fun arrangement(n: Int, centre: Int): List<Int> {
    if (n <= 0) return emptyList()
    val c = centre.coerceIn(0, n - 1)
    val left = ArrayDeque<Int>()
    val right = ArrayList<Int>()
    var toRight = true
    for (i in 0 until n) {
      if (i == c) continue
      if (toRight) right += i else left.addFirst(i)
      toRight = !toRight
    }
    return left + c + right
  }

  /** Angular width of a screen seen from its distance, degrees. */
  fun angularWidthDeg(widthM: Float, distM: Float): Float = deg(2f * atan(widthM / 2f / distM.coerceAtLeast(0.1f)))

  /**
   * Yaw of each screen on the sitting arc (degrees, + = right of straight ahead), indexed like [slots].
   * Neighbours touch with [GAP_DEG] between them; the whole arc is shifted so [centre] is at 0.
   */
  fun slotYaws(slots: List<ScreenSlot>, centre: Int): List<Float> {
    val order = arrangement(slots.size, centre)
    val yaws = FloatArray(slots.size)
    var cursor = 0f
    var centreAt = 0f
    for ((k, i) in order.withIndex()) {
      val w = angularWidthDeg(slots[i].widthM, slots[i].distM)
      yaws[i] = cursor + w / 2f
      if (i == centre.coerceIn(0, slots.size - 1)) centreAt = yaws[i]
      cursor += w + GAP_DEG
    }
    return List(slots.size) { (yaws[it] - centreAt).coerceIn(-MAX_SPREAD_DEG, MAX_SPREAD_DEG) }
  }

  /**
   * Where each screen goes for [posture] with the head at [head] and the body facing [bodyYawDeg].
   * [centre] is the screen straight ahead (the focused one).
   */
  fun place(posture: Posture, head: Vec, bodyYawDeg: Float, slots: List<ScreenSlot>, centre: Int): List<Placed> {
    val yaws = slotYaws(slots, centre)
    return slots.indices.map { placeOne(posture, head, bodyYawDeg, yaws[it], slots[it].distM) }
  }

  /** One screen at [slotYawDeg] (and [slotElevDeg] above the horizon, a row of Tidy's grid) on the arc [distM] away. */
  fun placeOne(posture: Posture, head: Vec, bodyYawDeg: Float, slotYawDeg: Float, distM: Float, slotElevDeg: Float = 0f): Placed {
    // The sitting frame of the screen, in head coordinates (x right, y up, z ahead).
    val a = rad(slotYawDeg)
    val e = rad(slotElevDeg)
    var fwd = Vec(cos(e) * sin(a), sin(e), cos(e) * cos(a))
    var right = Vec(cos(a), 0f, -sin(a))
    var up = Vec(-sin(e) * sin(a), cos(e), -sin(e) * cos(a))
    fwd = turn(posture, fwd)
    right = turn(posture, right)
    up = turn(posture, up)
    // Head coordinates -> world, for a body facing bodyYawDeg.
    val h = rad(bodyYawDeg)
    val hr = Vec(cos(h), 0f, -sin(h))
    val hf = Vec(sin(h), 0f, cos(h))
    fun world(v: Vec) = hr * v.x + Vec(0f, v.y, 0f) + hf * v.z
    val f = world(fwd)
    val r = world(right)
    val u = world(up)
    return Placed(head + f * distM, yawOf(f), pitchOf(f), rollOf(f, r, u))
  }

  /** The posture's turn of the whole arc about the head, on a vector in head coordinates. */
  private fun turn(p: Posture, v: Vec): Vec =
      when (p) {
        Posture.SIT -> v
        Posture.LIE_BACK -> {
          // About the head's right axis: ahead goes up, up goes back.
          val e = rad(LIE_ELEVATION_DEG)
          Vec(v.x, v.y * cos(e) + v.z * sin(e), v.z * cos(e) - v.y * sin(e))
        }
        // Head tipped to the left: right ear up, the head's top towards the world's left.
        Posture.SIDE_LEFT -> Vec(-v.y, v.x, v.z)
        Posture.SIDE_RIGHT -> Vec(v.y, -v.x, v.z)
      }

  /**
   * Roll (clockwise as you look at it) of a screen with the given axes: the angle between its right
   * axis and the horizontal right of the same facing.
   */
  private fun rollOf(fwd: Vec, right: Vec, up: Vec): Float {
    val y = rad(yawOf(fwd))
    val p = rad(pitchOf(fwd))
    val r0 = Vec(cos(y), 0f, -sin(y))
    return deg(atan2(up.dot(r0), right.dot(r0)))
  }

  // ---- "Bring screens to me" ----

  /** [p] turned about the vertical axis through [head] by [deltaDeg] (+ = towards +x, like yaw). */
  fun rotateAbout(head: Vec, p: Vec, deltaDeg: Float): Vec {
    val d = rad(deltaDeg)
    val x = p.x - head.x
    val z = p.z - head.z
    return Vec(head.x + x * cos(d) + z * sin(d), p.y, head.z + z * cos(d) - x * sin(d))
  }

  /**
   * The yaw the layout is centred on, seen from [head]: the mean direction of the screens (their
   * horizontal offsets added up, so a screen further out counts more, like it looks). Null when they
   * are all above the head, or cancel out (screens all round): the caller then keeps [fallback].
   */
  fun centreYawDeg(head: Vec, positions: List<Vec>): Float? {
    var sx = 0f
    var sz = 0f
    for (p in positions) {
      val d = p - head
      val len = sqrt(d.x * d.x + d.z * d.z)
      if (len < 0.2f) continue
      sx += d.x / len
      sz += d.z / len
    }
    if (sqrt(sx * sx + sz * sz) < 0.3f) return null
    return yawOf(Vec(sx, 0f, sz))
  }

  /** The turn that brings a layout centred on [centreYawDeg] to [targetYawDeg], in -180..180. */
  fun bringDelta(centreYawDeg: Float, targetYawDeg: Float): Float = wrap(targetYawDeg - centreYawDeg)

  /** [items] turned about [head] by [deltaDeg]: position and yaw move, pitch and roll stay. */
  fun turnAll(head: Vec, items: List<Placed>, deltaDeg: Float): List<Placed> =
      items.map { it.copy(pos = rotateAbout(head, it.pos, deltaDeg), yawDeg = wrap(it.yawDeg + deltaDeg)) }

  // ---- what a held screen keeps ----

  /**
   * A grab writes a yaw-only facing and drops pitch and roll; HoldSystem then puts the screen's own
   * pitch and roll back on top. The result: the grab's yaw, the screen's pitch and roll (a lying-back
   * screen stays tipped towards the face when it is moved).
   */
  fun afterGrab(before: Placed, grabYawDeg: Float, newPos: Vec): Placed = before.copy(pos = newPos, yawDeg = grabYawDeg)

  /** Degrees a screen's centre is [head]-relative: yaw, pitch and distance (for the console map). */
  fun bearing(head: Vec, p: Vec): Triple<Float, Float, Float> {
    val d = p - head
    return Triple(yawOf(d), pitchOf(d), d.length)
  }
}
