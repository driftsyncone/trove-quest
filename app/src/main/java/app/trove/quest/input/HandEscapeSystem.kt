@file:OptIn(SpatialSDKExperimentalAPI::class)

package app.trove.quest.input

import android.os.SystemClock
import android.util.Log
import app.trove.quest.drag.V3
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ControllerPose
import com.meta.spatial.runtime.JointSet
import com.meta.spatial.runtime.JointType
import com.meta.spatial.runtime.SkeletonJoint
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType

/**
 * Feeds the hands-only escape ([HandEscape], rule and thresholds in HandEscape.kt) once per frame, in
 * every state. Thin SDK glue: the 0.14 SDK exposes no per-finger hand joints of its own to Kotlin
 * (its HandTracker is internal), so the joints come from body tracking (`Scene.updateBodyTrackingBuffersAtTime`,
 * XR_FB_body_tracking, joint set DEFAULT), whose hand joints follow hand tracking while the hands are
 * tracked. Needs the BODY_TRACKING permission ([onHandsSeen] lets the activity ask for it).
 *
 * Gives [onFrame] null (no escape pose) unless: no controller is active, both hands are active hand
 * controllers (`Controller.type == HAND`), the body buffers updated, and every joint used has its
 * location valid + tracked flags. Untested on a device: if the SDK throws, the hands escape is off for
 * the run; if the buffers do not update (no permission, body tracking unavailable) it retries every 5 s.
 */
class HandEscapeSystem(
    private val onFrame: (nowMs: Long, frame: HandFrame?) -> Unit,
    private val onHandsSeen: () -> Unit,
) : SystemBase() {
  /** Debug builds log, every 500 ms while both hands are tracked, why the pose does (not) count. */
  var debug = false

  private var broken = false
  private var jointSetDone = false
  private var retryAtMs = 0L
  private var lastLogMs = 0L
  private val poses = ArrayList<ControllerPose>()
  private val skeleton = ArrayList<SkeletonJoint>()

  override fun execute() {
    val now = SystemClock.uptimeMillis()
    val frame = if (broken) null else read(now)
    onFrame(now, frame)
  }

  private fun read(now: Long): HandFrame? {
    var left = false
    var right = false
    for (e in Query.where { has(Controller.id) }.eval()) {
      if (!e.isLocal()) continue
      val c = e.tryGetComponent<Controller>() ?: continue
      if (!c.isActive) continue
      if (c.type == ControllerType.EYES) continue
      // A controller in use: both grips are the escape there.
      if (c.type != ControllerType.HAND) return null
      val type = e.tryGetComponent<AvatarAttachment>()?.type ?: continue
      if (type.startsWith("left")) left = true else if (type.startsWith("right")) right = true
    }
    if (!left || !right) return null
    onHandsSeen()
    if (now < retryAtMs) return null
    return try {
      val s = getScene()
      if (!jointSetDone) {
        s.setBodyTrackingJointSet(JointSet.DEFAULT)
        jointSetDone = true
      }
      if (!s.updateBodyTrackingBuffersAtTime(poses, skeleton)) {
        retryAtMs = now + RETRY_MS
        if (debug) Log.d(TAG, "hands escape: no body tracking data (permission / unavailable), retry in 5 s")
        null
      } else {
        frame().also { f ->
          if (debug && now - lastLogMs >= LOG_MS) {
            lastLogMs = now
            Log.d(TAG, "hands escape: ${HandPose.explain(f) ?: "POSE"}")
          }
        }
      }
    } catch (t: Throwable) {
      broken = true
      Log.w(TAG, "body tracking unavailable, the hands escape is off", t)
      null
    }
  }

  private fun frame(): HandFrame =
      HandFrame(
          left = hand(LEFT),
          right = hand(RIGHT),
          head = joint(JointType.HEAD),
      )

  private fun hand(j: Array<JointType>): HandJoints? {
    val v = arrayOfNulls<V3>(j.size)
    for (i in j.indices) v[i] = joint(j[i]) ?: return null
    fun finger(at: Int) = FingerJoints(v[at]!!, v[at + 1]!!, v[at + 2]!!, v[at + 3]!!)
    return HandJoints(wrist = v[0]!!, palm = v[1]!!, thumbTip = v[2]!!, index = finger(3), middle = finger(7), ring = finger(11), little = finger(15))
  }

  /** A joint's position, only with both its location flags valid and tracked. */
  private fun joint(t: JointType): V3? {
    val p = poses.getOrNull(t.index) ?: return null
    if (p.flags and ControllerPose.ValidBits != ControllerPose.ValidBits) return null
    if (p.flags and ControllerPose.TrackedBits != ControllerPose.TrackedBits) return null
    val v = p.pose.t
    if (!v.x.isFinite() || !v.y.isFinite() || !v.z.isFinite()) return null
    return V3(v.x, v.y, v.z)
  }

  private companion object {
    const val TAG = "TroveInput"
    const val RETRY_MS = 5000L
    const val LOG_MS = 500L

    /** wrist, palm, thumb tip, then proximal / intermediate / distal / tip of index, middle, ring, little. */
    val LEFT =
        arrayOf(
            JointType.LEFT_HAND_WRIST, JointType.LEFT_HAND_PALM, JointType.LEFT_HAND_THUMB_TIP,
            JointType.LEFT_HAND_INDEX_PROXIMAL, JointType.LEFT_HAND_INDEX_INTERMEDIATE, JointType.LEFT_HAND_INDEX_DISTAL, JointType.LEFT_HAND_INDEX_TIP,
            JointType.LEFT_HAND_MIDDLE_PROXIMAL, JointType.LEFT_HAND_MIDDLE_INTERMEDIATE, JointType.LEFT_HAND_MIDDLE_DISTAL, JointType.LEFT_HAND_MIDDLE_TIP,
            JointType.LEFT_HAND_RING_PROXIMAL, JointType.LEFT_HAND_RING_INTERMEDIATE, JointType.LEFT_HAND_RING_DISTAL, JointType.LEFT_HAND_RING_TIP,
            JointType.LEFT_HAND_LITTLE_PROXIMAL, JointType.LEFT_HAND_LITTLE_INTERMEDIATE, JointType.LEFT_HAND_LITTLE_DISTAL, JointType.LEFT_HAND_LITTLE_TIP,
        )
    val RIGHT =
        arrayOf(
            JointType.RIGHT_HAND_WRIST, JointType.RIGHT_HAND_PALM, JointType.RIGHT_HAND_THUMB_TIP,
            JointType.RIGHT_HAND_INDEX_PROXIMAL, JointType.RIGHT_HAND_INDEX_INTERMEDIATE, JointType.RIGHT_HAND_INDEX_DISTAL, JointType.RIGHT_HAND_INDEX_TIP,
            JointType.RIGHT_HAND_MIDDLE_PROXIMAL, JointType.RIGHT_HAND_MIDDLE_INTERMEDIATE, JointType.RIGHT_HAND_MIDDLE_DISTAL, JointType.RIGHT_HAND_MIDDLE_TIP,
            JointType.RIGHT_HAND_RING_PROXIMAL, JointType.RIGHT_HAND_RING_INTERMEDIATE, JointType.RIGHT_HAND_RING_DISTAL, JointType.RIGHT_HAND_RING_TIP,
            JointType.RIGHT_HAND_LITTLE_PROXIMAL, JointType.RIGHT_HAND_LITTLE_INTERMEDIATE, JointType.RIGHT_HAND_LITTLE_DISTAL, JointType.RIGHT_HAND_LITTLE_TIP,
        )
  }
}
