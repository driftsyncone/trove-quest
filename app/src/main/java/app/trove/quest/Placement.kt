package app.trove.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Where to put things relative to the user's head. */
object Placement {
  private const val FALLBACK_EYE_HEIGHT = 1.5f

  /** Head position and level (yaw-only) forward direction. */
  fun head(scene: Scene): Pair<Vector3, Vector3> {
    val pose = scene.getViewerPose()
    var pos = pose.t
    // Before tracking starts the pose is all zeros; assume a standing user at the origin.
    if (pos.y < 0.3f) pos = Vector3(pos.x, FALLBACK_EYE_HEIGHT, pos.z)
    val f = pose.forward()
    val len = sqrt(f.x * f.x + f.z * f.z)
    val flat = if (len < 1e-3f) Vector3(0f, 0f, 1f) else Vector3(f.x / len, 0f, f.z / len)
    return pos to flat
  }

  /** [distance] metres in front of the user, [dy] metres above eye level, turned to face them. */
  fun inFront(scene: Scene, distance: Float, dy: Float = 0f, dx: Float = 0f): Pose {
    val (headPos, fwd) = head(scene)
    val right = Vector3(fwd.z, 0f, -fwd.x)
    val pos = headPos + fwd * distance + Vector3(0f, dy, 0f) + right * dx
    return Pose(pos, Quaternion.lookRotationAroundY(pos - headPos))
  }

  /** Centred on the head, facing where the user looks: for 180/360 spheres. */
  fun aroundHead(scene: Scene): Pose {
    val (headPos, fwd) = head(scene)
    return Pose(headPos, Quaternion.lookRotationAroundY(fwd))
  }
}

/** Runtime panel ids. R.id values start at 0x7f000000, so this range cannot collide with them. */
object PanelIds {
  private val next = AtomicInteger(0x00100000)

  fun next(): Int = next.getAndIncrement()
}

/** Panel text density: 700 dp per metre (SDK default 500 is too coarse for our layouts). */
const val UI_DP_PER_METER = 700f
