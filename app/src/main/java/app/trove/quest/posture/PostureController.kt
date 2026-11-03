package app.trove.quest.posture

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trove.quest.Placement
import app.trove.quest.hold.HoldSystem
import app.trove.quest.player.MultiScreen
import app.trove.quest.player.PlayerSession
import app.trove.quest.storm.StormManager
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity

/** The wall panel, which lives in the activity: it re-places itself for the posture and turns with the layout. */
interface WallAccess {
  /** Put the wall in front of the user again, for the current posture (a no-op while it is hidden). */
  fun replace()

  /** World position of the wall, or null while it is not there. */
  fun position(): Vector3?

  /** Turn the wall about the vertical axis through [head] by [deltaDeg]. */
  fun turnAbout(head: Vector3, deltaDeg: Float)
}

/**
 * Posture layouts and "Bring screens to me" (README "Posture and bringing screens to you"). The
 * placement math is [PostureLayout]; this reads the head, moves the screens and saves the layout.
 * The chosen posture is remembered ([PREF_POSTURE]) and used for the wall too.
 */
class PostureController(
    private val activity: AppSystemActivity,
    private val prefs: SharedPreferences,
    private val sessions: () -> List<PlayerSession>,
    private val focused: () -> PlayerSession?,
    private val storm: StormManager,
    private val saveLayout: () -> Unit,
) {
  var current by mutableStateOf(Posture.of(prefs.getString(PREF_POSTURE, null)))
    private set

  var wall: WallAccess? = null

  /** One-tap preset: re-lay every open flat screen (and the wall) for [p], round the head where it is now. */
  fun apply(p: Posture) {
    current = p
    prefs.edit().putString(PREF_POSTURE, p.id).apply()
    val quads = sessions().filter { !it.isSphere && !it.behindSphere }
    if (quads.isNotEmpty()) {
      val (head, yaw) = headAndBodyYaw()
      val slots = quads.map { ScreenSlot(it.widthM, if (it === focused()) MultiScreen.FIRST_DISTANCE_M else MultiScreen.EXTRA_DISTANCE_M) }
      val centre = quads.indexOfFirst { it === focused() }.coerceAtLeast(0)
      val placed = PostureLayout.place(p, head, yaw, slots, centre)
      // The preset only places: with Face me on, placeAt turns each screen to the head (yaw and pitch) itself;
      // the pitch given here is what Face me off keeps (lying back tips the screens down at the face).
      for ((s, pl) in quads.zip(placed)) {
        s.placeAt(Pose(Vector3(pl.pos.x, pl.pos.y, pl.pos.z), HoldSystem.levelYaw(pl.yawDeg)), pl.pitchDeg, pl.rollDeg)
      }
      saveLayout()
    }
    wall?.replace()
  }

  /** The wall in front of the user for the current posture. */
  fun placeWall(distanceM: Float): Placed {
    val (head, yaw) = headAndBodyYaw()
    return PostureLayout.placeOne(current, head, yaw, slotYawDeg = 0f, distM = distanceM)
  }

  /**
   * "Bring screens to me": the whole layout (screens, their controls, the wall, the Storm wall)
   * turns about the user, yaw only, until its centre is where the body faces now. Distances,
   * heights, pitch and tilt stay, so a posture layout stays as it is.
   */
  fun bringToMe() {
    val (head, target) = headAndBodyYaw()
    val quads = sessions().filter { !it.isSphere && !it.behindSphere }
    val positions =
        quads.mapNotNull { it.currentPose()?.t }.map { Vec(it.x, it.y, it.z) }.ifEmpty {
          listOfNotNull(wall?.position()).ifEmpty { storm.positions() }.map { Vec(it.x, it.y, it.z) }
        }
    val centre = PostureLayout.centreYawDeg(head, positions) ?: return
    val delta = PostureLayout.bringDelta(centre, target)
    if (kotlin.math.abs(delta) < MIN_TURN_DEG) return
    val h = Vector3(head.x, head.y, head.z)
    for (s in sessions()) s.turnAbout(h, delta)
    wall?.turnAbout(h, delta)
    storm.turnAbout(h, delta)
    saveLayout()
  }

  /** The head position and the yaw the body faces (lying on the back, from the top of the head). */
  fun headAndBodyYaw(): Pair<Vec, Float> {
    val scene = activity.scene
    val (pos, flat) = Placement.head(scene)
    val q = scene.getViewerPose().q
    val f = q.times(Vector3(0f, 0f, 1f))
    val u = q.times(Vector3(0f, 1f, 0f))
    val yaw = PostureLayout.bodyYawDeg(Vec(f.x, f.y, f.z), Vec(u.x, u.y, u.z)) ?: PostureLayout.yawOf(Vec(flat.x, 0f, flat.z))
    return Vec(pos.x, pos.y, pos.z) to yaw
  }

  companion object {
    const val PREF_POSTURE = "posture"

    /** A turn smaller than this is not worth moving everything for. */
    const val MIN_TURN_DEG = 1f
  }
}

/** A level panel's [pose] turned about the vertical axis through [head] by [deltaDeg] (position and facing). */
fun turnedPose(pose: Pose, head: Vector3, deltaDeg: Float): Pose {
  val p = PostureLayout.rotateAbout(Vec(head.x, head.y, head.z), Vec(pose.t.x, pose.t.y, pose.t.z), deltaDeg)
  return Pose(Vector3(p.x, p.y, p.z), HoldSystem.levelYaw(HoldSystem.yawDegOf(pose.q) + deltaDeg))
}
