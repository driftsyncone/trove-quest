package app.trove.quest.layout

import app.trove.quest.posture.PostureLayout
import app.trove.quest.posture.Vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a screen faces when it is kept square to the eyes ("Face me"): its forward axis is the
 * direction from the head to its centre, so a screen placed high or low tips towards you. Yaw 0
 * looks along +z and + turns towards +x; pitch + = the screen faces up and away (it is above you,
 * tipped down at your face), the same convention as `PostureLayout` and `HoldSystem.pitchQuat`.
 */
object Facing {
  /** Pitch limit of a quad screen (PlayerSession keeps the same). */
  const val MAX_PITCH_DEG = 85f

  fun yawDeg(dx: Float, dz: Float): Float = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()

  fun pitchDeg(dx: Float, dy: Float, dz: Float): Float {
    val len = sqrt(dx * dx + dy * dy + dz * dz)
    if (len < 1e-4f) return 0f
    return Math.toDegrees(kotlin.math.asin((dy / len).toDouble().coerceIn(-1.0, 1.0))).toFloat().coerceIn(-MAX_PITCH_DEG, MAX_PITCH_DEG)
  }
}

/**
 * A screen as an angular rectangle on the shell round the head: the unit direction [dir] from the
 * head to its centre, its [distM], and its size in metres. Its angular size follows from the
 * distance (a far screen covers fewer degrees). [rollDeg] swaps width and height as the screen is
 * turned (a screen on its side covers a tall, narrow patch).
 */
data class Shell(val dir: Vec, val distM: Float, val widthM: Float, val heightM: Float, val rollDeg: Float = 0f) {
  private fun halfDeg(extentM: Float): Float = Math.toDegrees(atan(extentM / 2.0 / distM.coerceAtLeast(0.1f))).toFloat()

  /** Half the angular width, in the horizon frame (roll accounted for by the rectangle's bounding box). */
  val halfWidthDeg: Float
    get() {
      val r = Math.toRadians(rollDeg.toDouble())
      return (abs(cos(r)) * halfDeg(widthM) + abs(sin(r)) * halfDeg(heightM)).toFloat()
    }

  val halfHeightDeg: Float
    get() {
      val r = Math.toRadians(rollDeg.toDouble())
      return (abs(sin(r)) * halfDeg(widthM) + abs(cos(r)) * halfDeg(heightM)).toFloat()
    }

  val yawDeg: Float
    get() = PostureLayout.yawOf(dir)

  val elevDeg: Float
    get() = PostureLayout.pitchOf(dir)
}

/**
 * Screens that do not overlap and arrange themselves (README "Multi-screen", "No overlap"). Pure
 * geometry, no Spatial SDK types, unit tested ([SphereLayoutTest]); `ScreenArranger` applies it.
 *
 * The model: every screen lives on a sphere round the head. Two screens collide when their angular
 * rectangles overlap (plus a [GAP_DEG] margin) **and** they are less than [DEPTH_M] apart in
 * distance. A screen clearly nearer than another (by [DEPTH_M] or more) may sit in front of it, so
 * pulling a screen close to look at a detail doesn't shove the rest of the room around.
 */
object SphereLayout {
  /** Empty margin kept between two screens, degrees. */
  const val GAP_DEG = 2f

  /** Screens this much nearer / further than another do not collide with it (metres). */
  const val DEPTH_M = 0.5f

  /** A push goes this far past the exact overlap, so a settled pair is clear, not touching (degrees). */
  const val PUSH_MARGIN_DEG = 0.02f

  /** A shell is not pushed above / below this elevation (degrees): straight up has no yaw. */
  const val MAX_ELEV_DEG = 80f

  private fun rad(d: Float) = (d * PI / 180.0).toFloat()

  private fun deg(r: Float) = (r * 180.0 / PI).toFloat()

  private fun unit(v: Vec): Vec {
    val l = v.length
    return if (l < 1e-6f) Vec(0f, 0f, 1f) else v * (1f / l)
  }

