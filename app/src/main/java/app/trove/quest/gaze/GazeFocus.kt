package app.trove.quest.gaze

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.trove.quest.Placement
import app.trove.quest.player.PlayerSession
import app.trove.quest.posture.PostureLayout
import app.trove.quest.posture.Vec
import app.trove.quest.sync.HeadView
import app.trove.quest.sync.SyncScreen
import com.meta.spatial.toolkit.AppSystemActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pointer activity on any of our panels (control panels, the wall): look-to-focus stays quiet while pointing. */
val panelPointer = PointerGate({ SystemClock.elapsedRealtime() })

/**
 * Look to focus (README "Look to focus"). Every [TICK_MS] it reads the head pose
 * (`scene.getViewerPose()`), works out which screen is faced ([GazeRule]) and asks the owner to
 * focus it after the dwell. The toggle is remembered ([PREF_ON], default on); trigger-click focus
 * works either way.
 *
 * It also keeps the head view for the sync heartbeat (where the head points, the faced screen, each
 * screen's bearing), which the partner console draws as its map; that runs whether or not the
 * toggle is on.
 */
class GazeFocus(
    private val activity: AppSystemActivity,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val sessions: () -> List<PlayerSession>,
    private val focusOn: (PlayerSession) -> Unit,
    private val isFocused: (PlayerSession) -> Boolean,
    /** Gripping a panel, the wall up, Black Out: no focus changes from looking. */
    private val blocked: () -> Boolean,
    private val postureId: () -> String = { "sit" },
) {
  /** Wall header toggle. */
  var enabled by mutableStateOf(prefs.getBoolean(PREF_ON, true))
    private set

  private val rule = GazeRule()
  private var view: HeadView? = null
  private var bearings = emptyMap<String, Bearing>()

  private class Bearing(val yawDeg: Float, val pitchDeg: Float, val distM: Float)

  fun toggle(on: Boolean) {
    enabled = on
    rule.reset()
    prefs.edit().putBoolean(PREF_ON, on).apply()
  }

  fun start() {
    scope.launch {
      while (true) {
        delay(TICK_MS)
        runCatching { tick() }
      }
    }
  }

  private fun tick() {
    val list = sessions().filter { !it.isSphere && !it.behindSphere }
    val pose = activity.scene.getViewerPose()
    val (headPos, _) = Placement.head(activity.scene)
    val f = pose.forward()
    val gazeYaw = PostureLayout.yawOf(Vec(f.x, f.y, f.z))
    val gazePitch = PostureLayout.pitchOf(Vec(f.x, f.y, f.z))
    val head = Vec(headPos.x, headPos.y, headPos.z)
    val targets = ArrayList<GazeTarget>(list.size)
    val seen = HashMap<String, Bearing>(list.size)
    val byId = HashMap<String, PlayerSession>(list.size)
    for (s in list) {
      val p = s.currentPose()?.t ?: continue
      val (yaw, pitch, dist) = PostureLayout.bearing(head, Vec(p.x, p.y, p.z))
      if (dist < 0.1f) continue
      seen[s.syncId] = Bearing(yaw, pitch, dist)
      byId[s.syncId] = s
      targets += GazeTarget(s.syncId, yaw, pitch, PostureLayout.angularWidthDeg(s.widthM, dist) / 2f)
    }
    bearings = seen
    view = HeadView(gazeYaw, gazePitch, GazeRule.faced(gazeYaw, gazePitch, targets), postureId())

    val focusedId = list.firstOrNull(isFocused)?.syncId
    val pick = rule.update(SystemClock.elapsedRealtime(), gazeYaw, gazePitch, targets, focusedId, blocked = !enabled || blocked() || panelPointer.recent())
    pick?.let { byId[it] }?.let(focusOn)
  }

  /** For the heartbeat: the head's yaw / pitch, the faced screen and the posture; null before the first tick. */
  fun headView(): HeadView? = view

  /** Adds each screen's bearing from the head (yaw, pitch, distance) to the heartbeat's screens. */
  fun decorate(screens: List<SyncScreen>, sessions: List<PlayerSession>): List<SyncScreen> =
      screens.map { sc ->
        val b = bearings[sc.id] ?: return@map sc
        val s = sessions.firstOrNull { it.syncId == sc.id }
        sc.copy(yawDeg = b.yawDeg, pitchDeg = b.pitchDeg, distM = b.distM, widthM = s?.widthM, rollDeg = s?.rollDeg)
      }

  companion object {
    const val PREF_ON = "gaze_focus"
    const val TICK_MS = 100L
  }
}
