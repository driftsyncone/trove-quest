package app.trove.quest.drag

import kotlin.math.abs
import kotlin.math.sqrt

/** A plain vector, so the drag rules need no Spatial SDK types (and run in unit tests). */
data class V3(val x: Float, val y: Float, val z: Float) {
  operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)

  operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)

  operator fun times(k: Float) = V3(x * k, y * k, z * k)

  infix fun dot(o: V3): Float = x * o.x + y * o.y + z * o.z

  val length: Float
    get() = sqrt(this dot this)

  /** Unit vector, or [fallback] when this is (nearly) zero. */
  fun normalized(fallback: V3 = V3(0f, 0f, 1f)): V3 = length.let { if (it < 1e-6f) fallback else this * (1f / it) }
}

/** A flat panel in the world: centre, its unit axes, and half its width / height in metres. */
data class PanelRect(val center: V3, val right: V3, val up: V3, val normal: V3, val halfW: Float, val halfH: Float)

/** Where a ray meets a [PanelRect]: [t] along the ray, [u] / [v] in metres from the centre. */
data class RectHit(val t: Float, val u: Float, val v: Float)

/** How a card drag ends (or doesn't yet). */
enum class DragEnd { CONTINUE, DROP, CANCEL }

/**
 * Card drag rules (README "Drag a card into the room"). The wall panel only sees the ray while it is
 * on the panel, so the drag itself is run by [CardDragSystem] from the controller pose; these are
 * its decisions.
 */
object DragRules {
  /** Pointer travel on the card (dp) before a press turns into a drag. */
  const val START_THRESHOLD_DP = 12f

  /** The wall counts as hit this far (metres) outside its edge, so a release at the rim cancels. */
  const val WALL_MARGIN_M = 0.03f

  /**
   * Ray vs. rectangle (either side). Null when the ray is parallel, points away or misses.
   * [margin] grows the rectangle on every side.
   */
  fun rayHitsRect(origin: V3, dir: V3, rect: PanelRect, margin: Float = 0f): RectHit? {
    val denom = dir dot rect.normal
    if (abs(denom) < 1e-6f) return null
    val t = ((rect.center - origin) dot rect.normal) / denom
    if (t <= 0f) return null
    val p = origin + dir * t - rect.center
    val u = p dot rect.right
    val v = p dot rect.up
    return if (abs(u) <= rect.halfW + margin && abs(v) <= rect.halfH + margin) RectHit(t, u, v) else null
  }

  /**
   * The point on the ray exactly [distance] from [head]: the ghost keeps the wall's distance from the
   * user however the hand moves. A ray that never gets that far from the head (or starts beyond it)
   * uses the point [distance] along the ray, pushed onto the same sphere.
   */
  fun ghostPosition(head: V3, origin: V3, dir: V3, distance: Float): V3 {
    val d = dir.normalized()
    val m = origin - head
    val b = m dot d
    val c = (m dot m) - distance * distance
    val disc = b * b - c
    if (disc >= 0f) {
      val t = -b + sqrt(disc)
      if (t >= 0f) return origin + d * t
    }
    val p = origin + d * distance
    return head + (p - head).normalized(d) * distance
  }

  /**
   * Level (yaw-only) direction a panel at [pos] faces away from the user, like Placement.inFront
   * ("local +z points away from them"). Straight ahead when [pos] is right above the head.
   */
  fun facing(head: V3, pos: V3): V3 {
    val d = pos - head
    return V3(d.x, 0f, d.z).normalized()
  }

  /**
   * One step of a running drag. Grip / B / Y ([cancelPressed]) or a lost controller cancel; trigger
   * still held goes on; released with the ray off the wall drops, over the wall cancels.
   */
  fun step(triggerHeld: Boolean, cancelPressed: Boolean, controllerLost: Boolean, rayOnWall: Boolean): DragEnd =
      when {
        cancelPressed || controllerLost -> DragEnd.CANCEL
        triggerHeld -> DragEnd.CONTINUE
        rayOnWall -> DragEnd.CANCEL
        else -> DragEnd.DROP
      }

  /**
   * A press on a card starts the drag by itself once the ray has left the wall (the panel stops
   * reporting then), unless the other hand's grip is on something.
   */
  fun startsOffWall(triggerHeld: Boolean, rayOnWall: Boolean, gripHeld: Boolean): Boolean =
      triggerHeld && !rayOnWall && !gripHeld

  /** Ghost caption: what releasing here will do. */
  fun ghostHint(rayOnWall: Boolean, replaces: Boolean): String =
      when {
        rayOnWall -> "Release over the wall to cancel"
        replaces -> "Release: replaces the focused screen (6/6)"
        else -> "Release: new screen here"
      }
}