  private fun cross(a: Vec, b: Vec) = Vec(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

  /** The horizon frame at [f]: right (horizontal) and up, both perpendicular to [f]. Straight up/down falls back to +x. */
  private fun frame(f: Vec): Pair<Vec, Vec> {
    val h = sqrt(f.x * f.x + f.z * f.z)
    val r = if (h < 1e-4f) Vec(1f, 0f, 0f) else Vec(f.z / h, 0f, -f.x / h)
    return r to cross(f, r)
  }

  /** Where [b] is in [a]'s frame, degrees right (+x) and up (+y) of a's centre; null when b is behind a. */
  fun offsetOf(a: Shell, b: Shell): Pair<Float, Float>? {
    val (r, u) = frame(a.dir)
    val dz = b.dir.dot(a.dir)
    if (dz <= 0.05f) return null
    return deg(atan2(b.dir.dot(r), dz)) to deg(atan2(b.dir.dot(u), dz))
  }

  /** How deep two screens overlap along each axis (degrees, both > 0 = collide), null when they don't collide at all. */
  fun penetration(a: Shell, b: Shell, gap: Float = GAP_DEG): Pair<Float, Float>? {
    if (abs(a.distM - b.distM) >= DEPTH_M) return null
    val (x, y) = offsetOf(a, b) ?: return null
    val px = a.halfWidthDeg + b.halfWidthDeg + gap - abs(x)
    val py = a.halfHeightDeg + b.halfHeightDeg + gap - abs(y)
    return if (px > 0f && py > 0f) px to py else null
  }

  fun overlaps(a: Shell, b: Shell, gap: Float = GAP_DEG): Boolean = penetration(a, b, gap) != null

  /** The direction [dir] turned by [angleDeg] towards the unit vector [axis] (perpendicular to it). */
  private fun turned(dir: Vec, axis: Vec, angleDeg: Float): Vec {
    val a = rad(angleDeg)
    // The axis belongs to the other screen's frame: keep only the part perpendicular to this direction.
    val perp = unit(axis - dir * axis.dot(dir))
    return clampElev(unit(dir * cos(a) + perp * sin(a)))
  }

  private fun clampElev(d: Vec): Vec {
    val e = PostureLayout.pitchOf(d)
    if (abs(e) <= MAX_ELEV_DEG) return d
    return PostureLayout.direction(PostureLayout.yawOf(d), e.coerceIn(-MAX_ELEV_DEG, MAX_ELEV_DEG))
  }

  /**
   * Pushes overlapping screens apart along the shell until none collide, moving as little as
   * possible. Each overlapping pair separates along the axis (across or up / down) it overlaps least
   * on, each side taking half; a screen in [pinned] (held in the hand) never moves and the other takes
   * the whole push. Returns the new directions, same order; distances never change. Gives up after
   * [iterations] sweeps (a crowded sphere), leaving the best it reached.
   */
  fun relax(shells: List<Shell>, pinned: Set<Int> = emptySet(), gap: Float = GAP_DEG, iterations: Int = 40): List<Shell> {
    val s = shells.toMutableList()
    repeat(iterations) {
      var worst = 0f
      for (i in s.indices) for (j in i + 1 until s.size) {
        val a = s[i]
        val b = s[j]
        val pen = penetration(a, b, gap) ?: continue
        val iPinned = i in pinned
        val jPinned = j in pinned
        if (iPinned && jPinned) continue
        val (x, y) = offsetOf(a, b)!!
        val (r, u) = frame(a.dir)
        val horizontal = pen.first <= pen.second * HORIZONTAL_BIAS
        val amount = (if (horizontal) pen.first else pen.second) + PUSH_MARGIN_DEG
        val sign = (if (horizontal) x else y).let { if (abs(it) < 1e-3f) (if (i < j) 1f else -1f) else sign(it) }
        val axis = if (horizontal) r else u
        val shareA = if (iPinned) 0f else if (jPinned) 1f else 0.5f
        val shareB = 1f - shareA
        // b is at +sign of a on this axis: a moves the other way, b further out.
        if (shareA > 0f) s[i] = a.copy(dir = turned(a.dir, axis, -sign * amount * shareA))
        if (shareB > 0f) s[j] = b.copy(dir = turned(b.dir, axis, sign * amount * shareB))
        worst = max(worst, amount)
      }
      if (worst == 0f) return s
    }
    return s
  }

  /** Sideways pushes are a little preferred: monitors sit side by side more naturally than stacked. */
  private const val HORIZONTAL_BIAS = 1.25f

  private fun sign(v: Float) = if (v < 0f) -1f else 1f

  /** True when no collision is left among [shells]. */
  fun isClear(shells: List<Shell>, gap: Float = GAP_DEG): Boolean {
    for (i in shells.indices) for (j in i + 1 until shells.size) if (overlaps(shells[i], shells[j], gap)) return false
    return true
  }

  /**
   * The free spot nearest to [target] for a new screen of [size] metres at [distM]: the direction
   * closest to the target (sideways costs less than up / down) where it collides with none of
   * [others]. If nothing is free (the whole sphere is full) the least bad one, which relaxation then
   * sorts out. [target] is where you look, or where the card was dropped.
   */
  fun nearestFree(others: List<Shell>, widthM: Float, heightM: Float, distM: Float, target: Vec, gap: Float = GAP_DEG): Vec {
    val t = unit(target)
    val baseYaw = PostureLayout.yawOf(t)
    val baseElev = PostureLayout.pitchOf(t).coerceIn(-MAX_ELEV_DEG, MAX_ELEV_DEG)
    var best: Vec? = null
    var fallback = t
    var fallbackScore = Float.MAX_VALUE
    val candidates = ArrayList<Triple<Float, Float, Float>>() // cost, yaw offset, elev offset
    var yo = -180f
    while (yo < 180f) {
      var eo = -44f
      while (eo <= 44f) {
        candidates += Triple(sqrt(yo * yo + (eo * VERTICAL_COST) * (eo * VERTICAL_COST)), yo, eo)
        eo += STEP_DEG
      }
      yo += STEP_DEG
    }
    candidates.sortBy { it.first }
    for ((_, yoff, eoff) in candidates) {
      val e = baseElev + eoff
      if (abs(e) > MAX_ELEV_DEG) continue
      val d = PostureLayout.direction(baseYaw + yoff, e)
      val me = Shell(d, distM, widthM, heightM)
      // Free: collides with nothing. Remember the least overlapping one for a full sphere.
      var overlap = 0f
      for (o in others) overlap += penetration(me, o, gap)?.let { min(it.first, it.second) } ?: 0f
      if (overlap == 0f) {
        best = d
        break
      }
      if (overlap < fallbackScore) {
        fallbackScore = overlap
        fallback = d
      }
    }
    return best ?: fallback
  }

  private const val STEP_DEG = 2f

  /** A degree up / down costs this many sideways degrees. */
  private const val VERTICAL_COST = 1.6f

  /** One slot of the tidy grid, degrees right of and above the view centre. */
  data class Slot(val yawDeg: Float, val elevDeg: Float)

  /**
   * "Tidy": a neat grid for screens with the given angular sizes, centred on the view. Rows hold as
   * many screens as fit in [maxRowDeg]; more screens than fit make more rows (the focused row at
   * eye level, the next above, the next below, ...). Left-to-right follows [order] (smaller sorts
   * first: pass the screens' current bearing so they don't swap places needlessly), except that the
   * [centre] screen goes in the middle of the first row. Slots are in the same index order as the input.
   */
  fun tidy(widthsDeg: List<Float>, heightsDeg: List<Float>, order: List<Float>, centre: Int, maxRowDeg: Float = 120f, gap: Float = 4f): List<Slot> {
    val n = widthsDeg.size
    if (n == 0) return emptyList()
    val avgW = widthsDeg.average().toFloat()
    val fit = max(1, ((maxRowDeg + gap) / (avgW + gap)).toInt())
    val rows = (n + fit - 1) / fit
    val perRow = (n + rows - 1) / rows
    val c = centre.coerceIn(0, n - 1)
    val sorted = (0 until n).filter { it != c }.sortedBy { order[it] }.toMutableList()
    sorted.add(((perRow - 1) / 2).coerceAtMost(sorted.size), c)
    val rowMembers = List(rows) { r -> sorted.subList(r * perRow, min(n, (r + 1) * perRow)) }
    val rowHeights = FloatArray(rows) { r -> rowMembers[r].maxOf { heightsDeg[it] } }
    // Row 0 at eye level; row 1 above it, row 2 below, row 3 above that, ...
    val rowElev = FloatArray(rows)
    var upEdge = rowHeights[0] / 2f
    var downEdge = rowHeights[0] / 2f
    for (r in 1 until rows) {
      if (r % 2 == 1) {
        rowElev[r] = upEdge + gap + rowHeights[r] / 2f
        upEdge += gap + rowHeights[r]
      } else {
        rowElev[r] = -(downEdge + gap + rowHeights[r] / 2f)
        downEdge += gap + rowHeights[r]
      }
    }
    val slots = arrayOfNulls<Slot>(n)
    for (r in 0 until rows) {
      val members = rowMembers[r]
      // Up and down the sphere a yaw step covers less of the view: stretch the row so the tiles keep clear of each other.
      val stretch = 1f / cos(rad(min(abs(rowElev[r]) + rowHeights[r] / 2f, 75f)))
      val total = members.sumOf { widthsDeg[it].toDouble() }.toFloat() * stretch + gap * stretch * (members.size - 1)
      var cursor = -total / 2f
      for (i in members) {
        slots[i] = Slot(cursor + widthsDeg[i] * stretch / 2f, rowElev[r])
        cursor += (widthsDeg[i] + gap) * stretch
      }
    }
    // The focused screen is straight ahead: row 0 slides so it sits at yaw 0.
    val shift = slots[c]!!.yawDeg
    return List(n) { i -> slots[i]!!.let { if (i in rowMembers[0]) Slot(it.yawDeg - shift, it.elevDeg) else it } }
  }
}
