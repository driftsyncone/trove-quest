package app.trove.quest.input

import app.trove.quest.drag.DragRules
import app.trove.quest.drag.PanelRect
import app.trove.quest.drag.V3
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

/** A flat thing a ray can point at, and what the stick means there ([Target]). */
data class PointerRegion(val target: Target, val rect: PanelRect)

/** What a hand's ray points at (docs/control-scheme.md section 2a, "Stick (not holding)"). */
object PointerTargets {
  /** The nearest region the ray hits, else [Target.NOTHING]. */
  fun resolve(origin: V3, dir: V3, regions: List<PointerRegion>): Target {
    var best = Target.NOTHING
    var bestT = Float.MAX_VALUE
    for (r in regions) {
      val hit = DragRules.rayHitsRect(origin, dir, r.rect) ?: continue
      if (hit.t < bestT) {
        bestT = hit.t
        best = r.target
      }
    }
    return best
  }
}

/** The world rectangle of a panel at [pose], whose centre is [local] away from the pose in its own frame. */
fun rectAt(pose: Pose, local: Vector3, halfW: Float, halfH: Float): PanelRect {
  val c = pose.t + pose.q.times(local)
  val right = pose.q.times(Vector3(1f, 0f, 0f))
  val up = pose.q.times(Vector3(0f, 1f, 0f))
  val normal = pose.q.times(Vector3(0f, 0f, 1f))
  return PanelRect(V3(c.x, c.y, c.z), V3(right.x, right.y, right.z), V3(up.x, up.y, up.z), V3(normal.x, normal.y, normal.z), halfW, halfH)
}

/**
 * The world rectangle of a level panel at [center] that faces [head] (Storm tiles: `lookRotationAroundY`
 * turns them toward the head about the vertical axis). Built from the two positions alone, so it needs
 * no quaternion maths; [PanelRect.normal] points from the panel to the head. A panel right above / below
 * the head falls back to facing +Z.
 */
fun rectFacing(center: Vector3, head: Vector3, halfW: Float, halfH: Float): PanelRect = rectFacing(V3(center.x, center.y, center.z), V3(head.x, head.y, head.z), halfW, halfH)

fun rectFacing(center: V3, head: V3, halfW: Float, halfH: Float): PanelRect {
  val n = V3(head.x - center.x, 0f, head.z - center.z).normalized(V3(0f, 0f, -1f))
  // Right of a panel seen from the head: up x normal-to-the-viewer, i.e. (n.z, 0, -n.x) mirrored; the sign cannot matter (|u| is tested).
  val right = V3(n.z, 0f, -n.x)
  return PanelRect(center, right, V3(0f, 1f, 0f), n, halfW, halfH)
}
